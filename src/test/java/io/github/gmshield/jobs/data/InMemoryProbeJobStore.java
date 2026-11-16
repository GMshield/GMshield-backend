package io.github.gmshield.jobs.data;

import io.github.gmshield.shared.data.RollbackUndo;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** {@code job_probe} in memory; an insert undoes itself when its transaction rolls back. */
public class InMemoryProbeJobStore implements ProbeJobStore {

    public final Map<UUID, ProbeJobRow> rows = new ConcurrentHashMap<>();

    @Override
    public void insert(ProbeJobRow row) {
        rows.put(row.jobId(), row);
        RollbackUndo.onRollback(() -> rows.remove(row.jobId()));
    }

    @Override
    public Optional<ProbeJobRow> find(UUID jobId) {
        return Optional.ofNullable(rows.get(jobId));
    }
}
