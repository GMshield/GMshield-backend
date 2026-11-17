package io.github.gmshield.materials.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@code material} and {@code material_version} in memory, for tests that run without PostgreSQL. Like Hibernate's
 * {@code @TenantId}, it sees the rows of the {@link OwnerContext}'s owner only and refuses to work without one; like
 * the tables, it keeps the version's (owner, material) reference. A write undoes itself when its transaction rolls
 * back.
 */
public class InMemoryMaterialStore implements MaterialStore {

    public final Map<UUID, MaterialRow> materials = new ConcurrentHashMap<>();
    public final Map<UUID, VersionRow> versions = new ConcurrentHashMap<>();

    @Override
    public synchronized void insertMaterial(MaterialRow row) {
        ownerFor(row.ownerId());
        if (materials.putIfAbsent(row.id(), row) != null) {
            throw new IllegalStateException("unique key taken");
        }
        RollbackUndo.onRollback(() -> materials.remove(row.id()));
    }

    @Override
    public synchronized void insertVersion(VersionRow row) {
        UUID owner = ownerFor(row.ownerId());
        MaterialRow material = materials.get(row.materialId());
        if (versions.containsKey(row.id()) || material == null || !material.ownerId().equals(owner)) {
            throw new IllegalStateException("key or reference refused");
        }
        versions.put(row.id(), row);
        RollbackUndo.onRollback(() -> versions.remove(row.id()));
    }

    @Override
    public Optional<MaterialRow> findMaterial(UUID id) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(materials.get(id)).filter(row -> row.ownerId().equals(owner));
    }

    @Override
    public Optional<VersionRow> findVersion(UUID id) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(versions.get(id)).filter(row -> row.ownerId().equals(owner));
    }

    @Override
    public synchronized boolean changeVersion(UUID id, String state, String format, Long sizeBytes, Instant now) {
        Optional<VersionRow> found = findVersion(id);
        found.ifPresent(before -> {
            ownerFor(before.ownerId());
            versions.put(id, new VersionRow(before.id(), before.ownerId(), before.materialId(), state, format, sizeBytes,
                    before.createdAt(), now));
            RollbackUndo.onRollback(() -> versions.put(id, before));
        });
        return found.isPresent();
    }

    @Override
    public synchronized boolean deleteVersion(UUID id) {
        Optional<VersionRow> found = findVersion(id);
        found.ifPresent(before -> {
            ownerFor(before.ownerId());
            versions.remove(id);
            RollbackUndo.onRollback(() -> versions.put(id, before));
        });
        return found.isPresent();
    }

    @Override
    public synchronized boolean deleteMaterial(UUID id) {
        Optional<MaterialRow> found = findMaterial(id);
        found.ifPresent(before -> {
            ownerFor(before.ownerId());
            if (versions.values().stream().anyMatch(version -> version.materialId().equals(id))) {
                throw new IllegalStateException("a version still names the material");
            }
            materials.remove(id);
            RollbackUndo.onRollback(() -> materials.put(id, before));
        });
        return found.isPresent();
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
