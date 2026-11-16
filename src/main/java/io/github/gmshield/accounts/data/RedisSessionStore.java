package io.github.gmshield.accounts.data;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sessions as one JSON string per session, a pointer per token and a set per account (see
 * {@link SessionStore}). Each write is one small Lua script, so that it happens whole or not at all, and a
 * change checks the stored {@code version} first. Java decides what changes; Redis only does the atomic step.
 * The template is looked up on first use, so the unit tests, which start without Redis, can still create
 * the application.
 */
@Repository
class RedisSessionStore implements SessionStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** KEYS: record, pointer, account set. ARGV: record, its ttl (ms), session id, pointer ttl, set ttl. */
    private static final RedisScript<Long> CREATE = RedisScript.of("""
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            redis.call('SET', KEYS[2], ARGV[3], 'PX', ARGV[4])
            redis.call('SADD', KEYS[3], ARGV[3])
            if redis.call('PTTL', KEYS[3]) < tonumber(ARGV[5]) then
              redis.call('PEXPIRE', KEYS[3], ARGV[5])
            end
            return 1
            """, Long.class);

    /** KEYS: record, then pointers. ARGV: expected version, record, its ttl (ms), session id, then pointer ttls. */
    private static final RedisScript<Long> REPLACE = RedisScript.of("""
            local stored = redis.call('GET', KEYS[1])
            if not stored then return 0 end
            local ok, current = pcall(cjson.decode, stored)
            if not ok or type(current) ~= 'table' or tonumber(current['version']) ~= tonumber(ARGV[1]) then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            for i = 2, #KEYS do
              redis.call('SET', KEYS[i], ARGV[4], 'PX', ARGV[i + 3])
            end
            return 1
            """, Long.class);

    /** KEYS: record, account set, then pointers. ARGV: expected version, session id. */
    private static final RedisScript<Long> DELETE = RedisScript.of("""
            local stored = redis.call('GET', KEYS[1])
            if not stored then return 0 end
            local ok, current = pcall(cjson.decode, stored)
            if not ok or type(current) ~= 'table' or tonumber(current['version']) ~= tonumber(ARGV[1]) then return 0 end
            redis.call('DEL', KEYS[1])
            redis.call('SREM', KEYS[2], ARGV[2])
            for i = 3, #KEYS do
              redis.call('DEL', KEYS[i])
            end
            return 1
            """, Long.class);

    private final ObjectProvider<StringRedisTemplate> redis;

    RedisSessionStore(ObjectProvider<StringRedisTemplate> redis) {
        this.redis = redis;
    }

    @Override
    public void create(Session session, Pointer pointer, Duration timeToLive, Duration accountSetTimeToLive) {
        SessionStore.positive(timeToLive);
        SessionStore.positive(accountSetTimeToLive);
        redis.getObject().execute(CREATE,
                List.of(RedisKeys.session(session.id()), RedisKeys.sessionToken(pointer.tokenHash()),
                        RedisKeys.accountSessions(session.accountId())),
                write(session), millis(timeToLive), session.id().toString(), millis(pointer.timeToLive()),
                millis(accountSetTimeToLive));
    }

    @Override
    public Optional<Session> findByToken(byte[] tokenHash) {
        StringRedisTemplate template = redis.getObject();
        String sessionId = template.opsForValue().get(RedisKeys.sessionToken(tokenHash));
        if (sessionId == null || !UUID_TEXT.matcher(sessionId).matches()) {
            return Optional.empty();
        }
        UUID id = UUID.fromString(sessionId);
        return read(template.opsForValue().get(RedisKeys.session(id))).filter(session -> session.id().equals(id));
    }

    @Override
    public boolean replace(long expectedVersion, Session next, List<Pointer> pointers, Duration timeToLive) {
        SessionStore.positive(timeToLive);
        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>(List.of(Long.toString(expectedVersion), write(next), millis(timeToLive),
                next.id().toString()));
        keys.add(RedisKeys.session(next.id()));
        for (Pointer pointer : pointers) {
            keys.add(RedisKeys.sessionToken(pointer.tokenHash()));
            args.add(millis(pointer.timeToLive()));
        }
        Long done = redis.getObject().execute(REPLACE, keys, args.toArray());
        return done != null && done == 1L;
    }

    @Override
    public boolean delete(Session session) {
        List<String> keys = new ArrayList<>(List.of(RedisKeys.session(session.id()),
                RedisKeys.accountSessions(session.accountId()), RedisKeys.sessionToken(session.currentHash())));
        if (session.previousHash() != null) {
            keys.add(RedisKeys.sessionToken(session.previousHash()));
        }
        Long done = redis.getObject().execute(DELETE, keys, Long.toString(session.version()), session.id().toString());
        return done != null && done == 1L;
    }

    private static String millis(Duration duration) {
        return Long.toString(Math.max(1L, duration.toMillis()));
    }

    static String write(Session session) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", session.id().toString());
        value.put("accountId", session.accountId().toString());
        value.put("sealVersion", session.sealVersion());
        value.put("createdAt", session.createdAt().toEpochMilli());
        value.put("lastSeenAt", session.lastSeenAt().toEpochMilli());
        value.put("version", session.version());
        value.put("currentHash", session.currentHash());
        value.put("currentUsed", session.currentUsed());
        if (session.previousHash() != null) {
            value.put("previousHash", session.previousHash());
            value.put("overlapUntil", session.overlapUntil().toEpochMilli());
        }
        return JSON.writeValueAsString(value);
    }

    /**
     * The session of a stored string; empty for nothing, or for anything this version does not write
     * (a Story 1.9 record, a damaged value): such a session counts as absent, never as an error.
     */
    static Optional<Session> read(String stored) {
        if (stored == null) {
            return Optional.empty();
        }
        try {
            JsonNode value = JSON.readTree(stored);
            for (String field : new String[] {"id", "accountId", "sealVersion", "createdAt", "lastSeenAt", "version",
                    "currentHash", "currentUsed"}) {
                if (!value.has(field)) {
                    return Optional.empty();
                }
            }
            boolean renewed = value.has("previousHash");
            return Optional.of(new Session(
                    UUID.fromString(value.path("id").asString()), UUID.fromString(value.path("accountId").asString()),
                    value.path("sealVersion").asInt(), Instant.ofEpochMilli(value.path("createdAt").asLong()),
                    Instant.ofEpochMilli(value.path("lastSeenAt").asLong()), value.path("version").asLong(),
                    value.path("currentHash").asString(), value.path("currentUsed").asBoolean(),
                    renewed ? value.path("previousHash").asString() : null,
                    renewed ? Instant.ofEpochMilli(value.path("overlapUntil").asLong()) : null));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }
}
