package io.github.gmshield.conversations.data;

import java.util.List;
import java.util.UUID;

/**
 * {@code turn_reference} (Story 2.5), always within the owner of the work: every read sees that owner's rows only, and a
 * write for another owner is refused. Writes join the caller's transaction, which must exist.
 */
public interface TurnReferenceStore {

    /** Records the references of the current owner's turn. */
    void insertReferences(List<TurnReferenceRow> rows);

    /** The current owner's references of a turn, by number; empty for an unknown turn or another owner's. */
    List<TurnReferenceRow> references(UUID turnId);

    /** Deletes the current owner's references of a turn (Story 3.1 part B: its campaign goes); answers how many went. */
    int deleteReferences(UUID turnId);
}
