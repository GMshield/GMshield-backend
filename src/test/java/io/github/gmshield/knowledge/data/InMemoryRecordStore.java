package io.github.gmshield.knowledge.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * {@code record} and {@code record_passage} in memory (Story 4.2), for tests that run without PostgreSQL. Like
 * Hibernate's {@code @TenantId}, it sees the rows of the {@link OwnerContext}'s owner only and refuses to work without
 * one; like the table, it keeps the type check and at least one link per record. A write undoes itself when its
 * transaction rolls back. The passage and version keys are PostgreSQL's alone ({@code IngestionIntegrationTest},
 * {@code MaterialDeletionIntegrationTest}).
 */
public class InMemoryRecordStore implements RecordStore {

    /** The type check (V40): a record type key's text, a core key or an approved type's. */
    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_]{0,31}");

    public final Map<UUID, RecordRow> records = new ConcurrentHashMap<>();

    @Override
    public synchronized void insert(List<RecordRow> rows) {
        UUID owner = OwnerContext.require();
        for (RecordRow row : rows) {
            if (!row.ownerId().equals(owner) || records.containsKey(row.id()) || !TYPE.matcher(row.type()).matches()
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

    @Override
    public Optional<RecordRow> find(UUID id) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(records.get(id)).filter(row -> row.ownerId().equals(owner))
                .map(row -> withLinks(row, row.passageIds().stream().sorted().toList()));
    }

    @Override
    public List<RecordRow> ofTypeIn(Collection<UUID> versionIds, String type) {
        UUID owner = OwnerContext.require();
        return records.values().stream().filter(row -> row.ownerId().equals(owner)
                && versionIds.contains(row.versionId()) && row.type().equals(type))
                .map(row -> withLinks(row, List.of())).toList();
    }

    @Override
    public List<UUID> passagesOf(UUID recordId) {
        return find(recordId).map(RecordRow::passageIds).orElse(List.of());
    }

    @Override
    public List<RecordRow> findAll(Collection<UUID> ids) {
        UUID owner = OwnerContext.require();
        return ids.stream().distinct().map(records::get).filter(row -> row != null && row.ownerId().equals(owner))
                .map(row -> withLinks(row, row.passageIds().stream().sorted().toList())).toList();
    }

    @Override
    public List<RecordRow> named(Collection<UUID> versionIds, byte[] nameFingerprint) {
        UUID owner = OwnerContext.require();
        return records.values().stream().filter(row -> row.ownerId().equals(owner)
                && versionIds.contains(row.versionId()) && row.nameFingerprint() != null
                && Arrays.equals(row.nameFingerprint(), nameFingerprint))
                .sorted(Comparator.comparing(RecordRow::id))
                .map(row -> withLinks(row, row.passageIds().stream().sorted().toList())).toList();
    }

    @Override
    public synchronized Optional<RecordRow> correct(UUID id, long version, RecordCorrection correction) {
        UUID owner = OwnerContext.require();
        RecordRow before = records.get(id);
        if (before == null || !before.ownerId().equals(owner)) {
            return Optional.empty();
        }
        if (before.version() != version) {
            throw new KnowledgeConflict(KnowledgeConflict.Reason.STALE_VERSION);
        }
        String keptType = before.corrected() ? before.extractedType() : before.type();
        byte[] keptFingerprint = before.corrected() ? before.extractedNameFingerprint() : before.nameFingerprint();
        RecordRow after = written(before, correction, keptType, keptFingerprint);
        return Optional.of(withLinks(after, after.passageIds().stream().sorted().toList()));
    }

    @Override
    public synchronized boolean carry(UUID id, RecordCorrection correction, String extractedType,
                                      byte[] extractedNameFingerprint) {
        UUID owner = OwnerContext.require();
        RecordRow before = records.get(id);
        if (before == null || !before.ownerId().equals(owner)) {
            return false;
        }
        written(before, correction, extractedType, extractedNameFingerprint);
        return true;
    }

    /** Writes the correction over the row, its version raised by one, as the table's update does. */
    private RecordRow written(RecordRow before, RecordCorrection correction, String keptType, byte[] keptFingerprint) {
        if (!TYPE.matcher(correction.type()).matches() || keptType == null) {
            throw new IllegalStateException("check refused");
        }
        RecordRow after = new RecordRow(before.id(), before.ownerId(), before.versionId(), before.ordinal(),
                before.firstPage(), correction.type(), correction.name(), correction.nameFingerprint(),
                correction.fields(), before.passageIds(), before.createdAt(), before.version() + 1,
                correction.correctedAt(), keptType, keptFingerprint);
        records.put(after.id(), after);
        RollbackUndo.onRollback(() -> records.put(before.id(), before));
        return after;
    }

    /** The row as the JPA store reads it: its links by passage id, or none for a list. */
    private static RecordRow withLinks(RecordRow row, List<UUID> passageIds) {
        return row.withPassages(passageIds);
    }

    private int delete(List<RecordRow> found) {
        for (RecordRow row : found) {
            records.remove(row.id());
            RollbackUndo.onRollback(() -> records.put(row.id(), row));
        }
        return found.size();
    }
}
