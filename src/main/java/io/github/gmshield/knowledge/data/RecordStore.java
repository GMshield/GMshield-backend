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
 *
 * <p>Story 5.1: an artifact is a row of {@code record} too (V43), with a campaign instead of a version. No read above
 * ever answers one, not even by its id: each goes through a version, or leaves out a row without one. Only the
 * artifact reads and writes below ({@link #insertArtifact} to {@link #deleteArtifacts}) touch artifact rows, always
 * within campaigns of the owner.
 *
 * <p>Story 5.2 part B: the GM's delete of an artifact sets its {@code deleted_at} ({@link #markArtifactDeleting}) until
 * its deletion's pass removes the row: the artifact is deleting. Every artifact read leaves a deleting one out, except
 * {@link #findArtifacts} and {@link #artifactIdsOf} with {@link Reach#ALL}, which a deletion's own removal reads.
 */
public interface RecordStore {

    /**
     * Which records {@link #findAll} answers (Story 4.5 part B), and (Story 5.2 part B) which artifacts
     * {@link #findArtifacts} and {@link #artifactIdsOf} answer.
     */
    enum Reach {
        /** Live records only: never one the GM deleted (an artifact: never a deleting one). */
        LIVE,
        /** Every record, deleted or not (every artifact, deleting or not). */
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

    /** Stores the current owner's new artifact identity (Story 5.1), in the caller's transaction. */
    void insertArtifact(ArtifactRow row);

    /** The current owner's artifact {@code id} of the campaign {@code campaignId}; empty for any other id (Story 5.1). */
    Optional<ArtifactRow> findArtifact(UUID campaignId, UUID id);

    /**
     * Gives the current owner's artifact {@code id} the type key {@code type} (Story 5.2: an edit or a restore, whose
     * newest version has that type), with its row locked for the caller's transaction, which must exist; answers the row
     * as changed, or empty for an extracted record's id, an unknown one, another owner's or (Story 5.2 part B) a
     * deleting one. Never a correction.
     */
    Optional<ArtifactRow> retypeArtifact(UUID id, String type);

    /**
     * Marks the current owner's live artifact {@code id} deleting at {@code at} (Story 5.2 part B, the GM's delete),
     * with its row locked for the caller's transaction, which must exist, as {@link #retypeArtifact} locks it; answers
     * whether it was marked now: false for a deleting one, an extracted record's id, an unknown one or another owner's.
     */
    boolean markArtifactDeleting(UUID id, Instant at);

    /**
     * The current owner's artifacts with these ids, of any campaign, by id (Story 5.1 part B: an artifact's index jobs
     * check its campaign), deleting ones too only with {@link Reach#ALL} (Story 5.2 part B); an extracted record's id,
     * an unknown one or another owner's is left out.
     */
    List<ArtifactRow> findArtifacts(Collection<UUID> ids, Reach reach);

    /**
     * The current owner's artifacts of the campaign {@code campaignId} (Story 5.1), and from Story 5.6 part B those of
     * any campaign with an id in {@code shared} (the artifacts shared into it; empty for none), in one list newest
     * first (by id, a UUIDv7): only those of the type key {@code type} when it is not null, only those older than
     * {@code before} when it is not null, the first {@code offset} skipped, at most {@code limit}.
     */
    List<ArtifactRow> artifacts(UUID campaignId, Collection<UUID> shared, String type, UUID before, int offset,
                                int limit);

    /**
     * The current owner's live notes of the campaign {@code campaignId} that the Shield {@code shieldId} holds (Story 6.2
     * part A), newest first (by id, a UUIDv7): only those older than {@code before} when it is not null, the first
     * {@code offset} skipped, at most {@code limit}.
     */
    List<ArtifactRow> notesOf(UUID campaignId, UUID shieldId, UUID before, int offset, int limit);

    /** How many live notes of the current owner's campaign {@code campaignId} the Shield {@code shieldId} holds. */
    long noteCount(UUID campaignId, UUID shieldId);

    /**
     * The ids of every note the current owner's Shield {@code shieldId} holds, deleting ones too (Story 6.2 part B: what
     * the Shield's deletion removes), by id.
     */
    List<UUID> allNoteIdsOf(UUID shieldId);

    /** How many live notes the current owner's Shield {@code shieldId} holds (Story 6.2 part B: its deletion's dialog). */
    long liveNoteCountOf(UUID shieldId);

    /**
     * Moves the current owner's live note {@code id}, which a Shield holds, to the Shield {@code shieldId} of the same
     * campaign (Story 6.2 part B; the caller checked it), with its row locked for the caller's transaction, which must
     * exist, as {@link #retypeArtifact} locks it and reads it again; a note already in that Shield is not written.
     * Answers the row as it was before the move (read under the lock), or empty for a note no Shield holds, a deleting
     * one, any other artifact, an extracted record's id, an unknown one or another owner's.
     */
    Optional<ArtifactRow> moveNote(UUID id, UUID shieldId);

    /**
     * The current owner's live artifacts saved from the conversation {@code conversationId} (Story 5.4 part B: its
     * deletion's dialog), oldest first (by when they were written, then id); a deleting one is left out.
     */
    List<ArtifactRow> artifactsOfConversation(UUID conversationId);

    /**
     * How many artifacts the current owner's campaign has (Story 5.1), of the type key {@code type} when not null; from
     * Story 5.6 part B those with an id in {@code shared} counted too, as {@link #artifacts} lists them.
     */
    long artifactCount(UUID campaignId, Collection<UUID> shared, String type);

    /** How many artifacts the current owner's campaigns with these ids hold (Story 5.1, their deletion's dialogs). */
    long artifactsIn(Collection<UUID> campaignIds);

    /**
     * How many artifacts of the type key {@code type} the current owner's campaigns with these ids hold (Story 5.1: an
     * approved type that an artifact uses is in use).
     */
    long artifactsOfTypeIn(Collection<UUID> campaignIds, String type);

    /**
     * How many artifacts of each type key the current owner's campaign has (Story 5.1), from Story 5.6 part B those
     * with an id in {@code shared} counted too; a type with none is left out.
     */
    Map<String, Long> artifactTypes(UUID campaignId, Collection<UUID> shared);

    /**
     * The ids of every artifact of the current owner's campaigns with these ids (Story 5.1, their deletion; from Story
     * 5.1 part B the campaign tier too), by id, deleting ones too only with {@link Reach#ALL} (Story 5.2 part B).
     */
    List<UUID> artifactIdsOf(Collection<UUID> campaignIds, Reach reach);

    /**
     * Deletes the current owner's artifact identities with these ids (Story 5.1, a campaign's deletion, once their
     * versions are gone); answers how many went. An extracted record's id is never deleted here.
     */
    int deleteArtifacts(Collection<UUID> ids);
}
