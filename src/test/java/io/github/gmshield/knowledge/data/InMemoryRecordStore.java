package io.github.gmshield.knowledge.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code record} and {@code record_passage} in memory (Story 4.2), for tests that run without PostgreSQL. Like
 * Hibernate's {@code @TenantId}, it sees the rows of the {@link OwnerContext}'s owner only and refuses to work without
 * one; like the table, it keeps the type check and at least one link per record. A write undoes itself when its
 * transaction rolls back. The passage and version keys are PostgreSQL's alone ({@code IngestionIntegrationTest},
 * {@code MaterialDeletionIntegrationTest}).
 */
public class InMemoryRecordStore implements RecordStore {

    private static final Set<String> TYPES = Set.of("rule", "spell", "item", "creature", "character_sheet", "encounter",
            "dungeon", "note");

    public final Map<UUID, RecordRow> records = new ConcurrentHashMap<>();

    @Override
    public synchronized void insert(List<RecordRow> rows) {
        UUID owner = OwnerContext.require();
        for (RecordRow row : rows) {
            if (!row.ownerId().equals(owner) || records.containsKey(row.id()) || !TYPES.contains(row.type())
                    || row.passageIds().isEmpty() || row.firstPage() < 1 || row.ordinal() < 0) {
                throw new IllegalStateException("key or check refused");
            }
        }
        rows.forEach(row -> {
            records.put(row.id(), row);
            RollbackUndo.onRollback(() -> records.remove(row.id()));
        });
    }

    @Override
    public List<RecordRow> ofVersion(UUID versionId) {
        UUID owner = OwnerContext.require();
        return records.values().stream().filter(row -> row.ownerId().equals(owner) && row.versionId().equals(versionId))
                .sorted(Comparator.comparingInt(RecordRow::firstPage).thenComparingInt(RecordRow::ordinal)
                        .thenComparing(RecordRow::id)).toList();
    }

    @Override
    public synchronized int deleteRange(UUID versionId, int firstPage, int lastPage) {
        return delete(ofVersion(versionId).stream().filter(row -> row.firstPage() >= firstPage
                && row.firstPage() <= lastPage).toList());
    }

    @Override
    public synchronized int deleteOfVersion(UUID versionId) {
        return delete(ofVersion(versionId));
    }

    @Override
    public Map<String, Long> countsByType(Collection<UUID> versionIds) {
        UUID owner = OwnerContext.require();
        Map<String, Long> counts = new TreeMap<>();
        records.values().stream().filter(row -> row.ownerId().equals(owner) && versionIds.contains(row.versionId()))
                .forEach(row -> counts.merge(row.type(), 1L, Long::sum));
        return counts;
    }

    private int delete(List<RecordRow> found) {
        for (RecordRow row : found) {
            records.remove(row.id());
            RollbackUndo.onRollback(() -> records.put(row.id(), row));
        }
        return found.size();
    }
}
