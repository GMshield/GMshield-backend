package io.github.gmshield.artifacts.data;

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

/**
 * {@code artifact_index} and {@code artifact_index_removal} in memory (Story 5.1 part B), for tests that run without
 * PostgreSQL. Like Hibernate's {@code @TenantId}, it sees the rows of the {@link OwnerContext}'s owner only and refuses to
 * work without one. A write undoes itself when its transaction rolls back. The newest versions it reads are those of an
 * {@link InMemoryArtifactVersionStore}. The keys on the record and the version are PostgreSQL's alone.
 */
public class InMemoryArtifactIndexStore implements ArtifactIndexStore {

    /** The index rows by job id. */
    public final Map<UUID, ArtifactIndexRow> rows = new ConcurrentHashMap<>();
    /** The removal rows by job id. */
    public final Map<UUID, ArtifactIndexRemovalRow> removalRows = new ConcurrentHashMap<>();
    private final InMemoryArtifactVersionStore versions;

    public InMemoryArtifactIndexStore(InMemoryArtifactVersionStore versions) {
        this.versions = versions;
    }

    @Override
    public synchronized void insert(ArtifactIndexRow row) {
        UUID owner = OwnerContext.require();
        if (!row.ownerId().equals(owner) || rows.putIfAbsent(row.jobId(), row) != null) {
            throw new IllegalStateException("key refused");
        }
        RollbackUndo.onRollback(() -> rows.remove(row.jobId()));
    }

    @Override
    public Optional<ArtifactIndexRow> find(UUID jobId) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(rows.get(jobId)).filter(row -> row.ownerId().equals(owner));
    }

    @Override
    public List<ArtifactIndexRow> ofVersion(UUID recordId, int number) {
        return owned().filter(row -> row.recordId().equals(recordId) && row.number() == number)
                .sorted(Comparator.comparing(ArtifactIndexRow::createdAt)).toList();
    }

    @Override
    public List<ArtifactIndexRow> ofRecords(Collection<UUID> recordIds) {
        return owned().filter(row -> recordIds.contains(row.recordId()))
                .sorted(Comparator.comparing(ArtifactIndexRow::recordId)).toList();
    }

    @Override
    public synchronized boolean recordRecipe(UUID jobId, String recipe) {
        Optional<ArtifactIndexRow> found = find(jobId);
        found.ifPresent(row -> {
            rows.put(jobId, new ArtifactIndexRow(row.jobId(), row.ownerId(), row.recordId(), row.number(), recipe,
                    row.retries(), row.createdAt()));
            RollbackUndo.onRollback(() -> rows.put(jobId, row));
        });
        return found.isPresent();
    }

    @Override
    public List<ArtifactIndexRow> retryable(Collection<UUID> jobIds) {
        return owned().filter(row -> jobIds.contains(row.jobId()) && row.retries() < ArtifactIndexRow.MAX_RETRIES)
                .sorted(Comparator.comparing(ArtifactIndexRow::recordId)).toList();
    }

    @Override
    public synchronized Optional<ArtifactIndexRow> move(UUID jobId, UUID newJobId) {
        Optional<ArtifactIndexRow> found = find(jobId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ArtifactIndexRow before = found.get();
        ArtifactIndexRow moved = new ArtifactIndexRow(newJobId, before.ownerId(), before.recordId(), before.number(),
                null, before.retries() + 1, before.createdAt());
        rows.remove(jobId);
        rows.put(newJobId, moved);
        RollbackUndo.onRollback(() -> {
            rows.remove(newJobId);
            rows.put(jobId, before);
        });
        return Optional.of(moved);
    }

    @Override
    public synchronized int deleteOfVersion(UUID recordId, int number) {
        return delete(ofVersion(recordId, number));
    }

    @Override
    public synchronized int deleteOf(Collection<UUID> recordIds) {
        return delete(ofRecords(recordIds));
    }

    @Override
    public List<VersionKey> notIndexedUnder(String recipe) {
        UUID owner = OwnerContext.require();
        Map<UUID, Integer> newest = new java.util.HashMap<>();
        versions.rows.values().stream().filter(row -> row.ownerId().equals(owner))
                .forEach(row -> newest.merge(row.recordId(), row.number(), Math::max));
        return newest.entrySet().stream()
                .filter(entry -> ofVersion(entry.getKey(), entry.getValue()).stream()
                        .noneMatch(row -> recipe.equals(row.recipe())))
                .map(entry -> new VersionKey(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(VersionKey::recordId)).toList();
    }

    /** The owners of every owner with a newest version that no row reports under {@code recipe} (StaleArtifacts). */
    public List<UUID> staleOwners(String recipe) {
        Map<UUID, ArtifactVersionRow> newest = new java.util.HashMap<>();
        versions.rows.values().forEach(row -> newest.merge(row.recordId(), row,
                (left, right) -> left.number() >= right.number() ? left : right));
        return newest.values().stream()
                .filter(version -> rows.values().stream().noneMatch(row -> row.recordId().equals(version.recordId())
                        && row.number() == version.number() && recipe.equals(row.recipe())))
                .map(ArtifactVersionRow::ownerId).distinct().sorted().toList();
    }

    @Override
    public synchronized void insertRemoval(ArtifactIndexRemovalRow row) {
        UUID owner = OwnerContext.require();
        if (!row.ownerId().equals(owner) || removalRows.putIfAbsent(row.jobId(), row) != null) {
            throw new IllegalStateException("key refused");
        }
        RollbackUndo.onRollback(() -> removalRows.remove(row.jobId()));
    }

    @Override
    public Optional<ArtifactIndexRemovalRow> findRemoval(UUID jobId) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(removalRows.get(jobId)).filter(row -> row.ownerId().equals(owner));
    }

    @Override
    public List<ArtifactIndexRemovalRow> removals(Collection<UUID> jobIds) {
        UUID owner = OwnerContext.require();
        Set<UUID> wanted = Set.copyOf(jobIds);
        return removalRows.values().stream().filter(row -> row.ownerId().equals(owner) && wanted.contains(row.jobId()))
                .sorted(Comparator.comparing(ArtifactIndexRemovalRow::artifactId)).toList();
    }

    @Override
    public synchronized Optional<ArtifactIndexRemovalRow> moveRemoval(UUID jobId, UUID newJobId) {
        Optional<ArtifactIndexRemovalRow> found = findRemoval(jobId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ArtifactIndexRemovalRow before = found.get();
        ArtifactIndexRemovalRow moved = new ArtifactIndexRemovalRow(newJobId, before.ownerId(), before.artifactId());
        removalRows.remove(jobId);
        removalRows.put(newJobId, moved);
        RollbackUndo.onRollback(() -> {
            removalRows.remove(newJobId);
            removalRows.put(jobId, before);
        });
        return Optional.of(moved);
    }

    private int delete(List<ArtifactIndexRow> found) {
        found.forEach(row -> {
            rows.remove(row.jobId());
            RollbackUndo.onRollback(() -> rows.put(row.jobId(), row));
        });
        return found.size();
    }

    private java.util.stream.Stream<ArtifactIndexRow> owned() {
        UUID owner = OwnerContext.require();
        return rows.values().stream().filter(row -> row.ownerId().equals(owner));
    }
}
