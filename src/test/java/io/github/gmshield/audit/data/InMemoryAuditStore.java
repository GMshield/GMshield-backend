package io.github.gmshield.audit.data;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** {@code audit_entry} in memory, for tests that run without PostgreSQL; it can be told to fail. */
public class InMemoryAuditStore implements AuditStore {

    public final List<AuditRow> rows = new CopyOnWriteArrayList<>();
    /** When true, every append fails the way a lost database connection would. */
    public volatile boolean failing;

    @Override
    public void append(AuditRow row) {
        if (failing) {
            throw new IllegalStateException("the audit store is unavailable");
        }
        rows.add(row);
    }
}
