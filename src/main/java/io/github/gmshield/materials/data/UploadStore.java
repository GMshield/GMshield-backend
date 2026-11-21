package io.github.gmshield.materials.data;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The statements on {@code upload}. Every change is conditional, and answers whether it applied: a lease, an offset
 * or a state that moved meanwhile makes it a no-op instead of overwriting another request's work.
 */
public interface UploadStore {

    void insert(UploadRow row);

    Optional<UploadRow> find(UUID id);

    /** The open uploads of {@code owner} for these versions (Story 1.14), by the (owner, version) index. */
    List<UploadRow> openForVersions(UUID owner, Collection<UUID> versionIds);

    /**
     * Takes the lease of an open, unexpired upload of {@code owner} whose verified offset is {@code offset} and whose
     * lease is free or lapsed.
     */
    boolean takeLease(UUID id, UUID owner, long offset, UUID token, Instant until, Instant now);

    /** Extends the lease while the request that holds it works; false when it was lost. */
    boolean renewLease(UUID id, UUID token, Instant until, Instant now);

    /** Gives the lease back, if it is still this token's. */
    void releaseLease(UUID id, UUID token, Instant now);

    /**
     * Counts a verified part: the offset moves from {@code from} to {@code to} and the piece count to {@code pieces},
     * the format is set when given, and the lease is given back; only while the lease and the offset are still the
     * request's.
     */
    boolean advance(UUID id, UUID token, long from, long to, int pieces, String format, Instant now);

    /** The verified upload becomes {@code complete}; only while the lease is the request's and the offset is whole. */
    boolean complete(UUID id, UUID token, Instant now);

    /** A held open upload becomes {@code removing} (its file failed the whole-file check). */
    boolean removeHeld(UUID id, UUID token, Instant now);

    /** An open upload past its expiry, with no live lease, becomes {@code removing}. */
    boolean removeExpired(UUID id, Instant now);

    /**
     * The complete upload of {@code owner} for this version (Story 1.15: the raw file ingestion starts from); from Story
     * 1.21, the first of a set's complete members.
     */
    Optional<UploadRow> completeForVersion(UUID owner, UUID versionId);

    /**
     * A complete upload becomes {@code removing} (Story 1.15: its file was cleaned and stored again, or it needs a
     * password); the upload sweep finishes the removal.
     */
    boolean removeComplete(UUID id, Instant now);

    /** Deletes the row, whatever its state (a refused format, in the transaction that deletes its version). */
    boolean delete(UUID id);

    /** Deletes the row once its files are gone: the last step of a removal. */
    boolean deleteRemoving(UUID id);

    /** Open uploads past their expiry with no live lease, oldest expiry first. */
    List<UploadRow> expired(Instant now, int limit);

    /** Uploads whose removal has started and not finished. */
    List<UploadRow> removing(int limit);

    // --- image sets (Story 1.21)

    /** The uploads of {@code owner}'s version that are not being removed: members by position. */
    List<UploadRow> ofVersion(UUID owner, UUID versionId);

    /** The members (open, complete or failed) of the sets of {@code owner}'s versions, by version and position. */
    List<UploadRow> membersOf(UUID owner, Collection<UUID> versionIds);

    /**
     * Every complete upload of {@code owner}'s version, in order: a set's members by position, else the one raw
     * upload.
     */
    List<UploadRow> completeOfVersion(UUID owner, UUID versionId);

    /**
     * A held open upload becomes {@code failed} with {@code code} (its whole-file check or its format failed), and the
     * lease is given back; only while the lease is the request's.
     */
    boolean failHeld(UUID id, UUID token, String code, Instant now);

    /** An open member past its expiry, with no live lease, becomes {@code failed} with {@code code}. */
    boolean failExpired(UUID id, String code, Instant now);

    /** A failed member becomes {@code removing} (replaced, dropped, or its set discarded). */
    boolean removeFailed(UUID id, Instant now);

    /** The members of {@code owner}'s version after {@code position} move up by one place; answers how many. */
    int closeGap(UUID owner, UUID versionId, int position, Instant now);

    /**
     * One failed member of each set with no open member and whose failed members are all past their expiry: sets
     * nobody can finish any more (their complete members included), oldest expiry first.
     */
    List<UploadRow> abandonedSets(Instant now, int limit);

    /** Every upload of {@code owner}'s version, whatever its state (Story 3.1 part B: the version goes). */
    List<UploadRow> allOfVersion(UUID owner, UUID versionId);
}
