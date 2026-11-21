package io.github.gmshield.library.data;

import io.github.gmshield.shared.data.RollbackUndo;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code deletion} in memory (Story 3.1 part B), for tests that run without PostgreSQL: like the table, one row per
 * (owner, kind, target); every read by id and every change names the owner. A write undoes itself when its transaction
 * rolls back.
 */
public class InMemoryDeletionStore implements DeletionStore {

    public final Map<UUID, DeletionRow> rows = new ConcurrentHashMap<>();

    @Override
    public synchronized void insert(DeletionRow row) {
        boolean taken = rows.values().stream().anyMatch(other -> other.ownerId().equals(row.ownerId())
                && other.kind().equals(row.kind()) && other.targetId().equals(row.targetId()));
        if (taken || rows.putIfAbsent(row.id(), row) != null) {
            throw new IllegalStateException("unique key taken");
        }
        RollbackUndo.onRollback(() -> rows.remove(row.id()));
    }

    @Override
    public synchronized boolean insertIfAbsent(DeletionRow row) {
        boolean taken = rows.values().stream().anyMatch(other -> other.ownerId().equals(row.ownerId())
                && other.kind().equals(row.kind()) && other.targetId().equals(row.targetId()));
        if (taken) {
            return false;
        }
        insert(row);
        return true;
    }

    @Override
    public Optional<DeletionRow> find(UUID ownerId, UUID id) {
        return Optional.ofNullable(rows.get(id)).filter(row -> row.ownerId().equals(ownerId));
    }

    @Override
    public Optional<DeletionRow> findTarget(UUID ownerId, String kind, UUID targetId) {
        return rows.values().stream().filter(row -> row.ownerId().equals(ownerId) && row.kind().equals(kind)
                && row.targetId().equals(targetId)).findFirst();
    }

    @Override
    public synchronized boolean postpone(UUID ownerId, UUID id, Instant notBefore) {
        return change(ownerId, id, row -> row.notBefore().isBefore(notBefore), row -> new DeletionRow(row.id(),
                row.ownerId(), row.kind(), row.targetId(), notBefore, row.createdAt(), null));
    }

    @Override
    public synchronized boolean done(UUID ownerId, UUID id, Instant at) {
        return change(ownerId, id, row -> true, row -> new DeletionRow(row.id(), row.ownerId(), row.kind(),
                row.targetId(), row.notBefore(), row.createdAt(), at));
    }

    @Override
    public List<DeletionRow> due(Instant now, int limit) {
        return rows.values().stream().filter(row -> row.doneAt() == null && !row.notBefore().isAfter(now))
                .sorted(Comparator.comparing(DeletionRow::notBefore).thenComparing(DeletionRow::id))
                .limit(Math.max(limit, 0)).toList();
    }

    private boolean change(UUID ownerId, UUID id, java.util.function.Predicate<DeletionRow> when,
                           java.util.function.UnaryOperator<DeletionRow> next) {
        DeletionRow before = rows.get(id);
        if (before == null || !before.ownerId().equals(ownerId) || before.doneAt() != null || !when.test(before)) {
            return false;
        }
        rows.put(id, next.apply(before));
        RollbackUndo.onRollback(() -> rows.put(id, before));
        return true;
    }
}
