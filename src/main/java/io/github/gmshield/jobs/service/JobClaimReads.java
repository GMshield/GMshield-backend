package io.github.gmshield.jobs.service;

import io.github.gmshield.jobs.data.JobRow;
import io.github.gmshield.jobs.data.JobStorage;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.vault.service.Vault;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Library items by id for a claimed job: what {@link JobPassageReads} (Story 2.2) and {@link JobRecordReads} (Story
 * 4.3 part C) share. The live claim's grant comes first ({@code job.grant_refused}, 403); then 1 to {@value #MAX_IDS}
 * ids, none null (the read's own invalid problem, 400). The job's handler answers as the job's owner, in a read
 * transaction of its own; a kind without one gets the read's own refusal (403), as does a handler that keeps the
 * default. Lines carry ids, counts and codes only, never a text, a name or the grant.
 */
@Component
class JobClaimReads {

    /** The most ids one request may ask for. */
    static final int MAX_IDS = 100;
    private static final Logger log = LoggerFactory.getLogger(JobClaimReads.class);

    private final JobStorage storage;
    private final JobHandlers handlers;
    private final OwnerWork owners;
    private final JobClock clock;
    private final Vault vault;

    JobClaimReads(JobStorage storage, JobHandlers handlers, OwnerWork owners, JobClock clock, Vault vault) {
        this.storage = storage;
        this.handlers = handlers;
        this.owners = owners;
        this.clock = clock;
        this.vault = vault;
    }

    /** A handler's read of the items with these ids for one job. */
    @FunctionalInterface
    interface Call<T> {
        List<T> read(JobHandler handler, JobRef job, List<UUID> ids);
    }

    /**
     * What one kind of read is: the problem for a malformed id list, the refusal of a kind with no handler, the
     * handler's read, the line it writes when it answers and the key that counts what it answered.
     */
    record Kind<T>(Supplier<ApiException> invalid, Supplier<ApiException> refused, Call<T> call, String line,
                   String counted) {
    }

    /** The items with these ids for the claimed job, in the order asked, each once. */
    <T> List<T> read(UUID jobId, UUID claimId, String grant, List<UUID> ids, Kind<T> kind) {
        byte[] presented = JobClaims.hashOf(vault, grant);
        JobRow job = storage.joined().find(jobId).orElse(null);
        if (job == null || !JobClaims.grantMatches(job, claimId, presented, clock.now())) {
            JobClaims.refusedGrant(jobId);
            throw JobProblems.grantRefused();
        }
        if (ids == null || ids.isEmpty() || ids.size() > MAX_IDS || ids.stream().anyMatch(Objects::isNull)) {
            ApiException invalid = kind.invalid().get();
            JobTrace.run(job.traceId(), () -> log.atInfo().addKeyValue("jobId", jobId).addKeyValue("claimId", claimId)
                    .addKeyValue("kind", job.kind()).addKeyValue("requested", ids == null ? 0 : ids.size())
                    .addKeyValue("reason", invalid.problem().code()).log("request.refused"));
            throw invalid;
        }
        List<UUID> asked = List.copyOf(ids);
        Optional<JobHandler> handler = handlers.find(job.kind());
        return JobTrace.call(job.traceId(), () -> {
            try {
                List<T> read = owners.read(job.ownerId(), () -> kind.call().read(handler
                        .orElseThrow(kind.refused()), JobClaims.ref(job), asked));
                log.atInfo().addKeyValue("jobId", jobId).addKeyValue("claimId", claimId)
                        .addKeyValue("requested", asked.size()).addKeyValue(kind.counted(), read.size())
                        .log(kind.line());
                return read;
            } catch (ApiException refused) {
                log.atInfo().addKeyValue("jobId", jobId).addKeyValue("kind", job.kind())
                        .addKeyValue("requested", asked.size()).addKeyValue("reason", refused.problem().code())
                        .log("request.refused");
                throw refused;
            }
        });
    }
}
