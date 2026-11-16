package io.github.gmshield.accounts.data;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Sign-in attempts in memory, under the Redis key names, for tests that run without Redis. */
public class InMemorySignInAttemptStore implements SignInAttemptStore {

    /** Key to the JSON string Redis would hold. */
    public final Map<String, String> values = new ConcurrentHashMap<>();
    public final Map<String, Duration> timesToLive = new ConcurrentHashMap<>();

    @Override
    public void save(byte[] tokenHash, SignInAttempt attempt, Duration timeToLive) {
        values.put(RedisKeys.signIn(tokenHash), RedisSignInAttemptStore.write(attempt));
        timesToLive.put(RedisKeys.signIn(tokenHash), timeToLive);
    }

    @Override
    public Optional<SignInAttempt> take(byte[] tokenHash) {
        return Optional.ofNullable(values.remove(RedisKeys.signIn(tokenHash))).map(RedisSignInAttemptStore::read);
    }

    @Override
    public Optional<SignInAttempt> find(byte[] tokenHash) {
        return Optional.ofNullable(values.get(RedisKeys.signIn(tokenHash))).map(RedisSignInAttemptStore::read);
    }

    @Override
    public void delete(byte[] tokenHash) {
        values.remove(RedisKeys.signIn(tokenHash));
    }
}
