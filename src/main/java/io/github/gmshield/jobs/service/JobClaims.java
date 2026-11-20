package io.github.gmshield.jobs.service;

import io.github.gmshield.jobs.data.JobRow;
import io.github.gmshield.jobs.data.JobStorage;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.TraceContext;
import io.github.gmshield.vault.service.Vault;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
 *       {@code failed} with the reported code. With {@code retry: false} (Story 1.15: an input that no attempt can
 *       change, such as a file that needs a password) it ends {@code failed} at once. Only the backend decides
 *       retries.</li>
 *   <li>From Story 2.1 a claim may state the worker's index recipe ({@code [A-Za-z0-9._-]{1,32}}; anything else is
 *       refused before the claim). One that differs from the last one stated since this backend started, the first one
 *       included, is published as {@link IndexRecipeStated} once the claim ended, whatever its answer. A claim without
 *       one changes nothing. From contract 1.12.0 the worker also states it on its own ({@link #stateRecipe}), at
 *       its start and every check of the backend, so a deploy starts indexing before any job is claimed; the same
 *       comparison and the same event serve both.</li>
 *   <li>From Story 2.10 a claim may state the providers the worker holds a key for (at most 8, each
 *       {@code [a-z0-9-]{1,32}}; anything else is refused before the claim). A claim of a kind that calls a model
 *       ({@link JobHandler#callsModel()}) resolves the provider, model and tier through {@link ModelChoices}, records
 *       them on the job row (overwriting the last claim's) and answers them with the claim. When the worker did not
 *       state that provider (no list means none), the claim rolls back without using an attempt and the job ends
 *       {@code failed} with {@code job.provider_unavailable}, as a job whose input failed does; the claim is answered
 *       {@code job.not_offered}. Any other kind's claim ignores the providers.</li>
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
    /** The longest code {@code job.outcome_code} holds. */
    static final int MAX_CODE_LENGTH = 64;
    static final String DONE = "done";
    static final String FAILED = "failed";
    /** An index recipe as the worker states it (AD-10). */
    static final Pattern RECIPE = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    /** A provider as the worker states it (Story 2.10): an open word, so a new provider needs no contract change. */
    static final Pattern PROVIDER = Pattern.compile("[a-z0-9-]{1,32}");
    /** The most providers a claim may state. */
    static final int MAX_PROVIDERS = 8;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(JobClaims.class);

    private final JobStorage storage;
    private final JobHandlers handlers;
    private final JobOffers offers;
    private final OwnerWork owners;
    private final JobSettings settings;
    private final JobClock clock;
    private final Vault vault;
    private final ApplicationEventPublisher events;
    private final ModelChoices models;
    private final UsageReports usage;
    /** The index recipe the worker stated last since this backend started; null before the first. */
    private final AtomicReference<String> statedRecipe = new AtomicReference<>();

    JobClaims(JobStorage storage, JobHandlers handlers, JobOffers offers, OwnerWork owners, JobSettings settings,
              JobClock clock, Vault vault, ApplicationEventPublisher events, ModelChoices models,
              UsageReports usage) {
        this.storage = storage;
        this.handlers = handlers;
        this.offers = offers;
        this.owners = owners;
        this.settings = settings;
        this.clock = clock;
        this.vault = vault;
        this.events = events;
        this.models = models;
        this.usage = usage;
    }

    /**
     * A claim as the worker receives it. The grant is the only copy there will ever be. {@code model} is null for a kind
     * that calls no model.
     */
    public record Claim(UUID jobId, UUID claimId, int attempt, Instant renewAt, Instant lapsesAt, String grant,
                        String kind, JsonNode input, String traceparent, ModelChoice model) {
        @Override
        public String toString() {
            return "Claim[jobId=" + jobId + ", claimId=" + claimId + ", attempt=" + attempt + "]";
        }
    }

    /** The live claim's new times. */
    public record Renewal(Instant renewAt, Instant lapsesAt) {
    }

    /** {@link #claim(UUID, String, List)} without an index recipe or providers (a worker before contract 1.7.0). */
    public Claim claim(UUID jobId) {
        return claim(jobId, null, null);
    }

    /** {@link #claim(UUID, String, List)} without providers (a worker before contract 1.9.0). */
    public Claim claim(UUID jobId, String indexRecipe) {
        return claim(jobId, indexRecipe, null);
    }

    /**
     * Claims the job, the worker stating its index recipe ({@code null}: none) and the providers it holds a key for
     * ({@code null}: none); see the class.
     */
    public Claim claim(UUID jobId, String indexRecipe, List<String> providers) {
        if ((indexRecipe != null && !RECIPE.matcher(indexRecipe).matches()) || !validProviders(providers)) {
            throw JobProblems.invalid();
        }
        Set<String> stated = providers == null ? Set.of() : Set.copyOf(providers);
        boolean newRecipe = indexRecipe != null && newlyStated(indexRecipe);
        try {
            return offeredClaim(jobId, stated);
        } finally {
            if (newRecipe) {
                stated(indexRecipe);
            }
        }
    }

    /**
     * The worker states its index recipe outside a claim (contract 1.12.0, {@code PUT /internal/v1/index-recipe}): at
     * its start and at every check of the backend. Validated and compared as at a claim; one that differs from the last
     * one stated is published at once ({@link IndexRecipeStated}). Anything but a recipe is refused
     * ({@code request.invalid}) and changes nothing.
     */
    public void stateRecipe(String indexRecipe) {
        if (indexRecipe == null || !RECIPE.matcher(indexRecipe).matches()) {
            throw JobProblems.invalid();
        }
        if (newlyStated(indexRecipe)) {
            stated(indexRecipe);
        }
    }

    /** Records {@code recipe} as the last one stated; whether it differs from the one before. */
    private boolean newlyStated(String recipe) {
        return !recipe.equals(statedRecipe.getAndSet(recipe));
    }

    /**
     * Publishes the stated recipe; a listener's failure is never the claim's (or the statement's), and the next claim or
     * statement publishes it again.
     */
    private void stated(String recipe) {
        try {
            events.publishEvent(new IndexRecipeStated(recipe));
        } catch (RuntimeException failure) {
            statedRecipe.compareAndSet(recipe, null);
            log.atWarn().addKeyValue("errorType", failure.getClass().getName()).log("job.recipe_unpublished");
        }
    }

    /** At most {@link #MAX_PROVIDERS} providers, each a word of {@link #PROVIDER}; none is valid too. */
    static boolean validProviders(List<String> providers) {
        if (providers == null) {
            return true;
        }
        if (providers.size() > MAX_PROVIDERS) {
            return false;
        }
        for (String provider : providers) {
            if (provider == null || !PROVIDER.matcher(provider).matches()) {
                return false;
            }
        }
        return true;
    }

    private Claim offeredClaim(UUID jobId, Set<String> providers) {
        Instant now = clock.now();
        JobRow seen = storage.joined().find(jobId).orElseThrow(JobProblems::notFound);
        // A waiting job may be in the middle of its offer (the message added, the commit not yet done): the locked
        // read below waits for that commit and decides.
        if (!JobState.WAITING.equals(seen.state())) {
            refuseUnlessOffered(seen, now);
        }
        Optional<JobHandler> handler = handlers.find(seen.kind());
        if (handler.isEmpty()) {
            if (JobState.OFFERED.equals(seen.state())) {
                endWithoutHandler(seen, now);
            }
            throw JobProblems.notOffered();
        }
        String grant = newGrant();
        byte[] grantHash = vault.tokenHash(GRANT_PURPOSE, grant);
        JobRow[] offered = new JobRow[1];
        Claim claim;
        try {
            claim = JobTrace.call(seen.traceId(), () -> owners.call(seen.ownerId(), () ->
                    claimLocked(jobId, handler.get(), grant, grantHash, providers, now, offered)));
        } catch (InputFailed failed) {
            // The claim rolled back; the job cannot run without its input: it ends failed instead of being offered forever.
            endUnrunnable(offered[0], handler.get(), JobProblems.INPUT_FAILED, inputErrorType(failed.getCause()), Map.of(),
                    now);
            throw JobProblems.notOffered();
        } catch (ProviderUnavailable unavailable) {
            // The claim rolled back, attempt unused: no worker holds another key, so the job ends instead of waiting.
            endUnrunnable(offered[0], handler.get(), JobProblems.PROVIDER_UNAVAILABLE, "provider_not_stated",
                    Map.of("modelProvider", unavailable.provider(), "statedProviders", unavailable.stated()), now);
            throw JobProblems.notOffered();
        }
        ModelChoice model = claim.model();
        JobTrace.run(seen.traceId(), () -> log.atInfo().addKeyValue("jobId", jobId).addKeyValue("claimId", claim.claimId())
                .addKeyValue("attempt", claim.attempt()).addKeyValue("kind", claim.kind())
                .addKeyValue("modelProvider", model == null ? "none" : model.provider())
                .addKeyValue("modelTier", model == null ? "none" : model.tier())
                .log("job.claimed"));
        return claim;
    }

    /**
     * The claim inside the owner's transaction, on the locked row: the job runs under a new claim, with the model of
     * this attempt for a kind that calls one, and the handler's input. {@code offered[0]} is the row as it was offered,
     * for the caller to end the job when the claim fails with {@link InputFailed} or {@link ProviderUnavailable}.
     */
    private Claim claimLocked(UUID jobId, JobHandler handler, String grant, byte[] grantHash, Set<String> providers,
                              Instant now, JobRow[] offered) {
        JobRow current = storage.joined().findForUpdate(jobId).orElseThrow(JobProblems::notFound);
        refuseUnlessOffered(current, now);
        offered[0] = current;
        JobRow next = current.claimed(Ids.newId(), grantHash, now.plus(settings.renewEvery()),
                now.plus(settings.claimLapse()), now);
        // Resolved again at every claim: a retry follows the setting as it is now.
        ModelChoice model = handler.callsModel() ? models.resolve(ref(next)) : null;
        if (model != null) {
            if (!providers.contains(model.provider())) {
                throw new ProviderUnavailable(model.provider(), providers);
            }
            next = next.withModel(model.provider(), model.model(), model.tier());
        }
        if (!storage.joined().replace(current, next)) {
            throw JobProblems.claimed();
        }
        JsonNode input = inputOf(handler, next);
        return new Claim(next.id(), next.claimId(), next.attempt(), next.claimRenewAt(), next.claimLapsesAt(), grant,
                next.kind(), input, TraceContext.traceparent(next.traceId()), model);
    }

    /** The handler's input for this attempt (an empty object for none), or {@link InputFailed}. */
    private static JsonNode inputOf(JobHandler handler, JobRow next) {
        JsonNode input;
        try {
            input = handler.input(ref(next));
        } catch (RuntimeException failure) {
            throw new InputFailed(failure);
        }
        if (input == null) {
            return JsonNodeFactory.instance.objectNode();
        }
        if (!input.isObject()) {
            throw new InputFailed(null);
        }
        return input;
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

    /** {@link #report(UUID, UUID, String, String, String, Boolean, JsonNode)} without {@code retry}. */
    public void report(UUID jobId, UUID claimId, String grant, String outcome, String code, JsonNode result) {
        report(jobId, claimId, grant, outcome, code, null, result);
    }

    /**
     * The worker's outcome: {@code done} with an optional result, or {@code failed} with an optional code and an
     * optional {@code retry} ({@code false} ends the job at once). The job's change and the handler's call happen in
     * one transaction as the owner; a result the handler cannot record is {@code job.result_invalid} and changes
     * nothing.
     */
    public void report(UUID jobId, UUID claimId, String grant, String outcome, String code, Boolean retry,
                       JsonNode result) {
        boolean done = DONE.equals(outcome);
        if (!done && !FAILED.equals(outcome)
                || code != null && (done || code.length() > MAX_CODE_LENGTH || !CODE.matcher(code).matches())
                || result != null && !done || retry != null && done) {
            throw JobProblems.invalid();
        }
        Reported reported = new Reported(done, Boolean.FALSE.equals(retry), code, result);
        Instant now = clock.now();
        byte[] presented = hashOf(grant);
        JobRow seen = storage.joined().find(jobId).orElse(null);
        if (!grantMatches(seen, claimId, presented, now)) {
            refusedGrant(jobId);
            throw JobProblems.grantRefused();
        }
        Optional<JobHandler> handler = handlers.find(seen.kind());
        JobRow ended;
        try {
            ended = JobTrace.call(seen.traceId(), () -> owners.call(seen.ownerId(), () ->
                    end(jobId, claimId, presented, now, reported, handler)));
        } catch (JobResultInvalid invalid) {
            JobTrace.run(seen.traceId(), () -> log.atWarn().addKeyValue("jobId", jobId).addKeyValue("claimId", claimId)
                    .addKeyValue("reason", invalid.getMessage()).log("job.result_invalid"));
            throw JobProblems.resultInvalid();
        }
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

    /** The most events one batch may carry. */
    public static final int MAX_EVENTS = 500;
    /** The longest delta text, in code points. */
    public static final int MAX_DELTA = 16_384;
    /** The most call usages one batch may carry. */
    public static final int MAX_USAGE = 16;
    /** A provider's response id: printable ASCII, at most 256 characters. */
    static final Pattern RESPONSE_ID = Pattern.compile("[\\x21-\\x7E]{1,256}");

    /**
     * A numbered batch of answer events of the live claim (Story 2.4, internal contract 1.10.0), with the usage of the
     * model calls that ended since the last batch. The live claim's grant comes first ({@code job.grant_refused}, 403),
     * then the batch ({@code job.events_invalid}, 400: a number below 1, more than {@value #MAX_EVENTS} events, an event
     * that is not a delta of 1 to {@value #MAX_DELTA} code points or a citation of a passage id at an offset from 0,
     * more than {@value #MAX_USAGE} usages, or a usage without its call id or with a negative count). Then, as the owner
     * and on the job's locked row, the handler stores the batch ({@link JobHandler#events}: once per claim and number, a
     * gap {@code job.batch_out_of_order}, 409; a kind without events {@code job.events_refused}, 403) and the usage is
     * recorded once per call id ({@link UsageReports}), in that one transaction. Answers whether the worker must stop
     * (Story 2.6, the GM's stop): {@link JobHandler#stopRequested}, read in that same locked transaction after the
     * batch, so a stop committed before it is always seen. Lines carry ids and counts only.
     */
    public boolean events(UUID jobId, UUID claimId, String grant, JobEventBatch batch, List<CallUsage> calls) {
        Instant now = clock.now();
        byte[] presented = hashOf(grant);
        JobRow seen = storage.joined().find(jobId).orElse(null);
        if (seen == null || !grantMatches(seen, claimId, presented, now)) {
            refusedGrant(jobId);
            throw JobProblems.grantRefused();
        }
        List<CallUsage> usage = calls == null ? List.of() : calls;
        if (batch == null || !validBatch(batch) || !validUsage(usage)) {
            JobTrace.run(seen.traceId(), () -> log.atInfo().addKeyValue("jobId", jobId).addKeyValue("claimId", claimId)
                    .addKeyValue("reason", JobProblems.EVENTS_INVALID.code()).log("request.refused"));
            throw JobProblems.eventsInvalid();
        }
        Optional<JobHandler> handler = handlers.find(seen.kind());
        return Boolean.TRUE.equals(JobTrace.call(seen.traceId(), () -> {
            boolean stop;
            try {
                stop = Boolean.TRUE.equals(owners.call(seen.ownerId(), () -> {
                    JobRow current = storage.joined().findForUpdate(jobId).orElse(null);
                    if (current == null || !grantMatches(current, claimId, presented, clock.now())) {
                        throw JobProblems.grantRefused();
                    }
                    JobRef job = ref(current);
                    JobHandler kind = handler.orElseThrow(JobProblems::eventsRefused);
                    kind.events(job, batch);
                    if (!usage.isEmpty() && current.modelProvider() != null) {
                        this.usage.record(job, new ModelChoice(current.modelProvider(), current.modelId(),
                                current.modelTier()), List.copyOf(usage));
                    }
                    return kind.stopRequested(job);
                }));
            } catch (io.github.gmshield.shared.service.ApiException refused) {
                log.atInfo().addKeyValue("jobId", jobId).addKeyValue("claimId", claimId)
                        .addKeyValue("batch", batch.number()).addKeyValue("reason", refused.problem().code())
                        .log("request.refused");
                throw refused;
            }
            log.atDebug().addKeyValue("jobId", jobId).addKeyValue("claimId", claimId)
                    .addKeyValue("batch", batch.number()).addKeyValue("events", batch.events().size())
                    .addKeyValue("usage", usage.size()).addKeyValue("stop", stop).log("job.events_posted");
            return stop;
        }));
    }

    /** A number from 1, at most {@value #MAX_EVENTS} events, each a delta or a citation as {@link #events} says. */
    static boolean validBatch(JobEventBatch batch) {
        if (batch.number() < 1 || batch.events().size() > MAX_EVENTS) {
            return false;
        }
        for (JobEvent event : batch.events()) {
            boolean valid = event != null && switch (event.type()) {
                case JobEvent.DELTA -> event.text() != null && !event.text().isEmpty()
                        && event.text().codePointCount(0, event.text().length()) <= MAX_DELTA
                        && event.passageId() == null && event.offset() == null;
                case JobEvent.CITATION -> event.text() == null && event.passageId() != null && event.offset() != null
                        && event.offset() >= 0;
                case null, default -> false;
            };
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    /** At most {@value #MAX_USAGE} usages, each with its call id, counts from 0 and a response id that is one. */
    static boolean validUsage(List<CallUsage> usage) {
        if (usage.size() > MAX_USAGE) {
            return false;
        }
        for (CallUsage call : usage) {
            if (call == null || call.callId() == null || call.input() < 0 || call.cacheRead() < 0
                    || call.cacheWrite() < 0 || call.output() < 0 || call.reasoning() < 0
                    || call.responseId() != null && !RESPONSE_ID.matcher(call.responseId()).matches()) {
                return false;
            }
        }
        return true;
    }

    /** What the worker reported, once checked. */
    private record Reported(boolean done, boolean lastAttempt, String code, JsonNode result) {
    }

    /** The job's change and the handler's call, inside the owner's transaction of {@link #report}. */
    private JobRow end(UUID jobId, UUID claimId, byte[] presented, Instant now, Reported reported,
                       Optional<JobHandler> handler) {
        JobRow current = storage.joined().findForUpdate(jobId).orElse(null);
        if (!grantMatches(current, claimId, presented, now)) {
            throw JobProblems.grantRefused();
        }
        String code = reported.code() == null ? JobProblems.FAILED : reported.code();
        JobRef job = ref(current);
        // No splice (Story 2.4): a job that delivered an event is never offered again.
        boolean delivered = !reported.done() && handler.map(h -> h.delivered(job)).orElse(false);
        JobRow next;
        if (reported.done()) {
            next = current.released(JobState.DONE, null, now);
        } else if (!reported.lastAttempt() && !delivered && current.attempt() < settings.maxAttempts()) {
            next = current.released(JobState.WAITING, code, now);
        } else {
            next = current.released(JobState.FAILED, code, now);
        }
        if (!storage.joined().replace(current, next)) {
            throw JobProblems.grantRefused();
        }
        if (reported.done()) {
            JsonNode result = reported.result() == null ? JsonNodeFactory.instance.objectNode() : reported.result();
            handler.ifPresent(h -> h.completed(job, result));
        } else if (JobState.FAILED.equals(next.state())) {
            handler.ifPresent(h -> h.failed(job, next.outcomeCode()));
        }
        return next;
    }

    /** The handler's input could not be had (it threw, or it is not a JSON object). Its message names nothing. */
    static final class InputFailed extends RuntimeException {

        private static final long serialVersionUID = 1L;

        InputFailed(RuntimeException cause) {
            super(JobProblems.INPUT_FAILED, cause);
        }
    }

    /**
     * The worker did not state the provider the attempt resolved (Story 2.10). Its message names nothing; the claim it
     * interrupts rolls back. It carries the resolved provider and what the worker stated (sorted, comma-joined, empty
     * for none) for the log line that ends the job.
     */
    static final class ProviderUnavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final String provider;
        private final String stated;

        ProviderUnavailable(String provider, Set<String> stated) {
            super(JobProblems.PROVIDER_UNAVAILABLE);
            this.provider = provider;
            this.stated = String.join(",", new java.util.TreeSet<>(stated));
        }

        String provider() {
            return provider;
        }

        String stated() {
            return stated;
        }
    }

    private static String inputErrorType(Throwable cause) {
        return cause == null ? "not_an_object" : cause.getClass().getName();
    }

    /**
     * Ends an offered job that cannot run (its input failed, or the worker holds no key for its provider) with
     * {@code code}, the handler told as the owner, in one transaction. The attempt is not used. {@code details} are
     * added to the {@code job.failed} line.
     */
    private void endUnrunnable(JobRow offered, JobHandler handler, String code, String errorType,
                               Map<String, String> details, Instant now) {
        JobRow failed = offered.released(JobState.FAILED, code, now);
        boolean ended = JobTrace.call(offered.traceId(), () -> owners.call(offered.ownerId(), () -> {
            if (storage.joined().findForUpdate(offered.id()).isEmpty() || !storage.joined().replace(offered, failed)) {
                return false;
            }
            handler.failed(ref(offered), code);
            return true;
        }));
        if (ended) {
            JobTrace.run(offered.traceId(), () -> {
                org.slf4j.spi.LoggingEventBuilder line = log.atWarn().addKeyValue("jobId", offered.id())
                        .addKeyValue("kind", offered.kind()).addKeyValue("code", code)
                        .addKeyValue("errorType", errorType);
                details.forEach(line::addKeyValue);
                line.log("job.failed");
            });
            if (Lane.of(offered.lane()).orElseThrow() == Lane.INGESTION) {
                offers.offerNextIngestion();
            }
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
    static boolean grantMatches(JobRow row, UUID claimId, byte[] presented, Instant now) {
        return row != null && presented != null && JobState.RUNNING.equals(row.state()) && claimId != null
                && claimId.equals(row.claimId()) && row.claimLapsesAt() != null && row.claimLapsesAt().isAfter(now)
                && sameBytes(row.grantHash(), presented);
    }

    private byte[] hashOf(String grant) {
        return hashOf(vault, grant);
    }

    /** The vault token hash of a presented grant; null for anything that is not a grant as minted. */
    static byte[] hashOf(Vault vault, String grant) {
        return grant == null || !GRANT.matcher(grant).matches() ? null : vault.tokenHash(GRANT_PURPOSE, grant);
    }

    static void refusedGrant(UUID jobId) {
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
