package io.github.gmshield.artifacts.data;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code record_version} (Story 5.1), always within the owner of the work: every read sees that owner's rows only, and
 * an insert for another owner is refused. Writes join the caller's transaction, which must exist. The store checks no
 * record beyond the database's key: the service checks the artifact first.
 */
public interface ArtifactVersionStore {

    /** Stores a new version of the current owner's artifact. */
    void insert(ArtifactVersionRow row);

    /** The newest version of the current owner's artifact {@code recordId}; empty for one with none, or another's. */
    Optional<ArtifactVersionRow> latest(UUID recordId);

    /** The newest version of each of the current owner's artifacts with these ids, by record id; one with none is left out. */
    Map<UUID, ArtifactVersionRow> latest(Collection<UUID> recordIds);

    /** Deletes every version of the current owner's artifacts with these ids; answers how many went. */
    int deleteOf(Collection<UUID> recordIds);
}
