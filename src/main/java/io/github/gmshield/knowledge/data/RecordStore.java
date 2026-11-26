package io.github.gmshield.knowledge.data;

import java.time.Instant;
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
 *
 * <p>Story 4.5 part B: a record the GM deleted stays as a tombstone ({@link RecordRow#deleted}). Every read below
 * leaves it out unless it says otherwise: {@link #findAll} by its {@link Reach}, {@link #tombstones}, and the deletes
 * of a version's rows, which take tombstones too.
 */
public interface RecordStore {

    /** Which records {@link #findAll} answers (Story 4.5 part B). */
    enum Reach {
        /** Live records only: never one the GM deleted. */
        LIVE,
        /** Every record, deleted or not. */
        ALL
    }

    /** Stores new records of the current owner, each with its links to passages. */
    void insert(List<RecordRow> rows);

    /**
     * The current owner's live records of a version, by first page then ordinal, each with its passage ids in order: the
     * index's inputs read these, so a deleted record's point is never written again.
     */
    List<RecordRow> ofVersion(UUID versionId);

    /**
     * The current owner's deleted records of a version (Story 4.5 part B), by first page then ordinal, each with its
     * passage ids in order (a tombstone copied by a carry-over has none).
     */
    List<RecordRow> tombstones(UUID versionId);

    /**
     * Deletes the current owner's records of a version whose first page is within {@code firstPage} to
     * {@code lastPage}, their links first; answers how many records went.
     */
    int deleteRange(UUID versionId, int firstPage, int lastPage);

    /**
     * Deletes every record of the current owner's version, tombstones included, their links first; answers how many
     * records went.
     */
    int deleteOfVersion(UUID versionId);

    /**
     * How many live records of the current owner's versions with these ids each type has; a type with none is left out.
     */
    Map<String, Long> countsByType(Collection<UUID> versionIds);

    /**
     * The version id of the current owner's live record with this id, read without loading the row (Story 4.5 part B:
     * a writer locks that version before it reads the row); empty for an unknown id, another owner's or a deleted one.
     */
    Optional<UUID> versionOf(UUID id);

    /**
     * The current owner's live record with this id, with its passage ids in order; empty for an unknown id, another
     * owner's or a deleted one.
     */
    Optional<RecordRow> find(UUID id);

    /**
     * Every live record of the current owner's versions with these ids whose core type key is {@code type}, in no particular
     * order (the service sorts them by their decrypted names). Their passage ids are not read: each row's list is empty.
     */
    List<RecordRow> ofTypeIn(Collection<UUID> versionIds, String type);

    /** The ids of the passages the current owner's record links to, in order; empty for an unknown id or another owner's. */
    List<UUID> passagesOf(UUID recordId);

    /**
     * The current owner's records with these ids (Story 4.3 part B) that {@code reach} takes (Story 4.5 part B), each
     * with its passage ids in order, in no particular order; an unknown id or another owner's is left out.
     */
    List<RecordRow> findAll(Collection<UUID> ids, Reach reach);

    /**
     * The current owner's live records of the versions with these ids whose name fingerprint is {@code nameFingerprint}
     * (Story 4.3 part B: an equality on a keyed fingerprint, never a text search), each with its passage ids in order,
     * by id. A record without a fingerprint (stored before V37) never matches.
     */
    List<RecordRow> named(Collection<UUID> versionIds, byte[] nameFingerprint);

    /**
     * The current owner's live records of the versions with these ids, of one of these type keys, whose name
     * fingerprint is one of {@code nameFingerprints} (Story 4.6: a fingerprint-IN equality, never a text search), each
     * with its passage ids in order, by id, at most {@code limit}. A record without a fingerprint never matches.
     */
    List<RecordRow> namedAny(Collection<UUID> versionIds, Collection<String> recordTypes,
                             Collection<byte[]> nameFingerprints, int limit);

    /**
     * Writes the GM's correction (Story 4.5) of the current owner's record {@code id}, made on its row version
     * {@code version}: its type key, name, name fingerprint and fields become the correction's, and its last correction
     * time; on its first correction, the type key and fingerprint it had are kept as its extracted match key. Its id,
     * links, ordinal and first page stay. Answers the row as stored, its version raised by one, with its passage ids in
     * order; empty for an unknown id, another owner's or a deleted one. Throws {@link KnowledgeConflict}
     * {@code STALE_VERSION} when the row is not at {@code version}, or its version moved meanwhile (nothing is
     * written).
     */
    Optional<RecordRow> correct(UUID id, long version, RecordCorrection correction);

    /**
     * Carries an earlier version's correction over to the current owner's record {@code id} (Story 4.5, at a new
     * version's acceptance): its type key, name, name fingerprint and fields become the correction's, with its time, and
     * its extracted match key becomes {@code extractedType} and {@code extractedNameFingerprint} (the corrected record's,
     * kept from its first correction on). Its id, links, ordinal and first page stay. False for an unknown id or another
     * owner's.
     */
    boolean carry(UUID id, RecordCorrection correction, String extractedType, byte[] extractedNameFingerprint);

    /**
     * The GM's delete of the current owner's live record {@code id} (Story 4.5 part B), with its row locked: it becomes a
     * tombstone at {@code at}, and with {@code pointRemoved} (a record never indexed) its point counts as removed at
     * once. Its links, name and fields stay. False for an unknown id, another owner's or one already deleted (nothing is
     * written).
     */
    boolean delete(UUID id, Instant at, boolean pointRemoved);

    /**
     * Marks the current owner's deleted records with these ids as having no point any more, at {@code at} (Story 4.5
     * part B, a removal job's completion); a live record, one already marked, an unknown id or another owner's is left
     * as it is. Answers how many were marked.
     */
    int pointsRemoved(Collection<UUID> ids, Instant at);
}
