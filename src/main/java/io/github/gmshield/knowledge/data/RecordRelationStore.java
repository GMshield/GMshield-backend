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
 * records' rows (the database's cascade). From Story 5.5 part B (V51) the kind {@value RecordRelationRow#RELATED} has
 * many links from one record: {@link #from} and {@link #deleteFrom} assume one link per kind, which only
 * {@value RecordRelationRow#OVERRIDES} keeps; a relation has no direction, so {@link #between} and {@link #ofEither}
 * read both sides.
 */
public interface RecordRelationStore {

    /**
     * Stores a new link of the current owner; a second override from the same record, or a second link of the same kind
     * from and to the same records, is refused.
     */
    void insert(RecordRelationRow row);

    /**
     * The current owner's link of this kind from the record {@code fromRecordId}; empty for none. For a kind with one
     * link per record ({@value RecordRelationRow#OVERRIDES}).
     */
    Optional<RecordRelationRow> from(UUID fromRecordId, String kind);

    /** The current owner's links of this kind from these records, in no particular order. */
    List<RecordRelationRow> fromAll(Collection<UUID> fromRecordIds, String kind);

    /** The current owner's links of this kind to these records, in no particular order. */
    List<RecordRelationRow> to(Collection<UUID> toRecordIds, String kind);

    /** Points the current owner's link {@code id} to the record {@code toRecordId}; false for an unknown id. */
    boolean retarget(UUID id, UUID toRecordId);

    /**
     * Deletes the current owner's link of this kind from the record {@code fromRecordId}; answers whether one went. For
     * a kind with one link per record ({@value RecordRelationRow#OVERRIDES}).
     */
    boolean deleteFrom(UUID fromRecordId, String kind);

    /**
     * The current owner's links of this kind between the records {@code a} and {@code b}, in either direction (Story 5.5
     * part B), in no particular order.
     */
    List<RecordRelationRow> between(UUID a, UUID b, String kind);

    /**
     * The current owner's links of this kind from or to any of these records (Story 5.5 part B), each once, oldest first
     * (by when it was written, then id).
     */
    List<RecordRelationRow> ofEither(Collection<UUID> recordIds, String kind);

    /** Deletes the current owner's link {@code id} (Story 5.5 part B); answers whether it went. */
    boolean delete(UUID id);

    /**
     * Locks the current owner's {@code record} rows with these ids (an artifact, an extracted record) for the caller's
     * transaction, in id order, so two writes of marks on the same artifact or the same record take turns.
     */
    void lockRecords(Collection<UUID> recordIds);
}
