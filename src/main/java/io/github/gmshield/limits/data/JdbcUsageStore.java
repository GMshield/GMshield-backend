package io.github.gmshield.limits.data;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code model_call_usage} through {@link JdbcClient} on the application's data source, so a write takes part in the
 * caller's transaction (the one that stores the event batch). The client is looked up on first use, so the unit tests,
 * which start without a database, can still create the application.
 */
@Repository
class JdbcUsageStore implements UsageStore {

    private final ObjectProvider<JdbcClient> jdbc;

    JdbcUsageStore(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertOnce(UsageRow row) {
        return jdbc.getObject().sql("""
                        insert into model_call_usage (call_id, owner_id, job_id, provider, model, tier, response_id,
                            input_tokens, cache_read, cache_write, output_tokens, reasoning, estimated, created_at)
                        values (:callId, :ownerId, :jobId, :provider, :model, :tier, :responseId, :input, :cacheRead,
                            :cacheWrite, :output, :reasoning, :estimated, :createdAt)
                        on conflict (call_id) do nothing""")
                .param("callId", row.callId())
                .param("ownerId", row.ownerId())
                .param("jobId", row.jobId())
                .param("provider", row.provider())
                .param("model", row.model())
                .param("tier", row.tier())
                .param("responseId", row.responseId())
                .param("input", row.input())
                .param("cacheRead", row.cacheRead())
                .param("cacheWrite", row.cacheWrite())
                .param("output", row.output())
                .param("reasoning", row.reasoning())
                .param("estimated", row.estimated())
                .param("createdAt", OffsetDateTime.ofInstant(row.createdAt(), ZoneOffset.UTC))
                .update() == 1;
    }
}
