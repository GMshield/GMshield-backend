package io.github.gmshield.accounts.data;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A session as Redis holds it: ids, times, flags and token hashes, never a token (Story 1.10).
 *
 * <ul>
 *   <li>{@code sealVersion}: the account's seal version the current token was issued under.</li>
 *   <li>{@code version}: rises with every write; a write is a compare-and-set on it.</li>
 *   <li>{@code currentHash}, {@code currentUsed}: the hex of the current token's vault hash, and whether that
 *       token has served a call since it was issued.</li>
 *   <li>{@code previousHash}, {@code overlapUntil}: the token the last renewal replaced, still served for
 *       ordinary calls until {@code overlapUntil}; both absent before the first renewal.</li>
 * </ul>
 */
public record Session(UUID id, UUID accountId, int sealVersion, Instant createdAt, Instant lastSeenAt, long version,
                      String currentHash, boolean currentUsed, String previousHash, Instant overlapUntil) {

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    public Session {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        if (currentHash == null || !HASH.matcher(currentHash).matches()) {
            throw new IllegalArgumentException("currentHash is the hex of a 32-byte hash");
        }
        if ((previousHash == null) != (overlapUntil == null)
                || (previousHash != null && !HASH.matcher(previousHash).matches())) {
            throw new IllegalArgumentException("previousHash and overlapUntil come together, the hash in hex");
        }
    }

    /** A new session: version 1, its first token unused, no previous token. */
    public static Session started(UUID id, UUID accountId, int sealVersion, Instant now, byte[] tokenHash) {
        return new Session(id, accountId, sealVersion, now, now, 1, hex(tokenHash), false, null, null);
    }

    /** The hex form of a token hash, as keys and records hold it. */
    public static String hex(byte[] tokenHash) {
        return RedisKeys.hex(tokenHash);
    }

    /**
     * The same session after one of its tokens served a call at {@code now}: the next version, and the current
     * token marked used when it was {@code byCurrentToken}.
     */
    public Session seen(Instant now, boolean byCurrentToken) {
        return new Session(id, accountId, sealVersion, createdAt, now, version + 1, currentHash,
                currentUsed || byCurrentToken, previousHash, overlapUntil);
    }

    /**
     * The same session after a renewal: the next version, a new unused current token issued under
     * {@code newSealVersion}, and the token it replaces as the previous one until {@code overlapUntil}.
     */
    public Session renewed(byte[] newTokenHash, int newSealVersion, Instant now, Instant newOverlapUntil) {
        return new Session(id, accountId, newSealVersion, createdAt, now, version + 1, hex(newTokenHash), false,
                currentHash, newOverlapUntil);
    }

    @Override
    public String toString() {
        // Hashes are not secrets, but nothing reads them in a log line either.
        return "Session[id=" + id + ", accountId=" + accountId + ", sealVersion=" + sealVersion + ", version=" + version
                + ", currentUsed=" + currentUsed + ", renewed=" + (previousHash != null) + "]";
    }
}
