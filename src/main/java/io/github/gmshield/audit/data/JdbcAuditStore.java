package io.github.gmshield.audit.data;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code audit_entry} through {@link JdbcClient}: one insert, and nothing else. The client is looked up on
 * first use, so the unit tests, which start without a database, can still create the application.
 */
@Repository
class JdbcAuditStore implements AuditStore {

    private final ObjectProvider<JdbcClient> jdbc;

    JdbcAuditStore(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(AuditRow row) {
        jdbc.getObject().sql("insert into audit_entry (id, at, action, account_id, trace_id, details)"
                        + " values (:id, :at, :action, :account, :trace, cast(:details as jsonb))")
                .param("id", row.id())
                .param("at", OffsetDateTime.ofInstant(row.at(), ZoneOffset.UTC))
                .param("action", row.action())
                .param("account", row.accountId(), java.sql.Types.OTHER)
                .param("trace", row.traceId())
                .param("details", row.details())
                .update();
    }
}
