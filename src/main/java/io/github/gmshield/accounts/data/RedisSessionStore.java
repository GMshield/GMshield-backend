package io.github.gmshield.accounts.data;

import java.time.Duration;
import java.time.Instant;
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
 * Sessions as one JSON string per key (account id, seal version, creation time), and a set per account of
 * the hex hashes of its sessions. The set lives as long as its newest session. The template is looked up
 * on first use, so the unit tests, which start without Redis, can still create the application.
 */
@Repository
class RedisSessionStore implements SessionStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ObjectProvider<StringRedisTemplate> redis;

    RedisSessionStore(ObjectProvider<StringRedisTemplate> redis) {
        this.redis = redis;
    }

    @Override
    public void create(byte[] tokenHash, Session session, Duration timeToLive) {
        StringRedisTemplate template = redis.getObject();
        String index = RedisKeys.accountSessions(session.accountId());
        template.opsForValue().set(RedisKeys.session(tokenHash), write(session), timeToLive);
        template.opsForSet().add(index, RedisKeys.hex(tokenHash));
        template.expire(index, timeToLive);
    }

    @Override
    public Optional<Session> find(byte[] tokenHash) {
        return Optional.ofNullable(redis.getObject().opsForValue().get(RedisKeys.session(tokenHash)))
                .map(RedisSessionStore::read);
    }

    @Override
    public void delete(byte[] tokenHash) {
        StringRedisTemplate template = redis.getObject();
        String stored = template.opsForValue().getAndDelete(RedisKeys.session(tokenHash));
        if (stored != null) {
            template.opsForSet().remove(RedisKeys.accountSessions(read(stored).accountId()), RedisKeys.hex(tokenHash));
        }
    }

    static String write(Session session) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("accountId", session.accountId().toString());
        value.put("sealVersion", session.sealVersion());
        value.put("createdAt", session.createdAt().getEpochSecond());
        return JSON.writeValueAsString(value);
    }

    static Session read(String stored) {
        JsonNode value = JSON.readTree(stored);
        return new Session(UUID.fromString(value.path("accountId").asString()), value.path("sealVersion").asInt(),
                Instant.ofEpochSecond(value.path("createdAt").asLong()));
    }
}
