package io.github.gmshield.jobs.service;

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

    /** The worker reported the job done; {@code result} is what it sent (an empty object when it sent none). */
    void completed(JobRef job, JsonNode result);

    /** The job ended failed: its last attempt failed or lapsed. {@code code} is the reported or the backend's code. */
    void failed(JobRef job, String code);
}
