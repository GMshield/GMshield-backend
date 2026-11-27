package io.github.gmshield.artifacts.data;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * {@code artifact_reference} (Story 5.4 part A), always within the owner of the work: every read sees that owner's rows
 * only, and an insert for another owner is refused. Writes join the caller's transaction, which must exist. The
 * references are written once, with the artifact a draft was saved as, and deleted with it.
 */
public interface ArtifactReferenceStore {

    /** Stores the current owner's artifact's references (their numbers distinct). */
    void insert(List<ArtifactReferenceRow> rows);

    /** The current owner's artifact {@code recordId}'s references, by number; none for another's. */
    List<ArtifactReferenceRow> of(UUID recordId);

    /** Deletes the references of the current owner's artifacts with these ids; answers how many went. */
    int deleteOf(Collection<UUID> recordIds);
}
