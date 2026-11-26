package io.github.gmshield.materials.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@code index_change}, {@code index_removal} and (Story 4.5 part B) {@code record_removal} in memory, for tests that
 * run without PostgreSQL. Like Hibernate's {@code @TenantId}, it sees the rows of the {@link OwnerContext}'s owner
 * only; like the tables, an index change keeps its (owner, version) reference into the {@link InMemoryMaterialStore}. A
 * write undoes itself when its transaction rolls back.
 */
public class InMemoryIndexStore implements IndexStore {

    public final Map<UUID, IndexChangeRow> changes = new ConcurrentHashMap<>();
    public final Map<UUID, IndexRemovalRow> removals = new ConcurrentHashMap<>();
    public final Map<UUID, RecordRemovalRow> recordRemovals = new ConcurrentHashMap<>();
    /** The jobs whose index change a move refuses, as a failing database would (the retry sweep's isolation). */
    public final Set<UUID> refusedMoves = ConcurrentHashMap.newKeySet();
    /** The jobs whose index removal a move refuses, as a failing database would (the removal retry sweep's isolation). */
    public final Set<UUID> refusedRemovalMoves = ConcurrentHashMap.newKeySet();
    private final InMemoryMaterialStore materials;

    public InMemoryIndexStore(InMemoryMaterialStore materials) {
        this.materials = materials;
    }

    @Override
    public synchronized void insertChange(IndexChangeRow row) {
        UUID owner = ownerFor(row.ownerId());
        VersionRow version = materials.versions.get(row.versionId());
        if (changes.containsKey(row.jobId()) || version == null || !version.ownerId().equals(owner)
                || row.firstOrdinal() < 0 || row.lastOrdinal() < row.firstOrdinal()) {
            throw new IllegalStateException("key, reference or check refused");
        }
        changes.put(row.jobId(), row);
        RollbackUndo.onRollback(() -> changes.remove(row.jobId()));
    }

    @Override
    public Optional<IndexChangeRow> findChange(UUID jobId) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(changes.get(jobId)).filter(row -> row.ownerId().equals(owner));
    }

    @Override
    public List<IndexChangeRow> changes(UUID versionId) {
        UUID owner = OwnerContext.require();
        return changes.values().stream().filter(row -> row.ownerId().equals(owner) && row.versionId().equals(versionId))
                .sorted(Comparator.comparing(IndexChangeRow::records).thenComparingInt(IndexChangeRow::firstOrdinal))
                .toList();
    }

    @Override
    public synchronized boolean recordChangeRecipe(UUID jobId, String recipe) {
        Optional<IndexChangeRow> found = findChange(jobId);
        found.ifPresent(before -> {
            ownerFor(before.ownerId());
            changes.put(jobId, new IndexChangeRow(before.jobId(), before.ownerId(), before.versionId(),
                    before.firstOrdinal(), before.lastOrdinal(), recipe, before.retries(), before.records()));
            RollbackUndo.onRollback(() -> changes.put(jobId, before));
        });
        return found.isPresent();
    }

    @Override
    public List<IndexChangeRow> retryable(Collection<UUID> jobIds) {
        UUID owner = OwnerContext.require();
        return changes.values().stream().filter(row -> row.ownerId().equals(owner) && jobIds.contains(row.jobId())
                        && row.retries() < IndexChangeRow.MAX_RETRIES)
                .sorted(Comparator.comparing(IndexChangeRow::versionId).thenComparing(IndexChangeRow::records)
                        .thenComparingInt(IndexChangeRow::firstOrdinal)).toList();
    }

    @Override
    public synchronized Optional<IndexChangeRow> moveChange(UUID jobId, UUID newJobId) {
        Optional<IndexChangeRow> found = findChange(jobId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        IndexChangeRow before = found.get();
        ownerFor(before.ownerId());
        IndexChangeRow moved = new IndexChangeRow(newJobId, before.ownerId(), before.versionId(), before.firstOrdinal(),
                before.lastOrdinal(), null, before.retries() + 1, before.records());
        if (moved.retries() > IndexChangeRow.MAX_RETRIES || changes.containsKey(newJobId)
                || refusedMoves.contains(jobId)) {
            throw new IllegalStateException("key or check refused");
        }
        changes.remove(jobId);
        changes.put(newJobId, moved);
        RollbackUndo.onRollback(() -> {
            changes.remove(newJobId);
            changes.put(jobId, before);
        });
        return Optional.of(moved);
    }

    @Override
    public synchronized int deleteChanges(UUID versionId) {
        List<IndexChangeRow> found = changes(versionId);
        for (IndexChangeRow row : found) {
            ownerFor(row.ownerId());
            changes.remove(row.jobId());
            RollbackUndo.onRollback(() -> changes.put(row.jobId(), row));
        }
        return found.size();
    }

    @Override
    public synchronized int deleteRecordChanges(UUID versionId) {
        List<IndexChangeRow> found = changes(versionId).stream().filter(IndexChangeRow::records).toList();
        for (IndexChangeRow row : found) {
            ownerFor(row.ownerId());
            changes.remove(row.jobId());
            RollbackUndo.onRollback(() -> changes.put(row.jobId(), row));
        }
        return found.size();
    }

    @Override
    public synchronized void insertRemoval(IndexRemovalRow row) {
        ownerFor(row.ownerId());
        if (removals.putIfAbsent(row.jobId(), row) != null) {
            throw new IllegalStateException("key refused");
        }
        RollbackUndo.onRollback(() -> removals.remove(row.jobId()));
    }

    @Override
    public Optional<IndexRemovalRow> findRemoval(UUID jobId) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(removals.get(jobId)).filter(row -> row.ownerId().equals(owner));
    }

    @Override
    public List<IndexRemovalRow> removals(Collection<UUID> jobIds) {
        UUID owner = OwnerContext.require();
        return removals.values().stream().filter(row -> row.ownerId().equals(owner) && jobIds.contains(row.jobId()))
                .sorted(Comparator.comparing(IndexRemovalRow::jobId)).toList();
    }

    @Override
    public synchronized Optional<IndexRemovalRow> moveRemoval(UUID jobId, UUID newJobId) {
        Optional<IndexRemovalRow> found = findRemoval(jobId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        IndexRemovalRow before = found.get();
        ownerFor(before.ownerId());
        if (removals.containsKey(newJobId) || refusedRemovalMoves.contains(jobId)) {
            throw new IllegalStateException("key refused");
        }
        IndexRemovalRow moved = new IndexRemovalRow(newJobId, before.ownerId(), before.versionId());
        removals.remove(jobId);
        removals.put(newJobId, moved);
        RollbackUndo.onRollback(() -> {
            removals.remove(newJobId);
            removals.put(jobId, before);
        });
        return Optional.of(moved);
    }

    /** The current owner, who must be the row's; a write outside a transaction is refused, as JPA refuses it. */
    private static UUID ownerFor(UUID rowOwner) {
        UUID owner = OwnerContext.require();
        if (!owner.equals(rowOwner)) {
            throw new IllegalStateException("another owner's row");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("no transaction");
        }
        return owner;
    }

    @Override
    public boolean hasRemoval(UUID versionId) {
        UUID owner = OwnerContext.require();
        return removals.values().stream().anyMatch(row -> row.ownerId().equals(owner)
                && row.versionId().equals(versionId));
    }

    @Override
    public synchronized void insertRecordRemoval(RecordRemovalRow row) {
        ownerFor(row.ownerId());
        if (recordRemovals.putIfAbsent(row.jobId(), row) != null) {
            throw new IllegalStateException("key refused");
        }
        RollbackUndo.onRollback(() -> recordRemovals.remove(row.jobId()));
    }

    @Override
    public Optional<RecordRemovalRow> findRecordRemoval(UUID jobId) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(recordRemovals.get(jobId)).filter(row -> row.ownerId().equals(owner));
    }

    @Override
    public List<RecordRemovalRow> recordRemovals(Collection<UUID> jobIds) {
        UUID owner = OwnerContext.require();
        return recordRemovals.values().stream().filter(row -> row.ownerId().equals(owner)
                && jobIds.contains(row.jobId())).sorted(Comparator.comparing(RecordRemovalRow::jobId)).toList();
    }

    @Override
    public synchronized Optional<RecordRemovalRow> moveRecordRemoval(UUID jobId, UUID newJobId) {
        Optional<RecordRemovalRow> found = findRecordRemoval(jobId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RecordRemovalRow before = found.get();
        ownerFor(before.ownerId());
        if (recordRemovals.containsKey(newJobId)) {
            throw new IllegalStateException("key refused");
        }
        RecordRemovalRow moved = new RecordRemovalRow(newJobId, before.ownerId(), before.versionId(),
                before.recordId());
        recordRemovals.remove(jobId);
        recordRemovals.put(newJobId, moved);
        RollbackUndo.onRollback(() -> {
            recordRemovals.remove(newJobId);
            recordRemovals.put(jobId, before);
        });
        return Optional.of(moved);
    }
}
