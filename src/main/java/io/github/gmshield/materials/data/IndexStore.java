package io.github.gmshield.materials.data;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code index_change} and {@code index_removal} (Story 2.1), always within the owner of the work, like
 * {@link MaterialStore}: every read sees that owner's rows only, and a write for another owner is refused. Writes join
 * the caller's transaction, which must exist.
 */
public interface IndexStore {

    /** Records the input of an {@code index.change} job of the current owner. */
    void insertChange(IndexChangeRow row);

    /** The current owner's {@code index.change} input of this job; empty for an unknown job or another owner's. */
    Optional<IndexChangeRow> findChange(UUID jobId);

    /**
     * The current owner's {@code index.change} inputs of a version: its passages runs by first ordinal, then its records
     * runs by first page.
     */
    List<IndexChangeRow> changes(UUID versionId);

    /** Records the recipe the job's result reported; false when the current owner has no such row. */
    boolean recordChangeRecipe(UUID jobId, String recipe);

    /**
     * The current owner's {@code index.change} inputs of these jobs that the retry sweep may still move, those with
     * fewer than {@link IndexChangeRow#MAX_RETRIES} retries, by version, then passages runs before records runs, then by
     * first ordinal (indexing at worker start).
     */
    List<IndexChangeRow> retryable(Collection<UUID> jobIds);

    /**
     * Moves the current owner's {@code index.change} input of job {@code jobId} to job {@code newJobId}: the row is
     * replaced by one keyed by the new job, with the same version, range and kind of run ({@code records}), no recipe
     * and one more retry (the retry sweep). Empty when the current owner has no such row; otherwise the new row.
     */
    Optional<IndexChangeRow> moveChange(UUID jobId, UUID newJobId);

    /** Deletes the current owner's {@code index.change} inputs of a version (a new set replaces them); how many. */
    int deleteChanges(UUID versionId);

    /**
     * Deletes the current owner's records runs of a version (Story 4.2 part B: a reconcile that keeps its passages runs
     * cuts its records runs again); how many. Its passages runs stay.
     */
    int deleteRecordChanges(UUID versionId);

    /** Records the input of an {@code index.removal} job of the current owner. */
    void insertRemoval(IndexRemovalRow row);

    /** The current owner's {@code index.removal} input of this job; empty for an unknown job or another owner's. */
    Optional<IndexRemovalRow> findRemoval(UUID jobId);

    /** Whether the current owner's version has an {@code index.removal} input already (Story 3.1 part B). */
    boolean hasRemoval(UUID versionId);

    /**
     * The current owner's {@code index.removal} inputs of these jobs, by job (Story 3.4 part B: the removal retry sweep
     * reads those of the failed jobs).
     */
    List<IndexRemovalRow> removals(Collection<UUID> jobIds);

    /**
     * Moves the current owner's {@code index.removal} input of job {@code jobId} to job {@code newJobId} (Story 3.4
     * part B, the removal retry sweep): the row is replaced by one keyed by the new job, with the same version. Empty
     * when the current owner has no such row; otherwise the new row.
     */
    Optional<IndexRemovalRow> moveRemoval(UUID jobId, UUID newJobId);
}
