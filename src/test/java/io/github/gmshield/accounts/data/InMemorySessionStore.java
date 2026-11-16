package io.github.gmshield.accounts.data;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Sessions and each account's set in memory, under the Redis key names, for tests that run without Redis. */
public class InMemorySessionStore implements SessionStore {

    /** Key to the JSON string Redis would hold. */
    public final Map<String, String> values = new ConcurrentHashMap<>();
    /** Account set key to its members (hex hashes). */
    public final Map<String, Set<String>> accountSets = new ConcurrentHashMap<>();
    public final Map<String, Duration> timesToLive = new ConcurrentHashMap<>();

    @Override
    public void create(byte[] tokenHash, Session session, Duration timeToLive) {
        values.put(RedisKeys.session(tokenHash), RedisSessionStore.write(session));
        timesToLive.put(RedisKeys.session(tokenHash), timeToLive);
        accountSets.computeIfAbsent(RedisKeys.accountSessions(session.accountId()), key -> ConcurrentHashMap.newKeySet())
                .add(RedisKeys.hex(tokenHash));
    }

    @Override
    public Optional<Session> find(byte[] tokenHash) {
        return Optional.ofNullable(values.get(RedisKeys.session(tokenHash))).map(RedisSessionStore::read);
    }

    @Override
    public void delete(byte[] tokenHash) {
        String stored = values.remove(RedisKeys.session(tokenHash));
        if (stored != null) {
            Set<String> members = accountSets.get(RedisKeys.accountSessions(RedisSessionStore.read(stored).accountId()));
            if (members != null) {
                members.remove(RedisKeys.hex(tokenHash));
            }
        }
    }
}
