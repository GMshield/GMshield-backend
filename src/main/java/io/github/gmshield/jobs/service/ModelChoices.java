package io.github.gmshield.jobs.service;

/**
 * Resolves the model of a job's attempt (Story 2.10). The {@code limits} module implements it, because {@code limits}
 * depends on {@code jobs} and never the reverse; {@link JobClaims} asks it at each claim of a kind whose
 * {@link JobHandler#callsModel()} is true.
 */
public interface ModelChoices {

    /** The provider, model and tier for this attempt of the job. */
    ModelChoice resolve(JobRef job);
}
