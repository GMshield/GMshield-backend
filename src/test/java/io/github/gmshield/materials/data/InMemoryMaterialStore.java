package io.github.gmshield.materials.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
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
    public final Map<UUID, MaterialSystemRow> systems = new ConcurrentHashMap<>();

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
    public synchronized void insertMaterialSystem(MaterialSystemRow row) {
        UUID owner = ownerFor(row.ownerId());
        MaterialRow material = materials.get(row.materialId());
        boolean taken = systems.values().stream().anyMatch(other -> other.ownerId().equals(owner)
                && other.materialId().equals(row.materialId()) && other.systemId().equals(row.systemId()));
        if (systems.containsKey(row.id()) || taken || material == null || !material.ownerId().equals(owner)) {
            throw new IllegalStateException("key or reference refused");
        }
        systems.put(row.id(), row);
        RollbackUndo.onRollback(() -> systems.remove(row.id()));
    }

    @Override
    public List<MaterialSystemRow> systemsOf(UUID materialId) {
        UUID owner = OwnerContext.require();
        return systems.values().stream().filter(row -> row.ownerId().equals(owner) && row.materialId().equals(materialId))
                .sorted(Comparator.comparing(MaterialSystemRow::createdAt).thenComparing(MaterialSystemRow::id)).toList();
    }

    @Override
    public List<NewestVersion> newestIn(Collection<String> states, UUID before, int limit) {
        UUID owner = OwnerContext.require();
        return materials.values().stream()
                .filter(row -> row.ownerId().equals(owner) && (before == null || row.id().compareTo(before) < 0))
                .sorted(Comparator.comparing(MaterialRow::id).reversed())
                .map(row -> newestOf(row).orElse(null))
                .filter(found -> found != null && states.contains(found.version().state()))
                .limit(Math.max(limit, 0)).toList();
    }

    @Override
    public Optional<NewestVersion> newest(UUID materialId) {
        return findMaterial(materialId).flatMap(this::newestOf);
    }

    private Optional<NewestVersion> newestOf(MaterialRow material) {
        return versions.values().stream().filter(version -> version.materialId().equals(material.id()))
                .max(Comparator.comparing(VersionRow::createdAt).thenComparing(VersionRow::id))
                .map(version -> new NewestVersion(material, version));
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
            for (MaterialSystemRow link : List.copyOf(systems.values())) {
                if (link.materialId().equals(id)) {
                    systems.remove(link.id());
                    RollbackUndo.onRollback(() -> systems.put(link.id(), link));
                }
            }
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
