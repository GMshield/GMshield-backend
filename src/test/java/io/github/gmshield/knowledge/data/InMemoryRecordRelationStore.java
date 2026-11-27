package io.github.gmshield.knowledge.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code record_relation} in memory (Story 5.5 part A), for tests that run without PostgreSQL. Like Hibernate's
 * {@code @TenantId}, it sees the rows of the {@link OwnerContext}'s owner only and refuses to work without one; like the
 * table, one record has at most one link of a kind. A write undoes itself when its transaction rolls back. Locks are
 * PostgreSQL's alone, and so is the cascade from either record's row
 * ({@code ArtifactsIntegrationTest#anOverrideIsOneRowAnAnswerReadsItAndItGoesWithTheArtifactsRowAndWithTheRecordsRowOnPostgres}): a link whose
 * record is gone stays here, and every read of the service leaves it out.
 */
public class InMemoryRecordRelationStore implements RecordRelationStore {

    /** The rows by id. */
    public final Map<UUID, RecordRelationRow> rows = new ConcurrentHashMap<>();

    @Override
    public synchronized void insert(RecordRelationRow row) {
        UUID owner = OwnerContext.require();
        if (!row.ownerId().equals(owner) || rows.containsKey(row.id()) || rows.values().stream()
                .anyMatch(other -> other.fromRecordId().equals(row.fromRecordId()) && other.kind().equals(row.kind()))) {
            throw new IllegalStateException("key refused");
        }
        rows.put(row.id(), row);
        RollbackUndo.onRollback(() -> rows.remove(row.id()));
    }

    @Override
    public Optional<RecordRelationRow> from(UUID fromRecordId, String kind) {
        UUID owner = OwnerContext.require();
        return rows.values().stream().filter(row -> row.ownerId().equals(owner)
                && row.fromRecordId().equals(fromRecordId) && row.kind().equals(kind)).findFirst();
    }

    @Override
    public List<RecordRelationRow> fromAll(Collection<UUID> fromRecordIds, String kind) {
        UUID owner = OwnerContext.require();
        return rows.values().stream().filter(row -> row.ownerId().equals(owner)
                && fromRecordIds.contains(row.fromRecordId()) && row.kind().equals(kind)).toList();
    }

    @Override
    public List<RecordRelationRow> to(Collection<UUID> toRecordIds, String kind) {
        UUID owner = OwnerContext.require();
        return rows.values().stream().filter(row -> row.ownerId().equals(owner)
                && toRecordIds.contains(row.toRecordId()) && row.kind().equals(kind)).toList();
    }

    @Override
    public synchronized boolean retarget(UUID id, UUID toRecordId) {
        UUID owner = OwnerContext.require();
        RecordRelationRow found = rows.get(id);
        if (found == null || !found.ownerId().equals(owner)) {
            return false;
        }
        rows.put(id, found.retargeted(toRecordId));
        RollbackUndo.onRollback(() -> rows.put(id, found));
        return true;
    }

    @Override
    public synchronized boolean deleteFrom(UUID fromRecordId, String kind) {
        Optional<RecordRelationRow> found = from(fromRecordId, kind);
        found.ifPresent(row -> {
            rows.remove(row.id());
            RollbackUndo.onRollback(() -> rows.put(row.id(), row));
        });
        return found.isPresent();
    }

    @Override
    public void lockRecords(Collection<UUID> recordIds) {
        OwnerContext.require();
    }
}
