package io.github.gmshield.vault.data;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** {@code vault_data_key} in memory, for tests that run without PostgreSQL. */
public class InMemoryDataKeyStore implements DataKeyStore {

    public final Map<String, WrappedDataKey> rows = new ConcurrentHashMap<>();
    public final AtomicInteger reads = new AtomicInteger();
    public final AtomicInteger finds = new AtomicInteger();

    @Override
    public Optional<WrappedDataKey> latest(UUID ownerId) {
        reads.incrementAndGet();
        return rows.values().stream().filter(row -> row.ownerId().equals(ownerId))
                .max(Comparator.comparingInt(WrappedDataKey::version));
    }

    @Override
    public Optional<WrappedDataKey> find(UUID ownerId, int version) {
        reads.incrementAndGet();
        finds.incrementAndGet();
        return Optional.ofNullable(rows.get(ownerId + "/" + version));
    }

    @Override
    public void insertIfAbsent(WrappedDataKey key) {
        rows.putIfAbsent(key.ownerId() + "/" + key.version(), key);
    }

    /** Replaces a stored row as it is, the way a database edit or restore would. */
    public void put(WrappedDataKey key) {
        rows.put(key.ownerId() + "/" + key.version(), key);
    }
}
