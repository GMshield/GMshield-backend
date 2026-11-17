package io.github.gmshield.materials.data;

import java.time.Instant;
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

    /** Deletes the row, whatever its state (a refused format, in the transaction that deletes its version). */
    boolean delete(UUID id);

    /** Deletes the row once its files are gone: the last step of a removal. */
    boolean deleteRemoving(UUID id);

    /** Open uploads past their expiry with no live lease, oldest expiry first. */
    List<UploadRow> expired(Instant now, int limit);

    /** Uploads whose removal has started and not finished. */
    List<UploadRow> removing(int limit);
}
