package io.github.gmshield.knowledge.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code record_type} in memory (Story 4.1), for tests that run without PostgreSQL. Like Hibernate's {@code @TenantId}, it
 * sees the rows of the {@link OwnerContext}'s owner only and refuses to work without one; like the table, it keeps the
 * keys on (owner, system, core) and (owner, system, name fingerprint) and the version, refusing a stale rename, a taken
 * core key or a taken name as {@link KnowledgeConflict}. A write undoes itself when its transaction rolls back.
 */
public class InMemoryKnowledgeStore implements KnowledgeStore {

    /** The core keys in their fixed order, as the table's list orders them. */
    private static final List<String> CORE_ORDER = List.of("rule", "spell", "item", "creature", "character_sheet",
            "encounter", "dungeon", "note");

    public final Map<UUID, RecordTypeRow> recordTypes = new ConcurrentHashMap<>();
    /** Set it to make the next insert fail, as a database failure would. */
    public final AtomicBoolean failNextInsert = new AtomicBoolean();

    @Override
    public synchronized void insertRecordTypes(List<RecordTypeRow> rows) {
        UUID owner = OwnerContext.require();
        if (failNextInsert.getAndSet(false)) {
            throw new IllegalStateException("record type insert failed");
        }
        for (RecordTypeRow row : rows) {
            if (!row.ownerId().equals(owner) || recordTypes.containsKey(row.id())) {
                throw new IllegalStateException("key refused");
            }
            if (row.core() != null && !CORE_ORDER.contains(row.core())) {
                throw new IllegalStateException("check refused");
            }
            if (row.core() != null && ofSystem(row.systemId()).anyMatch(other -> row.core().equals(other.core()))) {
                throw new KnowledgeConflict(KnowledgeConflict.Reason.CORE_TAKEN);
            }
            if (nameTaken(row.systemId(), row.nameFingerprint(), row.id()) || keyTaken(row.systemId(), row.key())) {
                throw new KnowledgeConflict(KnowledgeConflict.Reason.NAME_TAKEN);
            }
            if ((row.core() == null) == (row.proposalId() == null)) {
                throw new IllegalStateException("origin check refused");
            }
        }
        rows.forEach(row -> {
            recordTypes.put(row.id(), row);
            RollbackUndo.onRollback(() -> recordTypes.remove(row.id()));
        });
    }

    @Override
    public List<RecordTypeRow> recordTypes(UUID systemId, int offset, int limit) {
        return ofSystem(systemId).sorted(Comparator.comparing(InMemoryKnowledgeStore::position)
                        .thenComparing(RecordTypeRow::id)).skip(Math.max(offset, 0)).limit(Math.max(limit, 0)).toList();
    }

    @Override
    public long recordTypeCount(UUID systemId) {
        return ofSystem(systemId).count();
    }

    @Override
    public Set<String> coreKeys(UUID systemId) {
        return ofSystem(systemId).map(RecordTypeRow::core).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    @Override
    public Optional<RecordTypeRow> findRecordType(UUID id) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(recordTypes.get(id)).filter(row -> row.ownerId().equals(owner));
    }

    @Override
    public boolean nameTaken(UUID systemId, byte[] nameFingerprint, UUID except) {
        return ofSystem(systemId).anyMatch(row -> !row.id().equals(except)
                && Arrays.equals(row.nameFingerprint(), nameFingerprint));
    }

    @Override
    public synchronized Optional<RecordTypeRow> rename(UUID id, long version, byte[] name, byte[] nameFingerprint) {
        Optional<RecordTypeRow> found = findRecordType(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RecordTypeRow before = found.get();
        if (before.version() != version) {
            throw new KnowledgeConflict(KnowledgeConflict.Reason.STALE_VERSION);
        }
        if (nameTaken(before.systemId(), nameFingerprint, id)) {
            throw new KnowledgeConflict(KnowledgeConflict.Reason.NAME_TAKEN);
        }
        RecordTypeRow after = before.renamed(name, nameFingerprint, version + 1);
        recordTypes.put(id, after);
        RollbackUndo.onRollback(() -> recordTypes.put(id, before));
        return Optional.of(after);
    }

    @Override
    public synchronized boolean delete(UUID id) {
        Optional<RecordTypeRow> found = findRecordType(id);
        found.ifPresent(row -> {
            recordTypes.remove(id);
            RollbackUndo.onRollback(() -> recordTypes.put(id, row));
        });
        return found.isPresent();
    }

    @Override
    public synchronized void deleteOfSystem(UUID systemId) {
        ofSystem(systemId).toList().forEach(row -> {
            recordTypes.remove(row.id());
            RollbackUndo.onRollback(() -> recordTypes.put(row.id(), row));
        });
    }

    @Override
    public void lockOntology(UUID systemId) {
        // One process, synchronized writes: nothing to lock.
        OwnerContext.require();
    }

    @Override
    public boolean keyTaken(UUID systemId, String key) {
        return ofSystem(systemId).anyMatch(row -> row.key().equals(key));
    }

    @Override
    public Set<String> keys(UUID systemId) {
        return ofSystem(systemId).map(RecordTypeRow::key).collect(Collectors.toSet());
    }

    @Override
    public Optional<RecordTypeRow> findByKey(UUID systemId, String key) {
        return ofSystem(systemId).filter(row -> row.key().equals(key)).findFirst();
    }

    @Override
    public List<RecordTypeRow> approvedRecordTypes(UUID systemId) {
        return ofSystem(systemId).filter(row -> row.core() == null).sorted(Comparator.comparing(RecordTypeRow::id))
                .toList();
    }

    /** The current owner's rows of the system. */
    private Stream<RecordTypeRow> ofSystem(UUID systemId) {
        UUID owner = OwnerContext.require();
        return recordTypes.values().stream().filter(row -> row.ownerId().equals(owner) && row.systemId().equals(systemId));
    }

    private static int position(RecordTypeRow row) {
        return row.core() == null ? CORE_ORDER.size() : CORE_ORDER.indexOf(row.core());
    }
}
