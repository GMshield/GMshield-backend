package io.github.gmshield.jobs.service;

import java.util.List;

/**
 * Records the usage of a job's model calls (Story 2.4). The {@code limits} module implements it, because {@code limits}
 * depends on {@code jobs} and never the reverse; {@link JobClaims} hands it the usage of each event batch, in the
 * transaction that stores the batch, with the model of the attempt. Each call is recorded once, by its call id.
 */
public interface UsageReports {

    /** Records each call's usage once, as the job's owner (bound), for this attempt's model. */
    void record(JobRef job, ModelChoice model, List<CallUsage> usage);
}
