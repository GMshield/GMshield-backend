package io.github.gmshield.accounts.data;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sessions in Redis (Story 1.10), the accounts module's own store (Spring Session is not used):
 *
 * <ul>
 *   <li>{@code gmshield:session:<session id>}: the {@link Session} record;</li>
 *   <li>{@code gmshield:session:token:<hex of a token's vault hash>}: the id of the session the token belongs
 *       to, for the current token and for every token a renewal retired;</li>
 *   <li>{@code gmshield:session:account:<account id>}: the ids of an account's sessions, so that all can end
 *       at once (a method here when Epic 8 or 9 needs it).</li>
 * </ul>
 *
 * Every change of an existing session is a compare-and-set on its {@code version}: two writers can never
 * both succeed from the same version. Every key has a time to live.
 */
public interface SessionStore {

    /** A token pointer to write with a session: the token's hash and how long the pointer lives. */
    record Pointer(byte[] tokenHash, Duration timeToLive) {

        public Pointer {
            RedisKeys.hex(tokenHash);
            positive(timeToLive);
            tokenHash = tokenHash.clone();
        }

        @Override
        public byte[] tokenHash() {
            return tokenHash.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Pointer that && Arrays.equals(tokenHash, that.tokenHash)
                    && timeToLive.equals(that.timeToLive);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(tokenHash) + timeToLive.hashCode();
        }

        @Override
        public String toString() {
            return "Pointer[timeToLive=" + timeToLive + "]";
        }
    }

    /**
     * Stores a new session for {@code timeToLive}, the pointer of its first token, and its id in its account's
     * set, which then lives at least {@code accountSetTimeToLive}.
     */
    void create(Session session, Pointer pointer, Duration timeToLive, Duration accountSetTimeToLive);

    /** The session a token belongs to, through the token's pointer; empty when either is gone or unreadable. */
    Optional<Session> findByToken(byte[] tokenHash);

    /**
     * Replaces the session whose stored version is still {@code expectedVersion} with {@code next}, kept for
     * {@code timeToLive}, and writes the given pointers. False, with nothing written, when the session is
     * gone or another write came first.
     */
    boolean replace(long expectedVersion, Session next, List<Pointer> pointers, Duration timeToLive);

    /**
     * Ends the session if its stored version is still {@code session.version()}: its record, the pointers of
     * its current and previous tokens, and its entry in its account's set. False, with nothing removed,
     * when the session is already gone or another write came first.
     */
    boolean delete(Session session);

    static void positive(Duration timeToLive) {
        if (timeToLive == null || timeToLive.isNegative() || timeToLive.isZero()) {
            throw new IllegalArgumentException("a key lives for a positive time");
        }
    }
}
