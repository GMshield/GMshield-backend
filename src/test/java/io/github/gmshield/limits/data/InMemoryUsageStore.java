package io.github.gmshield.limits.data;

import io.github.gmshield.shared.data.RollbackUndo;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** {@code model_call_usage} in memory, for tests that run without PostgreSQL: one row per call id, undone on rollback. */
public class InMemoryUsageStore implements UsageStore {

    public final Map<java.util.UUID, UsageRow> rows = new ConcurrentHashMap<>();

    @Override
    public boolean insertOnce(UsageRow row) {
        if (rows.putIfAbsent(row.callId(), row) != null) {
            return false;
        }
        RollbackUndo.onRollback(() -> rows.remove(row.callId()));
        return true;
    }
}
