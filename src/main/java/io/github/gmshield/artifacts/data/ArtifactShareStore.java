package io.github.gmshield.artifacts.data;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * {@code artifact_share} (Story 5.6 part A, V52), always within the owner of the work: every read sees that owner's
 * rows only, and an insert for another owner is refused. Writes join the caller's transaction, which must exist. The
 * store checks neither side beyond the database's keys: whether a share applies (a live artifact, a readable campaign
 * of its system) is the service's check, never the store's. A share goes with its artifact's row (the database's
 * cascade), and a campaign's deletion removes the shares into it ({@link #deleteInto}).
 */
public interface ArtifactShareStore {

    /** Stores a new share of the current owner; a second row for the same artifact and campaign is refused. */
    void insert(ArtifactShareRow row);

    /** The current owner's shares of the artifact {@code artifactId}, oldest first (by when written, then campaign). */
    List<ArtifactShareRow> of(UUID artifactId);

    /** The current owner's shares into the campaign {@code campaignId}, oldest first (by when written, then artifact). */
    List<ArtifactShareRow> into(UUID campaignId);

    /** Deletes the current owner's share of {@code artifactId} with {@code campaignId}; answers whether one went. */
    boolean delete(UUID artifactId, UUID campaignId);

    /** Deletes the current owner's shares of the artifacts with these ids; answers how many went. */
    int deleteOf(Collection<UUID> artifactIds);

    /** Deletes the current owner's shares into the campaigns with these ids; answers how many went. */
    int deleteInto(Collection<UUID> campaignIds);
}
