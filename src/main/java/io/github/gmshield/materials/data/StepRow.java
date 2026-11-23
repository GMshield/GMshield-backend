package io.github.gmshield.materials.data;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code ingestion_step} (Story 1.15): one ingestion job of a version, over one stage the worker names, of a
 * page range ({@code firstPage} and {@code lastPage} both null for the whole file) of an input file (the raw upload or
 * one of the version's files). From Story 1.16, {@code endedAt}: when its job was completed, finally failed or was
 * cancelled; null while the step may still run. From Story 1.18, a step may read every text file of its version
 * instead ({@code inputTexts}, with no {@code inputFileId}), and such a step is held, with no job, until every page of
 * the version has an outcome. From Story 4.2, a records step reads the passages of its pages instead
 * ({@code inputPassages}, with no {@code inputFileId}, always with a page range and a job), and {@code recordsUnread}
 * marks one that failed for good: its pages' records could not be read.
 */
public record StepRow(UUID id, UUID ownerId, UUID versionId, UUID jobId, String stage, Integer firstPage,
                      Integer lastPage, UUID inputFileId, boolean inputTexts, boolean inputPassages,
                      boolean recordsUnread, Instant createdAt, Instant endedAt) {

    /** A step over an input file, or over every text file, that is no records step. */
    public StepRow(UUID id, UUID ownerId, UUID versionId, UUID jobId, String stage, Integer firstPage,
                   Integer lastPage, UUID inputFileId, boolean inputTexts, Instant createdAt, Instant endedAt) {
        this(id, ownerId, versionId, jobId, stage, firstPage, lastPage, inputFileId, inputTexts, false, false, createdAt,
                endedAt);
    }

    /** A step over an input file that has not ended. */
    public StepRow(UUID id, UUID ownerId, UUID versionId, UUID jobId, String stage, Integer firstPage,
                   Integer lastPage, UUID inputFileId, Instant createdAt) {
        this(id, ownerId, versionId, jobId, stage, firstPage, lastPage, inputFileId, false, createdAt, null);
    }

    /** A step over an input file. */
    public StepRow(UUID id, UUID ownerId, UUID versionId, UUID jobId, String stage, Integer firstPage,
                   Integer lastPage, UUID inputFileId, Instant createdAt, Instant endedAt) {
        this(id, ownerId, versionId, jobId, stage, firstPage, lastPage, inputFileId, false, createdAt, endedAt);
    }

    /** A held step over every text file of the version (Story 1.18): no job yet, and not ended. */
    public static StepRow held(UUID id, UUID ownerId, UUID versionId, String stage, Integer firstPage,
                               Integer lastPage, Instant createdAt) {
        return new StepRow(id, ownerId, versionId, null, stage, firstPage, lastPage, null, true, createdAt, null);
    }

    /** A records step over the passages of its pages (Story 4.2), with its job, not ended. */
    public static StepRow records(UUID id, UUID ownerId, UUID versionId, UUID jobId, String stage, int firstPage,
                                  int lastPage, Instant createdAt) {
        return new StepRow(id, ownerId, versionId, jobId, stage, firstPage, lastPage, null, false, true, false,
                createdAt, null);
    }

    /** Whether the step waits for its job (Story 1.18): it has none and has not ended. */
    public boolean held() {
        return jobId == null && endedAt == null;
    }

    /** A copy that ended at {@code now}. */
    public StepRow ended(Instant now) {
        return new StepRow(id, ownerId, versionId, jobId, stage, firstPage, lastPage, inputFileId, inputTexts,
                inputPassages, recordsUnread, createdAt, now);
    }

    /** A copy given its job (Story 1.18). */
    public StepRow started(UUID job) {
        return new StepRow(id, ownerId, versionId, job, stage, firstPage, lastPage, inputFileId, inputTexts,
                inputPassages, recordsUnread, createdAt, endedAt);
    }

    /** A copy whose records are unread, or read again (Story 4.2). */
    public StepRow unread(boolean unread) {
        return new StepRow(id, ownerId, versionId, jobId, stage, firstPage, lastPage, inputFileId, inputTexts,
                inputPassages, unread, createdAt, endedAt);
    }
}
