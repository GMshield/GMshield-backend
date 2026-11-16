package io.github.gmshield.jobs.data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * The work streams through small Lua scripts, as {@code accounts.data.RedisSessionStore} does: each one is one
 * command with a typed answer. The template is looked up on first use, so the unit tests, which start without
 * Redis, can still create the application.
 */
@Repository
class RedisJobStream implements JobStream {

    /** KEYS: stream. ARGV: job id. */
    private static final RedisScript<String> ADD = RedisScript.of(
            "return redis.call('XADD', KEYS[1], '*', 'job', ARGV[1])", String.class);

    /** KEYS: stream. ARGV: group. 1 when created, 0 when it existed; any other error is raised. */
    private static final RedisScript<Long> CREATE_GROUP = RedisScript.of("""
            local created = redis.pcall('XGROUP', 'CREATE', KEYS[1], ARGV[1], '0', 'MKSTREAM')
            if type(created) == 'table' and created['err'] then
              if string.find(created['err'], 'BUSYGROUP', 1, true) then return 0 end
              return redis.error_reply(created['err'])
            end
            return 1
            """, Long.class);

    /** KEYS: stream. ARGV: the oldest id kept. */
    private static final RedisScript<Long> TRIM = RedisScript.of(
            "return redis.call('XTRIM', KEYS[1], 'MINID', ARGV[1])", Long.class);

    private final ObjectProvider<StringRedisTemplate> redis;

    RedisJobStream(ObjectProvider<StringRedisTemplate> redis) {
        this.redis = redis;
    }

    @Override
    public String offer(String stream, UUID jobId) {
        return redis.getObject().execute(ADD, List.of(stream), jobId.toString());
    }

    @Override
    public boolean ensureGroup(String stream) {
        Long created = redis.getObject().execute(CREATE_GROUP, List.of(stream), GROUP);
        return created != null && created == 1L;
    }

    @Override
    public long trimBefore(String stream, Instant before) {
        Long removed = redis.getObject().execute(TRIM, List.of(stream), Long.toString(Math.max(0L, before.toEpochMilli())));
        return removed == null ? 0L : removed;
    }
}
