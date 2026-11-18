package io.github.gmshield.accounts.data;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sessions, token pointers and each account's set in memory, under the Redis key names and with the values
 * Redis would hold, for tests that run without Redis. It mirrors the Redis store: each write is atomic (the
 * methods are synchronized), a replacement compares the stored version first (an ending does not), and a
 * key past its time to live, measured on the injected clock, is gone.
 */
public class InMemorySessionStore implements SessionStore {

    /** Key to the string Redis would hold (a session's JSON, or a pointer's session id). */
    public final Map<String, String> values = new ConcurrentHashMap<>();
    /** Account set key to its members (session ids). */
    public final Map<String, Set<String>> accountSets = new ConcurrentHashMap<>();
    /** Key to the time to live it was last written with. */
    public final Map<String, Duration> timesToLive = new ConcurrentHashMap<>();
    private final Map<String, Instant> expiries = new ConcurrentHashMap<>();
    private final Clock clock;
    /**
     * How many of the next {@link #replace} calls lose their compare-and-set, as if another call had just written
     * the session (its version rises, the write is not applied).
     */
    public int replacesToLose;
    /**
     * How many of the next {@link #delete} calls meet a write that came just before them (the stored version
     * rises first), as a concurrent call marking the session seen would.
     */
    public int writesBeforeDelete;

    public InMemorySessionStore(Clock clock) {
        this.clock = clock;
    }

    /** Forgets everything, as a fresh Redis would. */
    public synchronized void clear() {
        replacesToLose = 0;
        writesBeforeDelete = 0;
        values.clear();
        accountSets.clear();
        timesToLive.clear();
        expiries.clear();
    }

    /** The live value of a key, as {@code GET} would answer now. */
    public synchronized Optional<String> get(String key) {
        expire(key);
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public synchronized void create(Session session, Pointer pointer, Duration timeToLive, Duration accountSetTimeToLive) {
        SessionStore.positive(timeToLive);
        SessionStore.positive(accountSetTimeToLive);
        put(RedisKeys.session(session.id()), RedisSessionStore.write(session), timeToLive);
        put(RedisKeys.sessionToken(pointer.tokenHash()), session.id().toString(), pointer.timeToLive());
        String set = RedisKeys.accountSessions(session.accountId());
        expire(set);
        accountSets.computeIfAbsent(set, key -> ConcurrentHashMap.newKeySet()).add(session.id().toString());
        Instant setExpiry = expiries.get(set);
        Instant wanted = clock.instant().plus(accountSetTimeToLive);
        if (setExpiry == null || setExpiry.isBefore(wanted)) {
            expiries.put(set, wanted);
            timesToLive.put(set, accountSetTimeToLive);
        }
    }

    @Override
    public synchronized Optional<Session> findByToken(byte[] tokenHash) {
        String pointer = RedisKeys.sessionToken(tokenHash);
        expire(pointer);
        String sessionId = values.get(pointer);
        if (sessionId == null) {
            return Optional.empty();
        }
        UUID id;
        try {
            id = UUID.fromString(sessionId);
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
        String key = RedisKeys.session(id);
        expire(key);
        return RedisSessionStore.read(values.get(key)).filter(session -> session.id().equals(id));
    }

    @Override
    public synchronized boolean replace(long expectedVersion, Session next, List<Pointer> pointers, Duration timeToLive) {
        SessionStore.positive(timeToLive);
        String key = RedisKeys.session(next.id());
        if (storedVersion(key) != expectedVersion) {
            return false;
        }
        if (replacesToLose > 0) {
            replacesToLose--;
            Session other = RedisSessionStore.read(values.get(key)).orElseThrow().seen(clock.instant(), false);
            values.put(key, RedisSessionStore.write(other));
            return false;
        }
        put(key, RedisSessionStore.write(next), timeToLive);
        for (Pointer pointer : pointers) {
            put(RedisKeys.sessionToken(pointer.tokenHash()), next.id().toString(), pointer.timeToLive());
        }
        return true;
    }

    @Override
    public synchronized boolean delete(Session session) {
        String key = RedisKeys.session(session.id());
        expire(key);
        if (writesBeforeDelete > 0 && values.containsKey(key)) {
            writesBeforeDelete--;
            Session other = RedisSessionStore.read(values.get(key)).orElseThrow().seen(clock.instant(), false);
            values.put(key, RedisSessionStore.write(other));
        }
        boolean existed = values.containsKey(key);
        remove(key);
        remove(RedisKeys.sessionToken(session.currentHash()));
        if (session.previousHash() != null) {
            remove(RedisKeys.sessionToken(session.previousHash()));
        }
        Set<String> members = accountSets.get(RedisKeys.accountSessions(session.accountId()));
        if (members != null) {
            members.remove(session.id().toString());
        }
        return existed;
    }

    /** The version of the stored session, or -1 when it is gone or unreadable. */
    private long storedVersion(String key) {
        expire(key);
        return RedisSessionStore.read(values.get(key)).map(Session::version).orElse(-1L);
    }

    private void put(String key, String value, Duration timeToLive) {
        values.put(key, value);
        timesToLive.put(key, timeToLive);
        expiries.put(key, clock.instant().plus(timeToLive));
    }

    private void remove(String key) {
        values.remove(key);
        timesToLive.remove(key);
        expiries.remove(key);
    }

    private void expire(String key) {
        Instant expiry = expiries.get(key);
        if (expiry != null && !clock.instant().isBefore(expiry)) {
            remove(key);
            accountSets.remove(key);
        }
    }
}
