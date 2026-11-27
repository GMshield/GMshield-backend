package io.github.gmshield.knowledge.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * {@code record} and {@code record_passage} in memory (Story 4.2), for tests that run without PostgreSQL. Like
 * Hibernate's {@code @TenantId}, it sees the rows of the {@link OwnerContext}'s owner only and refuses to work without
 * one; like the table, it keeps the type check and at least one link per live record (a tombstone a carry-over copies
 * has none, Story 4.5 part B), and like the JPA store it leaves deleted records out of every read that does. A write
 * undoes itself when its transaction rolls back. The passage and version keys are PostgreSQL's alone
 * ({@code IngestionIntegrationTest}, {@code MaterialDeletionIntegrationTest}).
 *
 * <p>Story 5.1: an artifact's identity is a row of the same table on PostgreSQL; here it is kept apart, in
 * {@link #artifacts}, so no read of an extracted record can see one, as no JPA read does (the integration tests check
 * that against the table). The campaign key is PostgreSQL's alone. From Story 5.2 part B an artifact the GM deleted is
 * kept in {@link #deletingArtifacts} until its row goes, and every artifact read leaves it out unless its
 * {@link Reach} is {@link Reach#ALL}, as the JPA store does.
 */
public class InMemoryRecordStore implements RecordStore {

    /** The type check (V40): a record type key's text, a core key or an approved type's. */
    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_]{0,31}");

    public final Map<UUID, RecordRow> records = new ConcurrentHashMap<>();
    /** The artifacts' identity rows (Story 5.1), by id. */
    public final Map<UUID, ArtifactRow> artifacts = new ConcurrentHashMap<>();
    /** The ids of the artifacts the GM deleted whose rows are still here (Story 5.2 part B). */
    public final Set<UUID> deletingArtifacts = ConcurrentHashMap.newKeySet();

    @Override
    public synchronized void insert(List<RecordRow> rows) {
        UUID owner = OwnerContext.require();
        for (RecordRow row : rows) {
            if (!row.ownerId().equals(owner) || records.containsKey(row.id()) || artifacts.containsKey(row.id())
                    || !TYPE.matcher(row.type()).matches()
                    || (row.passageIds().isEmpty() && !row.deleted()) || row.firstPage() < 1 || row.ordinal() < 0
                    || (row.pointRemovedAt() != null && !row.deleted())) {
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
        return allOfVersion(versionId).stream().filter(row -> !row.deleted()).toList();
    }

    @Override
    public List<RecordRow> tombstones(UUID versionId) {
        return allOfVersion(versionId).stream().filter(RecordRow::deleted)
                .map(row -> withLinks(row, row.passageIds().stream().sorted().toList())).toList();
    }

    private List<RecordRow> allOfVersion(UUID versionId) {
        UUID owner = OwnerContext.require();
        return records.values().stream().filter(row -> row.ownerId().equals(owner) && row.versionId().equals(versionId))
                .sorted(Comparator.comparingInt(RecordRow::firstPage).thenComparingInt(RecordRow::ordinal)
                        .thenComparing(RecordRow::id)).toList();
    }

    @Override
    public synchronized int deleteRange(UUID versionId, int firstPage, int lastPage) {
        return delete(allOfVersion(versionId).stream().filter(row -> row.firstPage() >= firstPage
                && row.firstPage() <= lastPage).toList());
    }

    @Override
    public synchronized int deleteOfVersion(UUID versionId) {
        return delete(allOfVersion(versionId));
    }

    @Override
    public Map<String, Long> countsByType(Collection<UUID> versionIds) {
        UUID owner = OwnerContext.require();
        Map<String, Long> counts = new TreeMap<>();
        records.values().stream().filter(row -> row.ownerId().equals(owner) && versionIds.contains(row.versionId())
                && !row.deleted()).forEach(row -> counts.merge(row.type(), 1L, Long::sum));
        return counts;
    }

    @Override
    public Optional<UUID> versionOf(UUID id) {
        return find(id).map(RecordRow::versionId);
    }

    @Override
    public Optional<RecordRow> find(UUID id) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(records.get(id)).filter(row -> row.ownerId().equals(owner) && !row.deleted())
                .map(row -> withLinks(row, row.passageIds().stream().sorted().toList()));
    }

    @Override
    public List<RecordRow> ofTypeIn(Collection<UUID> versionIds, String type) {
        UUID owner = OwnerContext.require();
        return records.values().stream().filter(row -> row.ownerId().equals(owner)
                && versionIds.contains(row.versionId()) && row.type().equals(type) && !row.deleted())
                .map(row -> withLinks(row, List.of())).toList();
    }

    @Override
    public List<UUID> passagesOf(UUID recordId) {
        return find(recordId).map(RecordRow::passageIds).orElse(List.of());
    }

    @Override
    public List<RecordRow> findAll(Collection<UUID> ids, Reach reach) {
        UUID owner = OwnerContext.require();
        return ids.stream().distinct().map(records::get).filter(row -> row != null && row.ownerId().equals(owner)
                        && switch (reach) {
                            case LIVE -> !row.deleted();
                            case ALL -> true;
                        })
                .map(row -> withLinks(row, row.passageIds().stream().sorted().toList())).toList();
    }

    @Override
    public List<RecordRow> named(Collection<UUID> versionIds, byte[] nameFingerprint) {
        UUID owner = OwnerContext.require();
        return records.values().stream().filter(row -> row.ownerId().equals(owner)
                && versionIds.contains(row.versionId()) && !row.deleted() && row.nameFingerprint() != null
                && Arrays.equals(row.nameFingerprint(), nameFingerprint))
                .sorted(Comparator.comparing(RecordRow::id))
                .map(row -> withLinks(row, row.passageIds().stream().sorted().toList())).toList();
    }

    @Override
    public List<RecordRow> namedAny(Collection<UUID> versionIds, Collection<String> recordTypes,
                                    Collection<byte[]> nameFingerprints, int limit) {
        UUID owner = OwnerContext.require();
        return records.values().stream().filter(row -> row.ownerId().equals(owner)
                        && versionIds.contains(row.versionId()) && recordTypes.contains(row.type()) && !row.deleted()
                        && row.nameFingerprint() != null
                        && nameFingerprints.stream().anyMatch(one -> Arrays.equals(one, row.nameFingerprint())))
                .sorted(Comparator.comparing(RecordRow::id)).limit(Math.max(limit, 0))
                .map(row -> withLinks(row, row.passageIds().stream().sorted().toList())).toList();
    }

    @Override
    public synchronized Optional<RecordRow> correct(UUID id, long version, RecordCorrection correction) {
        UUID owner = OwnerContext.require();
        RecordRow before = records.get(id);
        if (before == null || !before.ownerId().equals(owner) || before.deleted()) {
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

    @Override
    public synchronized boolean delete(UUID id, Instant at, boolean pointRemoved) {
        UUID owner = OwnerContext.require();
        RecordRow before = records.get(id);
        if (before == null || !before.ownerId().equals(owner) || before.deleted()) {
            return false;
        }
        replace(before, at, pointRemoved ? at : null);
        return true;
    }

    @Override
    public synchronized int pointsRemoved(Collection<UUID> ids, Instant at) {
        UUID owner = OwnerContext.require();
        int marked = 0;
        for (UUID id : Set.copyOf(ids)) {
            RecordRow before = records.get(id);
            if (before != null && before.ownerId().equals(owner) && before.deleted()
                    && before.pointRemovedAt() == null) {
                replace(before, before.deletedAt(), at);
                marked++;
            }
        }
        return marked;
    }

    /** The row with these delete times, its version raised by one, as the table's update does. */
    private void replace(RecordRow before, Instant deletedAt, Instant pointRemovedAt) {
        RecordRow after = new RecordRow(before.id(), before.ownerId(), before.versionId(), before.ordinal(),
                before.firstPage(), before.type(), before.name(), before.nameFingerprint(), before.fields(),
                before.passageIds(), before.createdAt(), before.version() + 1, before.correctedAt(),
                before.extractedType(), before.extractedNameFingerprint(), deletedAt, pointRemovedAt);
        records.put(after.id(), after);
        RollbackUndo.onRollback(() -> records.put(before.id(), before));
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

    @Override
    public synchronized void insertArtifact(ArtifactRow row) {
        UUID owner = OwnerContext.require();
        if (!row.ownerId().equals(owner) || records.containsKey(row.id()) || artifacts.containsKey(row.id())
                || !TYPE.matcher(row.type()).matches()) {
            throw new IllegalStateException("key or check refused");
        }
        artifacts.put(row.id(), row);
        RollbackUndo.onRollback(() -> artifacts.remove(row.id()));
    }

    @Override
    public Optional<ArtifactRow> findArtifact(UUID campaignId, UUID id) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(artifacts.get(id))
                .filter(row -> row.ownerId().equals(owner) && row.campaignId().equals(campaignId) && live(row));
    }

    @Override
    public synchronized Optional<ArtifactRow> retypeArtifact(UUID id, String type) {
        UUID owner = OwnerContext.require();
        ArtifactRow row = artifacts.get(id);
        if (row == null || !row.ownerId().equals(owner) || !live(row)) {
            return Optional.empty();
        }
        if (!TYPE.matcher(type).matches()) {
            throw new IllegalStateException("check refused");
        }
        ArtifactRow changed = new ArtifactRow(row.id(), row.ownerId(), row.campaignId(), type, row.createdAt(),
                row.conversationId());
        artifacts.put(id, changed);
        RollbackUndo.onRollback(() -> artifacts.put(id, row));
        return Optional.of(changed);
    }

    @Override
    public synchronized boolean markArtifactDeleting(UUID id, Instant at) {
        UUID owner = OwnerContext.require();
        ArtifactRow row = artifacts.get(id);
        if (row == null || !row.ownerId().equals(owner) || !live(row)) {
            return false;
        }
        deletingArtifacts.add(id);
        RollbackUndo.onRollback(() -> deletingArtifacts.remove(id));
        return true;
    }

    @Override
    public List<ArtifactRow> findArtifacts(Collection<UUID> ids, Reach reach) {
        UUID owner = OwnerContext.require();
        return ids.stream().distinct().map(artifacts::get)
                .filter(row -> row != null && row.ownerId().equals(owner) && (reach == Reach.ALL || live(row)))
                .sorted(Comparator.comparing(ArtifactRow::id)).toList();
    }

    @Override
    public List<ArtifactRow> artifacts(UUID campaignId, Collection<UUID> shared, String type, UUID before, int offset,
                                       int limit) {
        return ofCampaign(campaignId, shared, type).filter(row -> before == null || unsigned(row.id(), before) < 0)
                .sorted(Comparator.comparing(ArtifactRow::id, InMemoryRecordStore::unsigned).reversed())
                .skip(offset).limit(limit).toList();
    }

    @Override
    public List<ArtifactRow> artifactsOfConversation(UUID conversationId) {
        UUID owner = OwnerContext.require();
        return artifacts.values().stream().filter(row -> row.ownerId().equals(owner)
                && conversationId.equals(row.conversationId()) && live(row))
                .sorted(Comparator.comparing(ArtifactRow::createdAt).thenComparing(ArtifactRow::id,
                        InMemoryRecordStore::unsigned)).toList();
    }

    @Override
    public long artifactCount(UUID campaignId, Collection<UUID> shared, String type) {
        return ofCampaign(campaignId, shared, type).count();
    }

    @Override
    public long artifactsIn(Collection<UUID> campaignIds) {
        UUID owner = OwnerContext.require();
        return artifacts.values().stream().filter(row -> row.ownerId().equals(owner)
                && campaignIds.contains(row.campaignId()) && live(row)).count();
    }

    @Override
    public long artifactsOfTypeIn(Collection<UUID> campaignIds, String type) {
        UUID owner = OwnerContext.require();
        return artifacts.values().stream().filter(row -> row.ownerId().equals(owner)
                && campaignIds.contains(row.campaignId()) && row.type().equals(type) && live(row)).count();
    }

    @Override
    public Map<String, Long> artifactTypes(UUID campaignId, Collection<UUID> shared) {
        Map<String, Long> counts = new TreeMap<>();
        ofCampaign(campaignId, shared, null).forEach(row -> counts.merge(row.type(), 1L, Long::sum));
        return counts;
    }

    @Override
    public List<UUID> artifactIdsOf(Collection<UUID> campaignIds, Reach reach) {
        UUID owner = OwnerContext.require();
        return artifacts.values().stream().filter(row -> row.ownerId().equals(owner)
                && campaignIds.contains(row.campaignId()) && (reach == Reach.ALL || live(row))).map(ArtifactRow::id)
                .sorted().toList();
    }

    @Override
    public synchronized int deleteArtifacts(Collection<UUID> ids) {
        UUID owner = OwnerContext.require();
        int deleted = 0;
        for (UUID id : Set.copyOf(ids)) {
            ArtifactRow row = artifacts.get(id);
            if (row != null && row.ownerId().equals(owner)) {
                artifacts.remove(id);
                boolean deleting = deletingArtifacts.remove(id);
                RollbackUndo.onRollback(() -> {
                    artifacts.put(row.id(), row);
                    if (deleting) {
                        deletingArtifacts.add(row.id());
                    }
                });
                deleted++;
            }
        }
        return deleted;
    }

    /** The live artifacts of the campaign, and (Story 5.6 part B) those with an id in {@code shared}, of the type. */
    private java.util.stream.Stream<ArtifactRow> ofCampaign(UUID campaignId, Collection<UUID> shared, String type) {
        UUID owner = OwnerContext.require();
        return artifacts.values().stream().filter(row -> row.ownerId().equals(owner)
                && (row.campaignId().equals(campaignId) || shared.contains(row.id()))
                && (type == null || row.type().equals(type)) && live(row));
    }

    /** Whether the artifact is not deleting (Story 5.2 part B). */
    private boolean live(ArtifactRow row) {
        return !deletingArtifacts.contains(row.id());
    }

    /** UUIDs in PostgreSQL's order: their bytes as unsigned numbers, so a UUIDv7's time orders it. */
    private static int unsigned(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }
}
