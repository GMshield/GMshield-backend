package io.github.gmshield.materials.data;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code ingestion_step} (Story 1.15): one ingestion job of a version, over one stage the worker names, of a
 * page range ({@code firstPage} and {@code lastPage} both null for the whole file) of an input file (the raw upload or
 * one of the version's files). From Story 1.16, {@code endedAt}: when its job was completed, finally failed or was
 * cancelled; null while the step may still run.
 */
public record StepRow(UUID id, UUID ownerId, UUID versionId, UUID jobId, String stage, Integer firstPage,
                      Integer lastPage, UUID inputFileId, Instant createdAt, Instant endedAt) {

    /** A step that has not ended. */
    public StepRow(UUID id, UUID ownerId, UUID versionId, UUID jobId, String stage, Integer firstPage,
                   Integer lastPage, UUID inputFileId, Instant createdAt) {
        this(id, ownerId, versionId, jobId, stage, firstPage, lastPage, inputFileId, createdAt, null);
    }

    /** A copy that ended at {@code now}. */
    public StepRow ended(Instant now) {
        return new StepRow(id, ownerId, versionId, jobId, stage, firstPage, lastPage, inputFileId, createdAt, now);
    }
}
