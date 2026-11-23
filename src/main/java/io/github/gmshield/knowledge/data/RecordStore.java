package io.github.gmshield.knowledge.data;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code record} and {@code record_passage} (Story 4.2), always within the owner of the work: every read sees that
 * owner's rows only, and an insert for another owner is refused. Writes join the caller's transaction, which must exist.
 * The store checks no passage or version beyond the database's keys: the ingestion chain checks them first. From Story
 * 4.3 it also reads records one at a time and by type for the records list and page; whether a version is {@code ready}
 * and belongs to the system is the service's check, never the store's.
 */
public interface RecordStore {

    /** Stores new records of the current owner, each with its links to passages. */
    void insert(List<RecordRow> rows);

    /** The current owner's records of a version, by first page then ordinal, each with its passage ids in order. */
    List<RecordRow> ofVersion(UUID versionId);

    /**
     * Deletes the current owner's records of a version whose first page is within {@code firstPage} to
     * {@code lastPage}, their links first; answers how many records went.
     */
    int deleteRange(UUID versionId, int firstPage, int lastPage);

    /** Deletes every record of the current owner's version, their links first; answers how many records went. */
    int deleteOfVersion(UUID versionId);

    /** How many records of the current owner's versions with these ids each type has; a type with none is left out. */
    Map<String, Long> countsByType(Collection<UUID> versionIds);

    /** The current owner's record with this id, with its passage ids in order; empty for an unknown id or another owner's. */
    Optional<RecordRow> find(UUID id);

    /**
     * Every record of the current owner's versions with these ids whose core type key is {@code type}, in no particular
     * order (the service sorts them by their decrypted names). Their passage ids are not read: each row's list is empty.
     */
    List<RecordRow> ofTypeIn(Collection<UUID> versionIds, String type);

    /** The ids of the passages the current owner's record links to, in order; empty for an unknown id or another owner's. */
    List<UUID> passagesOf(UUID recordId);

    /**
     * The current owner's records with these ids (Story 4.3 part B), each with its passage ids in order, in no particular
     * order; an unknown id or another owner's is left out.
     */
    List<RecordRow> findAll(Collection<UUID> ids);

    /**
     * The current owner's records of the versions with these ids whose name fingerprint is {@code nameFingerprint}
     * (Story 4.3 part B: an equality on a keyed fingerprint, never a text search), each with its passage ids in order,
     * by id. A record without a fingerprint (stored before V37) never matches.
     */
    List<RecordRow> named(Collection<UUID> versionIds, byte[] nameFingerprint);
}
