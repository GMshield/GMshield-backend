package io.github.gmshield.artifacts.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * {@code record_version} in memory (Story 5.1), for tests that run without PostgreSQL. Like Hibernate's
 * {@code @TenantId}, it sees the rows of the {@link OwnerContext}'s owner only and refuses to work without one; like the
 * table, its key is the record and the number (a number taken is {@link VersionTaken}, Story 5.2). A write undoes itself when its transaction rolls back. The key on the
 * record is PostgreSQL's alone ({@code ArtifactsIntegrationTest}).
 */
public class InMemoryArtifactVersionStore implements ArtifactVersionStore {

    /** The rows by their key, {@code <record id>/<number>}. */
    public final Map<String, ArtifactVersionRow> rows = new ConcurrentHashMap<>();

    @Override
    public synchronized void insert(ArtifactVersionRow row) {
        UUID owner = OwnerContext.require();
        String key = key(row.recordId(), row.number());
        if (!row.ownerId().equals(owner)) {
            throw new IllegalStateException("key refused");
        }
        if (rows.containsKey(key)) {
            throw new VersionTaken(null);
        }
        rows.put(key, row);
        RollbackUndo.onRollback(() -> rows.remove(key));
    }

    @Override
    public Optional<ArtifactVersionRow> latest(UUID recordId) {
        return Optional.ofNullable(latest(List.of(recordId)).get(recordId));
    }

    @Override
    public Map<UUID, ArtifactVersionRow> latest(Collection<UUID> recordIds) {
        UUID owner = OwnerContext.require();
        Map<UUID, ArtifactVersionRow> found = new HashMap<>();
        rows.values().stream().filter(row -> row.ownerId().equals(owner) && recordIds.contains(row.recordId()))
                .sorted(Comparator.comparingInt(ArtifactVersionRow::number))
                .forEach(row -> found.put(row.recordId(), row));
        return found;
    }

    @Override
    public Optional<ArtifactVersionRow> find(UUID recordId, int number) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(rows.get(key(recordId, number))).filter(row -> row.ownerId().equals(owner));
    }

    @Override
    public Optional<ArtifactVersionRow> lock(UUID recordId, int number) {
        return find(recordId, number);
    }

    @Override
    public List<ArtifactVersionRow> page(UUID recordId, Integer before, int offset, int limit) {
        return of(recordId).filter(row -> before == null || row.number() < before)
                .sorted(Comparator.comparingInt(ArtifactVersionRow::number).reversed()).skip(offset).limit(limit)
                .toList();
    }

    @Override
    public long count(UUID recordId) {
        return of(recordId).count();
    }

    private Stream<ArtifactVersionRow> of(UUID recordId) {
        UUID owner = OwnerContext.require();
        return rows.values().stream().filter(row -> row.ownerId().equals(owner) && row.recordId().equals(recordId));
    }

    @Override
    public synchronized int deleteOf(Collection<UUID> recordIds) {
        UUID owner = OwnerContext.require();
        List<Map.Entry<String, ArtifactVersionRow>> found = rows.entrySet().stream()
                .filter(entry -> entry.getValue().ownerId().equals(owner)
                        && recordIds.contains(entry.getValue().recordId())).toList();
        found.forEach(entry -> {
            rows.remove(entry.getKey());
            RollbackUndo.onRollback(() -> rows.put(entry.getKey(), entry.getValue()));
        });
        return found.size();
    }

    private static String key(UUID recordId, int number) {
        return recordId + "/" + number;
    }
}
