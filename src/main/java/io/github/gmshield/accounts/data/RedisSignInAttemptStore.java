package io.github.gmshield.accounts.data;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Attempts as one JSON string per key ({@code SET ... EX}, {@code GETDEL}): the stage, the expiry and,
 * once identified, the account id and the identity's two vault ciphertexts in Base64. The template is
 * looked up on first use, so the unit tests, which start without Redis, can still create the application.
 */
@Repository
class RedisSignInAttemptStore implements SignInAttemptStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ObjectProvider<StringRedisTemplate> redis;

    RedisSignInAttemptStore(ObjectProvider<StringRedisTemplate> redis) {
        this.redis = redis;
    }

    @Override
    public void save(byte[] tokenHash, SignInAttempt attempt, Duration timeToLive) {
        redis.getObject().opsForValue().set(RedisKeys.signIn(tokenHash), write(attempt), timeToLive);
    }

    @Override
    public Optional<SignInAttempt> take(byte[] tokenHash) {
        return Optional.ofNullable(redis.getObject().opsForValue().getAndDelete(RedisKeys.signIn(tokenHash)))
                .map(RedisSignInAttemptStore::read);
    }

    @Override
    public Optional<SignInAttempt> find(byte[] tokenHash) {
        return Optional.ofNullable(redis.getObject().opsForValue().get(RedisKeys.signIn(tokenHash)))
                .map(RedisSignInAttemptStore::read);
    }

    @Override
    public void delete(byte[] tokenHash) {
        redis.getObject().delete(RedisKeys.signIn(tokenHash));
    }

    static String write(SignInAttempt attempt) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("stage", attempt.stage().name());
        value.put("expiresAt", attempt.expiresAt().getEpochSecond());
        if (attempt.stage() == SignInAttempt.Stage.IDENTIFIED) {
            value.put("accountId", attempt.accountId().toString());
            value.put("googleId", Base64.getEncoder().encodeToString(attempt.googleId()));
            value.put("email", Base64.getEncoder().encodeToString(attempt.email()));
        }
        return JSON.writeValueAsString(value);
    }

    static SignInAttempt read(String stored) {
        JsonNode value = JSON.readTree(stored);
        Instant expiresAt = Instant.ofEpochSecond(value.path("expiresAt").asLong());
        if (SignInAttempt.Stage.IDENTIFIED.name().equals(value.path("stage").asString())) {
            return SignInAttempt.identified(expiresAt, UUID.fromString(value.path("accountId").asString()),
                    Base64.getDecoder().decode(value.path("googleId").asString()),
                    Base64.getDecoder().decode(value.path("email").asString()));
        }
        return SignInAttempt.started(expiresAt);
    }
}
