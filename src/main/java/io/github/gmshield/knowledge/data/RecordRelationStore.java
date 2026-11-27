package io.github.gmshield.knowledge.data;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code record_relation} (Story 5.5 part A, V50), always within the owner of the work: every read sees that owner's
 * rows only, and an insert for another owner is refused. Writes join the caller's transaction, which must exist. The
 * store checks neither side of a link beyond the database's keys: whether a mark applies (a live artifact of the
 * campaign, a live record in its scope) is the service's check, never the store's. A link goes with either of its
 * records' rows (the database's cascade).
 */
public interface RecordRelationStore {

    /** Stores a new link of the current owner; one more of the same kind from the same record is refused. */
    void insert(RecordRelationRow row);

    /** The current owner's link of this kind from the record {@code fromRecordId}; empty for none. */
    Optional<RecordRelationRow> from(UUID fromRecordId, String kind);

    /** The current owner's links of this kind from these records, in no particular order. */
    List<RecordRelationRow> fromAll(Collection<UUID> fromRecordIds, String kind);

    /** The current owner's links of this kind to these records, in no particular order. */
    List<RecordRelationRow> to(Collection<UUID> toRecordIds, String kind);

    /** Points the current owner's link {@code id} to the record {@code toRecordId}; false for an unknown id. */
    boolean retarget(UUID id, UUID toRecordId);

    /** Deletes the current owner's link of this kind from the record {@code fromRecordId}; answers whether one went. */
    boolean deleteFrom(UUID fromRecordId, String kind);

    /**
     * Locks the current owner's {@code record} rows with these ids (an artifact, an extracted record) for the caller's
     * transaction, in id order, so two writes of marks on the same artifact or the same record take turns.
     */
    void lockRecords(Collection<UUID> recordIds);
}
