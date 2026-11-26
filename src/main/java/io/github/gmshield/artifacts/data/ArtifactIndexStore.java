package io.github.gmshield.artifacts.data;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code artifact_index} and {@code artifact_index_removal} (Story 5.1 part B), always within the owner of the work:
 * every read sees that owner's rows only, and a write for another owner is refused. Writes join the caller's
 * transaction, which must exist. Ids, numbers and recipes only.
 */
public interface ArtifactIndexStore {

    /** An artifact version, by its record and number. */
    record VersionKey(UUID recordId, int number) {
    }

    /** Records the input of a new {@code index.artifact} job of the current owner. */
    void insert(ArtifactIndexRow row);

    /** The current owner's row of the job {@code jobId}; empty for an unknown job or another owner's. */
    Optional<ArtifactIndexRow> find(UUID jobId);

    /** The current owner's rows of the version {@code (recordId, number)}. */
    List<ArtifactIndexRow> ofVersion(UUID recordId, int number);

    /** The current owner's rows of the artifacts with these ids, every version. */
    List<ArtifactIndexRow> ofRecords(Collection<UUID> recordIds);

    /** Records the recipe the job {@code jobId} reported on its row; false when the row is gone. */
    boolean recordRecipe(UUID jobId, String recipe);

    /** The current owner's rows of these failed jobs that may still be retried (fewer than the most retries). */
    List<ArtifactIndexRow> retryable(Collection<UUID> jobIds);

    /** Moves the row of {@code jobId} to {@code newJobId}, its recipe cleared and one more retry; empty when gone. */
    Optional<ArtifactIndexRow> move(UUID jobId, UUID newJobId);

    /** Deletes the current owner's rows of the version {@code (recordId, number)}; answers how many went. */
    int deleteOfVersion(UUID recordId, int number);

    /** Deletes the current owner's rows of the artifacts with these ids; answers how many went. */
    int deleteOf(Collection<UUID> recordIds);

    /**
     * The current owner's newest artifact versions that no row reports as indexed under {@code recipe}: none of the
     * version's rows has that recipe (a version with no row at all included).
     */
    List<VersionKey> notIndexedUnder(String recipe);

    /** Records the input of a new {@code index.artifact_removal} job of the current owner. */
    void insertRemoval(ArtifactIndexRemovalRow row);

    /** The current owner's removal row of the job {@code jobId}; empty for an unknown job or another owner's. */
    Optional<ArtifactIndexRemovalRow> findRemoval(UUID jobId);

    /** The current owner's removal rows of these jobs. */
    List<ArtifactIndexRemovalRow> removals(Collection<UUID> jobIds);

    /** Moves the removal row of {@code jobId} to {@code newJobId}; empty when it is gone. */
    Optional<ArtifactIndexRemovalRow> moveRemoval(UUID jobId, UUID newJobId);
}
