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
     * The worker reported the job done; {@code result} is what it sent (an empty object when it sent none). The
     * files the worker sent back are adopted here, with {@link Jobs#adoptOutput}; any it does not adopt are removed
     * once the job ended. A result that cannot be recorded throws {@link JobResultInvalid}.
     */
    void completed(JobRef job, JsonNode result);

    /** The job ended failed: its last attempt failed or lapsed. {@code code} is the reported or the backend's code. */
    void failed(JobRef job, String code);
}
