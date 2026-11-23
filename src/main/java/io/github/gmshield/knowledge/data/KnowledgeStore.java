package io.github.gmshield.knowledge.data;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code record_type} (Story 4.1), always within the owner of the work: every read sees that owner's rows only, and an
 * insert for another owner is refused. Lookups are exact, by id or by key: there is no text search. Writes join the
 * caller's transaction, which must exist. The store does not know whether a system is ready or being deleted: the
 * service checks the system through {@code library} first.
 */
public interface KnowledgeStore {

    /**
     * Stores new record types of the current owner, in order. Throws {@link KnowledgeConflict} ({@code CORE_TAKEN} or
     * {@code NAME_TAKEN}) when the system already has one's core key or name fingerprint.
     */
    void insertRecordTypes(List<RecordTypeRow> rows);

    /**
     * The current owner's record types of the system {@code systemId}: the core types first, in the core keys' fixed
     * order ({@code CoreRecordTypes.KEYS}), then the others oldest first (ascending id, a UUID v7); the {@code offset} first ones skipped,
     * at most {@code limit}.
     */
    List<RecordTypeRow> recordTypes(UUID systemId, int offset, int limit);

    /** How many record types the current owner's system {@code systemId} has. */
    long recordTypeCount(UUID systemId);

    /** The core keys the current owner's system {@code systemId} already has. */
    Set<String> coreKeys(UUID systemId);

    /** The current owner's record type with this id; empty for an unknown id or another owner's. */
    Optional<RecordTypeRow> findRecordType(UUID id);

    /** Whether a record type of the current owner's system other than {@code except} has this name fingerprint. */
    boolean nameTaken(UUID systemId, byte[] nameFingerprint, UUID except);

    /**
     * Renames the current owner's record type {@code id} and answers the row as stored, its version raised by one; empty
     * for an unknown id or another owner's. Throws {@link KnowledgeConflict}: {@code STALE_VERSION} when the row is not
     * at {@code version} (nothing is written), {@code NAME_TAKEN} when another record type of the system has the new
     * name fingerprint.
     */
    Optional<RecordTypeRow> rename(UUID id, long version, byte[] name, byte[] nameFingerprint);

    /** Deletes the current owner's record type {@code id}; false when there is no such row. */
    boolean delete(UUID id);

    /** Deletes every record type of the current owner's system {@code systemId}; safe to repeat. */
    void deleteOfSystem(UUID systemId);

    /**
     * Locks the ontology of the current owner's system {@code systemId} until the caller's transaction ends (Story 4.4):
     * its core record types, for writing, so two changes of its one name space (types, terms and aliases) run one after
     * the other. A system without them locks nothing.
     */
    void lockOntology(UUID systemId);

    /** Whether a record type of the current owner's system {@code systemId} has this key (Story 4.4). */
    boolean keyTaken(UUID systemId, String key);
}
