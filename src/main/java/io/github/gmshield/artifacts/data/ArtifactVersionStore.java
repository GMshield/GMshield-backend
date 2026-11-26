package io.github.gmshield.artifacts.data;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code record_version} (Story 5.1), always within the owner of the work: every read sees that owner's rows only, and
 * an insert for another owner is refused. Writes join the caller's transaction, which must exist. The store checks no
 * record beyond the database's key: the service checks the artifact first. Story 5.2: an edit or a restore inserts the
 * next number; a number the record already has is {@link VersionTaken}, never the database's message.
 */
public interface ArtifactVersionStore {

    /**
     * Stores a new version of the current owner's artifact; {@link VersionTaken} when the record already has that number
     * (Story 5.2: another write took it first).
     */
    void insert(ArtifactVersionRow row);

    /** The newest version of the current owner's artifact {@code recordId}; empty for one with none, or another's. */
    Optional<ArtifactVersionRow> latest(UUID recordId);

    /** The newest version of each of the current owner's artifacts with these ids, by record id; one with none is left out. */
    Map<UUID, ArtifactVersionRow> latest(Collection<UUID> recordIds);

    /** The current owner's version {@code number} of the artifact {@code recordId}; empty for none (Story 5.1 part B). */
    Optional<ArtifactVersionRow> find(UUID recordId, int number);

    /**
     * {@link #find}, with the row locked for the caller's transaction (Story 5.1 part B): an artifact's index jobs are
     * created, retried and completed under this lock, so one version never gets two sets at once.
     */
    Optional<ArtifactVersionRow> lock(UUID recordId, int number);

    /**
     * A page of the current owner's artifact {@code recordId}'s versions, newest first (Story 5.2): only the numbers
     * below {@code before} when it is not null, the first {@code offset} skipped, at most {@code limit}.
     */
    List<ArtifactVersionRow> page(UUID recordId, Integer before, int offset, int limit);

    /** How many versions the current owner's artifact {@code recordId} has (Story 5.2); 0 for another's. */
    long count(UUID recordId);

    /** Deletes every version of the current owner's artifacts with these ids; answers how many went. */
    int deleteOf(Collection<UUID> recordIds);
}
