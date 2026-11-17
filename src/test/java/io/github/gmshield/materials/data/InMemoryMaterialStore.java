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
    /** {@code ingestion_step} (Story 1.15). */
    public final Map<UUID, StepRow> steps = new ConcurrentHashMap<>();
    /** {@code version_file} (Story 1.15). */
    public final Map<UUID, VersionFileRow> files = new ConcurrentHashMap<>();

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
                    before.createdAt(), now, before.pageTotal(), before.pipelineVersion(), before.failureCode()));
            RollbackUndo.onRollback(() -> versions.put(id, before));
        });
        return found.isPresent();
    }

    @Override
    public synchronized boolean changeVersionState(UUID id, String state, Instant now) {
        return replaceVersion(id, before -> new VersionRow(before.id(), before.ownerId(), before.materialId(), state,
                before.format(), before.sizeBytes(), before.createdAt(), now, before.pageTotal(), before.pipelineVersion(),
                before.failureCode()));
    }

    @Override
    public synchronized boolean recordIngestion(UUID id, String pipelineVersion, Integer pageTotal, Instant now) {
        return replaceVersion(id, before -> new VersionRow(before.id(), before.ownerId(), before.materialId(),
                before.state(), before.format(), before.sizeBytes(), before.createdAt(), now,
                pageTotal == null ? before.pageTotal() : pageTotal,
                pipelineVersion == null ? before.pipelineVersion() : pipelineVersion, before.failureCode()));
    }

    @Override
    public synchronized boolean failVersion(UUID id, String code, Instant now) {
        return replaceVersion(id, before -> new VersionRow(before.id(), before.ownerId(), before.materialId(), "failed",
                before.format(), before.sizeBytes(), before.createdAt(), now, before.pageTotal(), before.pipelineVersion(),
                code));
    }

    private boolean replaceVersion(UUID id, java.util.function.UnaryOperator<VersionRow> change) {
        Optional<VersionRow> found = findVersion(id);
        found.ifPresent(before -> {
            ownerFor(before.ownerId());
            versions.put(id, change.apply(before));
            RollbackUndo.onRollback(() -> versions.put(id, before));
        });
        return found.isPresent();
    }

    @Override
    public synchronized void insertStep(StepRow row) {
        UUID owner = ownerFor(row.ownerId());
        VersionRow version = versions.get(row.versionId());
        boolean jobTaken = steps.values().stream().anyMatch(other -> other.ownerId().equals(owner)
                && other.jobId().equals(row.jobId()));
        if (steps.containsKey(row.id()) || jobTaken || version == null || !version.ownerId().equals(owner)) {
            throw new IllegalStateException("key or reference refused");
        }
        steps.put(row.id(), row);
        RollbackUndo.onRollback(() -> steps.remove(row.id()));
    }

    @Override
    public Optional<StepRow> findStepByJob(UUID jobId) {
        UUID owner = OwnerContext.require();
        return steps.values().stream().filter(row -> row.ownerId().equals(owner) && row.jobId().equals(jobId)).findFirst();
    }

    @Override
    public long countSteps(UUID versionId) {
        UUID owner = OwnerContext.require();
        return steps.values().stream().filter(row -> row.ownerId().equals(owner) && row.versionId().equals(versionId))
                .count();
    }

    @Override
    public synchronized void insertVersionFile(VersionFileRow row) {
        UUID owner = ownerFor(row.ownerId());
        VersionRow version = versions.get(row.versionId());
        boolean fileTaken = files.values().stream().anyMatch(other -> other.ownerId().equals(owner)
                && other.fileId().equals(row.fileId()));
        if (files.containsKey(row.id()) || fileTaken || version == null || !version.ownerId().equals(owner)) {
            throw new IllegalStateException("key or reference refused");
        }
        files.put(row.id(), row);
        RollbackUndo.onRollback(() -> files.remove(row.id()));
    }

    @Override
    public List<VersionFileRow> versionFiles(UUID versionId) {
        UUID owner = OwnerContext.require();
        return files.values().stream().filter(row -> row.ownerId().equals(owner) && row.versionId().equals(versionId))
                .sorted(Comparator.comparing(VersionFileRow::role).reversed().thenComparing(VersionFileRow::firstPage)
                        .thenComparing(VersionFileRow::id)).toList();
    }

    @Override
    public synchronized List<VersionFileRow> deleteVersionFiles(UUID versionId) {
        List<VersionFileRow> found = versionFiles(versionId);
        for (VersionFileRow row : found) {
            ownerFor(row.ownerId());
            files.remove(row.id());
            RollbackUndo.onRollback(() -> files.put(row.id(), row));
        }
        return found;
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
