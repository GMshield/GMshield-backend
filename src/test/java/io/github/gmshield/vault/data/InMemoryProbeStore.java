package io.github.gmshield.vault.data;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** {@code vault_probe} in memory, for tests that run without PostgreSQL. */
public class InMemoryProbeStore implements ProbeStore {

    public final Map<UUID, ProbeRow> rows = new ConcurrentHashMap<>();

    @Override
    public void insert(ProbeRow row) {
        rows.put(row.id(), row);
    }

    @Override
    public Optional<ProbeRow> find(UUID id) {
        return Optional.ofNullable(rows.get(id));
    }
}
