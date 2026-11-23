package io.github.gmshield.jobs.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * What one kind of job does, registered by the feature module that owns the kind (a Spring bean; one per kind).
 * The jobs module calls a handler inside a transaction of its own with the job's owner bound in the
 * {@code OwnerContext}, so library reads and writes work as they do for a request; an exception rolls that
 * transaction back together with the job's change.
 *
 * <p>A handler keeps its job's input in its own tables, keyed by the job id: the job row and Redis never hold
 * content, input or result.
 */
public interface JobHandler {

    /** The kind, a short lower-case word ({@code [a-z][a-z0-9_.-]*}, at most 64 characters). */
    String kind();

    /** The lane a job of this kind is offered on, unless its creator names another. */
    Lane lane();

    /**
     * Whether this job calls a model (Story 2.10; from Story 4.2 per job, since only some steps of a kind may). Each
     * claim of such a job resolves the provider, model and tier through {@link ModelChoices}, records them on the job
     * row and sends them with the claim; a worker that did not state that provider is refused the job
     * ({@code job.provider_unavailable}). Asked at each claim as the owner, in the claim's transaction.
     */
    default boolean callsModel(JobRef job) {
        return false;
    }

    /** The input handed to the worker with a claim: everything it needs for this attempt. */
    JsonNode input(JobRef job);

    /**
     * The input file {@code name} of the job for its live claim (Story 1.15), streamed to the worker by
     * {@code GET .../inputs/{name}}; empty (404) when the job has no such input. Runs as the owner, in a read
     * transaction of its own; the returned input is written after it ended.
     */
    default Optional<JobInput> openInput(JobRef job, String name) {
        return Optional.empty();
    }

    /**
     * The passages with these ids (1 to 100, none null), for the job's live claim (Story 2.2), served to the worker by
     * {@code POST .../passages}: the handler checks every id against what its job may read before anything is
     * decrypted, and answers them in the order asked, each once. Runs as the owner, in a read transaction of its own. A
     * kind whose jobs read no passage keeps this default: {@code job.passages_refused} (403).
     */
    default List<JobPassage> passages(JobRef job, List<UUID> ids) {
        throw JobProblems.passagesRefused();
    }

    /**
     * A numbered batch of answer events of the job's live claim (Story 2.4), posted by the worker to
     * {@code POST .../events}: the handler stores it once, or accepts a batch it already stored (a repeated number of
     * the same claim) without storing it again, and refuses a gap with {@link JobProblems#batchOutOfOrder()}. Runs as
     * the owner, in the transaction of the job's locked row, so the batches of one job are stored one at a time. A kind
     * whose jobs stream no events keeps this default: {@code job.events_refused} (403).
     */
    default void events(JobRef job, JobEventBatch batch) {
        throw JobProblems.eventsRefused();
    }

    /**
     * Whether the job has delivered an event (Story 2.4): once it has, a failed attempt (reported or lapsed) ends the job
     * instead of offering it again, so no answer is spliced from two attempts. Runs as the owner, in the transaction
     * that ends or retries the job.
     */
    default boolean delivered(JobRef job) {
        return false;
    }

    /**
     * Whether the worker must stop the job (Story 2.6): the answer to each event batch it posts. Runs as the owner, in
     * the transaction of the job's locked row that stored the batch, so a stop committed before the batch is seen.
     */
    default boolean stopRequested(JobRef job) {
        return false;
    }

    /**
     * The worker reported the job done; {@code result} is what it sent (an empty object when it sent none). The
     * files the worker sent back are adopted here, with {@link Jobs#adoptOutput}; any it does not adopt are removed
     * once the job ended. A result that cannot be recorded throws {@link JobResultInvalid}.
     */
    void completed(JobRef job, JsonNode result);

    /** The job ended failed: its last attempt failed or lapsed. {@code code} is the reported or the backend's code. */
    void failed(JobRef job, String code);
}
