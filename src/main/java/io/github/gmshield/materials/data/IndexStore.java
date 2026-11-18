package io.github.gmshield.materials.data;

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

    /** The current owner's {@code index.change} inputs of a version, by first ordinal. */
    List<IndexChangeRow> changes(UUID versionId);

    /** Records the recipe the job's result reported; false when the current owner has no such row. */
    boolean recordChangeRecipe(UUID jobId, String recipe);

    /** Deletes the current owner's {@code index.change} inputs of a version (a new set replaces them); how many. */
    int deleteChanges(UUID versionId);

    /** Records the input of an {@code index.removal} job of the current owner. */
    void insertRemoval(IndexRemovalRow row);

    /** The current owner's {@code index.removal} input of this job; empty for an unknown job or another owner's. */
    Optional<IndexRemovalRow> findRemoval(UUID jobId);
}
