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
 * {@code index_change} and {@code index_removal} in memory, for tests that run without PostgreSQL. Like Hibernate's
 * {@code @TenantId}, it sees the rows of the {@link OwnerContext}'s owner only; like the tables, an index change keeps
 * its (owner, version) reference into the {@link InMemoryMaterialStore}. A write undoes itself when its transaction
 * rolls back.
 */
public class InMemoryIndexStore implements IndexStore {

    public final Map<UUID, IndexChangeRow> changes = new ConcurrentHashMap<>();
    public final Map<UUID, IndexRemovalRow> removals = new ConcurrentHashMap<>();
    /** The jobs whose index change a move refuses, as a failing database would (the retry sweep's isolation). */
    public final Set<UUID> refusedMoves = ConcurrentHashMap.newKeySet();
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
                .sorted(Comparator.comparingInt(IndexChangeRow::firstOrdinal)).toList();
    }

    @Override
    public synchronized boolean recordChangeRecipe(UUID jobId, String recipe) {
        Optional<IndexChangeRow> found = findChange(jobId);
        found.ifPresent(before -> {
            ownerFor(before.ownerId());
            changes.put(jobId, new IndexChangeRow(before.jobId(), before.ownerId(), before.versionId(),
                    before.firstOrdinal(), before.lastOrdinal(), recipe, before.retries()));
            RollbackUndo.onRollback(() -> changes.put(jobId, before));
        });
        return found.isPresent();
    }

    @Override
    public List<IndexChangeRow> retryable(Collection<UUID> jobIds) {
        UUID owner = OwnerContext.require();
        return changes.values().stream().filter(row -> row.ownerId().equals(owner) && jobIds.contains(row.jobId())
                        && row.retries() < IndexChangeRow.MAX_RETRIES)
                .sorted(Comparator.comparing(IndexChangeRow::versionId)
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
                before.lastOrdinal(), null, before.retries() + 1);
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
}
