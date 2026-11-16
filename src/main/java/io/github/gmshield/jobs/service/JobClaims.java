package io.github.gmshield.jobs.service;

import io.github.gmshield.jobs.data.JobRow;
import io.github.gmshield.jobs.data.JobStorage;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.TraceContext;
import io.github.gmshield.vault.service.Vault;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The worker's side of a job (Story 1.12): a claim, its renewals and the outcome.
 *
 * <ul>
 *   <li>A claim takes an {@code offered} job: one more attempt, a new claim id, a new random grant (32 bytes,
 *       Base64url) of which only the vault token hash ({@code "grant"}) is stored, and the claim's renew and lapse
 *       times. It is refused with {@code job.claimed} while another claim is live, and with {@code job.not_offered}
 *       for a job that is waiting, done, failed or cancelled. The handler's input comes with it.</li>
 *   <li>A renewal or an outcome must present the live claim's grant, for that job and that claim, before it lapses;
 *       anything else is {@code job.grant_refused}, whichever part is wrong.</li>
 *   <li>A {@code failed} outcome sends the job back to waiting while attempts remain; otherwise the job ends
 *       {@code failed} with the reported code. Only the backend decides retries.</li>
 * </ul>
 *
 * Every handler call runs as the job's owner ({@link OwnerWork}). Every line about a job carries the trace id of
 * the request that created it; the grant never appears anywhere but in the claim's answer.
 */
@Service
public class JobClaims {

    static final String GRANT_PURPOSE = "grant";
    static final int GRANT_BYTES = 32;
    /** A grant as minted: 32 bytes in unpadded Base64url. Anything else is refused before it is hashed. */
    static final Pattern GRANT = Pattern.compile("[A-Za-z0-9_-]{43}");
    /** An outcome code: {@code category.reason}, lower case. */
    static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{0,30}+(?:\\.[a-z0-9_]{1,30}+){1,3}+");
    static final String DONE = "done";
    static final String FAILED = "failed";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(JobClaims.class);

    private final JobStorage storage;
    private final JobHandlers handlers;
    private final JobOffers offers;
    private final OwnerWork owners;
    private final JobSettings settings;
    private final JobClock clock;
    private final Vault vault;

    JobClaims(JobStorage storage, JobHandlers handlers, JobOffers offers, OwnerWork owners, JobSettings settings,
              JobClock clock, Vault vault) {
        this.storage = storage;
        this.handlers = handlers;
        this.offers = offers;
        this.owners = owners;
        this.settings = settings;
        this.clock = clock;
        this.vault = vault;
    }

    /** A claim as the worker receives it. The grant is the only copy there will ever be. */
    public record Claim(UUID jobId, UUID claimId, int attempt, Instant renewAt, Instant lapsesAt, String grant,
                        String kind, JsonNode input, String traceparent) {
        @Override
        public String toString() {
            return "Claim[jobId=" + jobId + ", claimId=" + claimId + ", attempt=" + attempt + "]";
        }
    }

    /** The live claim's new times. */
    public record Renewal(Instant renewAt, Instant lapsesAt) {
    }

    public Claim claim(UUID jobId) {
        Instant now = clock.now();
        JobRow seen = storage.joined().find(jobId).orElseThrow(JobProblems::notFound);
        refuseUnlessOffered(seen, now);
        Optional<JobHandler> handler = handlers.find(seen.kind());
        if (handler.isEmpty()) {
            endWithoutHandler(seen, now);
            throw JobProblems.notOffered();
        }
        String grant = newGrant();
        byte[] grantHash = vault.tokenHash(GRANT_PURPOSE, grant);
        Claim claim = JobTrace.call(seen.traceId(), () -> owners.call(seen.ownerId(), () -> {
            JobRow current = storage.joined().findForUpdate(jobId).orElseThrow(JobProblems::notFound);
            refuseUnlessOffered(current, now);
            JobRow next = current.claimed(Ids.newId(), grantHash, now.plus(settings.renewEvery()),
                    now.plus(settings.claimLapse()), now);
            if (!storage.joined().replace(current, next)) {
                throw JobProblems.claimed();
            }
            JsonNode input = handler.get().input(ref(next));
            return new Claim(next.id(), next.claimId(), next.attempt(), next.claimRenewAt(), next.claimLapsesAt(), grant,
                    next.kind(), input == null ? JsonNodeFactory.instance.objectNode() : input,
                    TraceContext.traceparent(next.traceId()));
        }));
        JobTrace.run(seen.traceId(), () -> log.atInfo().addKeyValue("jobId", jobId).addKeyValue("claimId", claim.claimId())
                .addKeyValue("attempt", claim.attempt()).addKeyValue("kind", claim.kind()).log("job.claimed"));
        return claim;
    }

    public Renewal renew(UUID jobId, UUID claimId, String grant) {
        Instant now = clock.now();
        byte[] presented = hashOf(grant);
        JobRow renewed;
        try {
            renewed = storage.separately(store -> {
                JobRow row = store.findForUpdate(jobId).orElse(null);
                if (!grantMatches(row, claimId, presented, now)) {
                    throw JobProblems.grantRefused();
                }
                JobRow next = row.renewed(now.plus(settings.renewEvery()), now.plus(settings.claimLapse()), now);
                if (!store.replace(row, next)) {
                    throw JobProblems.grantRefused();
                }
                return next;
            });
        } catch (io.github.gmshield.shared.service.ApiException refused) {
            refusedGrant(jobId);
            throw refused;
        }
        JobTrace.run(renewed.traceId(), () -> log.atDebug().addKeyValue("jobId", jobId).addKeyValue("claimId", claimId)
                .log("job.renewed"));
        return new Renewal(renewed.claimRenewAt(), renewed.claimLapsesAt());
    }

    /**
     * The worker's outcome: {@code done} with an optional result, or {@code failed} with an optional code. The job's
     * change and the handler's call happen in one transaction as the owner.
     */
    public void report(UUID jobId, UUID claimId, String grant, String outcome, String code, JsonNode result) {
        boolean done = DONE.equals(outcome);
        if (!done && !FAILED.equals(outcome) || code != null && (done || !CODE.matcher(code).matches())
                || result != null && !done) {
            throw JobProblems.invalid();
        }
        Instant now = clock.now();
        byte[] presented = hashOf(grant);
        JobRow seen = storage.joined().find(jobId).orElse(null);
        if (!grantMatches(seen, claimId, presented, now)) {
            refusedGrant(jobId);
            throw JobProblems.grantRefused();
        }
        Optional<JobHandler> handler = handlers.find(seen.kind());
        JobRow ended = JobTrace.call(seen.traceId(), () -> owners.call(seen.ownerId(), () -> {
            JobRow current = storage.joined().findForUpdate(jobId).orElse(null);
            if (!grantMatches(current, claimId, presented, now)) {
                throw JobProblems.grantRefused();
            }
            JobRow next;
            if (done) {
                next = current.released(JobState.DONE, null, now);
            } else if (current.attempt() < settings.maxAttempts()) {
                next = current.released(JobState.WAITING, code == null ? JobProblems.FAILED : code, now);
            } else {
                next = current.released(JobState.FAILED, code == null ? JobProblems.FAILED : code, now);
            }
            if (!storage.joined().replace(current, next)) {
                throw JobProblems.grantRefused();
            }
            JobRef job = ref(current);
            if (done) {
                handler.ifPresent(h -> h.completed(job, result == null ? JsonNodeFactory.instance.objectNode() : result));
            } else if (JobState.FAILED.equals(next.state())) {
                handler.ifPresent(h -> h.failed(job, next.outcomeCode()));
            }
            return next;
        }));
        JobTrace.run(ended.traceId(), () -> log.atInfo().addKeyValue("jobId", jobId).addKeyValue("claimId", claimId)
                .addKeyValue("attempt", ended.attempt()).addKeyValue("state", ended.state())
                .addKeyValue("code", ended.outcomeCode() == null ? "none" : ended.outcomeCode())
                .log(done ? "job.done" : JobState.WAITING.equals(ended.state()) ? "job.retrying" : "job.failed"));
        Lane lane = Lane.of(ended.lane()).orElseThrow();
        if (JobState.WAITING.equals(ended.state())) {
            offers.offerDue(lane, jobId);
        } else if (lane == Lane.INGESTION) {
            offers.offerNextIngestion();
        }
    }

    /** A claim is possible only for an offered job; a live claim is "claimed", anything else "not offered". */
    private static void refuseUnlessOffered(JobRow row, Instant now) {
        if (JobState.OFFERED.equals(row.state())) {
            return;
        }
        if (JobState.RUNNING.equals(row.state()) && row.claimLapsesAt() != null && row.claimLapsesAt().isAfter(now)) {
            throw JobProblems.claimed();
        }
        throw JobProblems.notOffered();
    }

    /** A job of a kind this backend has no handler for cannot run: it ends failed, and the lane moves on. */
    private void endWithoutHandler(JobRow row, Instant now) {
        boolean ended = storage.separately(store -> store.replace(row, row.released(JobState.FAILED,
                JobProblems.UNKNOWN_KIND, now)));
        if (ended) {
            JobTrace.run(row.traceId(), () -> log.atWarn().addKeyValue("jobId", row.id()).addKeyValue("kind", row.kind())
                    .addKeyValue("code", JobProblems.UNKNOWN_KIND).log("job.failed"));
            if (Lane.of(row.lane()).orElseThrow() == Lane.INGESTION) {
                offers.offerNextIngestion();
            }
        }
    }

    /** The live claim's grant, for this job and this claim, not lapsed: compared in constant time. */
    private static boolean grantMatches(JobRow row, UUID claimId, byte[] presented, Instant now) {
        return row != null && presented != null && JobState.RUNNING.equals(row.state()) && claimId != null
                && claimId.equals(row.claimId()) && row.claimLapsesAt() != null && row.claimLapsesAt().isAfter(now)
                && sameBytes(row.grantHash(), presented);
    }

    private byte[] hashOf(String grant) {
        return grant == null || !GRANT.matcher(grant).matches() ? null : vault.tokenHash(GRANT_PURPOSE, grant);
    }

    private static void refusedGrant(UUID jobId) {
        log.atInfo().addKeyValue("jobId", jobId).addKeyValue("reason", "job.grant_refused").log("request.refused");
    }

    static String newGrant() {
        byte[] bytes = new byte[GRANT_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static JobRef ref(JobRow row) {
        return new JobRef(row.id(), row.ownerId(), row.kind(), Lane.of(row.lane()).orElseThrow(), row.attempt(),
                row.traceId());
    }

    /** Constant-time comparison: the time depends on the lengths only, never on where the bytes differ. */
    static boolean sameBytes(byte[] expected, byte[] given) {
        if (expected == null || given == null) {
            return false;
        }
        int difference = expected.length ^ given.length;
        for (int i = 0; i < expected.length; i++) {
            difference |= expected[i] ^ (i < given.length ? given[i] : 0);
        }
        return difference == 0;
    }
}
