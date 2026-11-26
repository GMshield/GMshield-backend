package io.github.gmshield.knowledge.service;

import io.github.gmshield.knowledge.data.KnowledgeConflict;
import io.github.gmshield.knowledge.data.KnowledgeStore;
import io.github.gmshield.knowledge.data.RecordStore;
import io.github.gmshield.knowledge.data.RecordTypeRow;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.library.service.SystemCreated;
import io.github.gmshield.materials.service.ExtractedRecords;
import io.github.gmshield.materials.service.ReadyVersions;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A system's ontology (Story 4.1): its record types, for the owner of the work ({@link OwnerContext}); the store sees
 * that owner's rows only, so another owner's id reads exactly like an unknown one. A record type's name is encrypted
 * for the owner, bound to the entity {@value #RECORD_TYPE}, the field {@code name} and the row; its fingerprint (of the
 * name's {@link Library#uniqueForm}) is unique per owner and system.
 *
 * <p>Every system has the eight core types ({@link CoreRecordTypes}): {@link #systemCreated} seeds them in the
 * transaction that creates the system, and {@link #fillCore} adds the missing ones of a system from before V34
 * ({@link CoreTypeBackfill}, at start). A read never writes. A system that is not the owner's, unknown or being deleted
 * is "not found", and so are its record types. A core type can be neither renamed nor removed
 * ({@code ontology.core_record_type}, 409, nothing changes); a type that is not core (Story 4.4 adds them) is renamed
 * on the version it was read at ({@code request.stale_version}, 409, when it moved) to a name no other type of the
 * system has ({@code ontology.record_type_name_taken}, 409; {@code ontology.record_type_name_invalid}, 400, outside 1
 * to {@value #NAME_MAX} code points after trimming), and removed while it has no records
 * ({@code ontology.record_type_in_use}, 409; from Story 5.1 also while an artifact of the system's campaigns has its
 * key). From Story 4.2 a record type's record count is the number of records of
 * its core type in the system's {@code ready} versions ({@link ReadyVersions#ofSystem}: the materials attached to the
 * system and those of its campaigns), so a version that needs review counts nowhere; a type that is not core has none
 * until Story 4.4 part B, which counts the records of its key. Logs carry ids, counts and versions only.
 *
 * <p>From Story 4.4 a record type also has its {@code key} (a core type's core key, or the stable slug of an approved
 * type's name, which never changes) and its origin: {@code core}, or the proposal that approved it and that proposal's
 * material ({@link Origin}). A rename checks the system's one name space ({@link NameSpace}: types, terms, aliases and
 * the core names in pt-BR and en) under the system's ontology lock.
 */
@Service
public class Ontology {

    static final String RECORD_TYPE = "record_type";
    static final String FIELD_NAME = "name";
    /** A record type's name, in code points after trimming. */
    public static final int NAME_MAX = 120;
    /** A list page's size: {@value #DEFAULT_LIMIT} unless asked, at most {@value #MAX_LIMIT}. */
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    /** The furthest position a list page may start at. */
    public static final int MAX_OFFSET = 100_000;
    /** The record count of a type whose key no record of the system has. */
    static final long NO_RECORDS = 0;
    private static final Logger log = LoggerFactory.getLogger(Ontology.class);

    private final KnowledgeStore store;
    private final Vault vault;
    private final Library library;
    private final ReadyVersions ready;
    private final ExtractedRecords records;
    private final RecordStore artifacts;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final NameSpace names;
    private final Origins origins;
    private final Clock clock;

    Ontology(KnowledgeStore store, Vault vault, Library library, ReadyVersions ready, ExtractedRecords records,
             RecordStore artifacts, ObjectProvider<PlatformTransactionManager> transactions, NameSpace names,
             Origins origins, ObjectProvider<Clock> clock) {
        this.store = store;
        this.names = names;
        this.origins = origins;
        this.vault = vault;
        this.library = library;
        this.ready = ready;
        this.records = records;
        this.artifacts = artifacts;
        this.transactions = transactions;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /**
     * A record type: its name (for a core type, the stored English name, which the frontend replaces by its own
     * translation of {@code core}), its core key (null when it is not core), its stable key (Story 4.4), its origin (null
     * for a core type, whose origin is {@code core}), how many records it types, and its version.
     */
    public record RecordType(UUID id, String name, String core, String key, Origin origin, long records, long version) {
        @Override
        public String toString() {
            return "RecordType[id=" + id + ", core=" + core + ", key=" + key + ", records=" + records + ", version="
                    + version + "]";
        }
    }

    /**
     * Where an approved record type or term came from (Story 4.4): the approving proposal, its material, and that
     * material's name; the material and its name are null once the material was deleted. {@code toString} names ids.
     */
    public record Origin(UUID proposalId, UUID materialId, String materialName) {
        @Override
        public String toString() {
            return "Origin[proposalId=" + proposalId + ", materialId=" + materialId + "]";
        }
    }

    /** One page of a system's record types and how many it has in all. */
    public record RecordTypePage(List<RecordType> items, long total) {
    }

    /**
     * The new system's core record types (Story 4.1), in the transaction that creates it: they exist with the system or
     * not at all, so a failure here rolls the system back.
     */
    @EventListener
    void systemCreated(SystemCreated created) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("knowledge.transaction_required");
        }
        if (!created.ownerId().equals(OwnerContext.require())) {
            throw new IllegalStateException("knowledge.owner_mismatch");
        }
        int seeded = seed(created.systemId(), Set.of());
        log.atInfo().addKeyValue("ownerId", created.ownerId()).addKeyValue("systemId", created.systemId())
                .addKeyValue("recordTypes", seeded).log("knowledge.core_types_seeded");
    }

    /**
     * Adds the core record types the owner's system {@code systemId} lacks, in one transaction of its own; answers how
     * many it added (0 for a system that has them all, an unknown one, another owner's or one being deleted). Throws
     * when a key refuses the insert (another backend filled the system meanwhile): nothing is added then.
     */
    public int fillCore(UUID systemId) {
        int filled = writing(() -> {
            if (library.system(systemId).isEmpty()) {
                return 0;
            }
            return seed(systemId, store.coreKeys(systemId));
        });
        if (filled > 0) {
            log.atInfo().addKeyValue("ownerId", OwnerContext.require()).addKeyValue("systemId", systemId)
                    .addKeyValue("recordTypes", filled).log("knowledge.core_types_filled");
        }
        return filled;
    }

    /**
     * The record types of the owner's system {@code systemId}: the core types first in their fixed order, then the others
     * oldest first; the {@code offset} first ones skipped (0 to {@value #MAX_OFFSET}), at most {@code limit} (1 to
     * {@value #MAX_LIMIT}), with how many the system has in all. Empty for a system that is not the owner's, unknown or
     * being deleted.
     */
    public Optional<RecordTypePage> recordTypes(UUID systemId, int offset, int limit) {
        checkPage(offset, limit);
        return reading(() -> library.system(systemId).map(system -> {
            Map<String, Long> counts = counts(system.id());
            List<RecordTypeRow> rows = store.recordTypes(system.id(), offset, limit);
            Map<UUID, Origin> from = origins.of(rows.stream().map(RecordTypeRow::proposalId).toList());
            return new RecordTypePage(rows.stream().map(row -> open(row, counts, from)).toList(),
                    store.recordTypeCount(system.id()));
        }));
    }

    /** A list page's limits: {@code offset} 0 to {@value #MAX_OFFSET}, {@code limit} 1 to {@value #MAX_LIMIT}. */
    static void checkPage(int offset, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("knowledge.limit_invalid");
        }
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new IllegalArgumentException("knowledge.offset_invalid");
        }
    }

    /** The owner's record type {@code id}; empty for an unknown id, another owner's or one of a system being deleted. */
    public Optional<RecordType> recordType(UUID id) {
        return reading(() -> visible(id).map(row -> open(row, counts(row.systemId()), origins.of(proposals(row)))));
    }

    /**
     * Renames the owner's record type {@code id} on the version {@code version}, as the class says; answers it as stored,
     * its version raised by one, or empty for an unknown id, another owner's or one of a system being deleted.
     */
    public Optional<RecordType> rename(UUID id, long version, String typedName) {
        Optional<RecordType> renamed = writing(() -> visible(id).map(row -> {
            if (row.core() != null) {
                throw new ApiException(KnowledgeProblems.CORE_RECORD_TYPE);
            }
            if (row.version() != version) {
                throw new ApiException(KnowledgeProblems.STALE_VERSION);
            }
            String name = name(typedName);
            byte[] fingerprint = fingerprint(name);
            // Story 4.4: the one name space of the system, terms, aliases and the core names in both languages included.
            store.lockOntology(row.systemId());
            if (names.taken(row.systemId(), name, id)) {
                throw new ApiException(KnowledgeProblems.RECORD_TYPE_NAME_TAKEN);
            }
            RecordTypeRow stored = conflicts(() -> store.rename(id, version,
                    vault.encryptText(binding(id, row.ownerId()), name), fingerprint))
                    .orElseThrow(() -> new IllegalStateException("knowledge.record_type_vanished"));
            return open(stored, Map.of(), origins.of(proposals(stored)));
        }));
        renamed.ifPresent(type -> log.atInfo().addKeyValue("ownerId", OwnerContext.require()).addKeyValue("recordTypeId", id)
                .addKeyValue("version", type.version()).log("knowledge.record_type_renamed"));
        return renamed;
    }

    /**
     * Removes the owner's record type {@code id}, as the class says; false for an unknown id, another owner's or one of a
     * system being deleted; {@code request.stale_version} (409) when a rename committed meanwhile; and from Story 4.2
     * {@code ontology.record_type_in_use} (409) for a type that types records.
     */
    public boolean delete(UUID id) {
        boolean deleted = writing(() -> visible(id).map(row -> {
            if (row.core() != null) {
                throw new ApiException(KnowledgeProblems.CORE_RECORD_TYPE);
            }
            if (records(row, counts(row.systemId())) > 0 || artifactsOf(row) > 0) {
                throw new ApiException(KnowledgeProblems.RECORD_TYPE_IN_USE);
            }
            return conflicts(() -> store.delete(id));
        }).orElse(false));
        if (deleted) {
            log.atInfo().addKeyValue("ownerId", OwnerContext.require()).addKeyValue("recordTypeId", id)
                    .log("knowledge.record_type_deleted");
        }
        return deleted;
    }

    /**
     * How many artifacts of the system's campaigns have the type's key (Story 5.1): an artifact uses its type as a
     * record does, so a type that one uses is in use.
     */
    private long artifactsOf(RecordTypeRow row) {
        return library.campaignIdsOf(row.systemId())
                .map(campaigns -> artifacts.artifactsOfTypeIn(campaigns, row.key())).orElse(0L);
    }

    /** Inserts the core types of {@code systemId} whose key is not in {@code present}; how many it inserted. */
    private int seed(UUID systemId, Set<String> present) {
        UUID owner = OwnerContext.require();
        Instant now = now();
        List<RecordTypeRow> rows = new ArrayList<>();
        for (String key : CoreRecordTypes.KEYS) {
            if (!present.contains(key)) {
                String name = CoreRecordTypes.name(key);
                UUID id = Ids.newId();
                rows.add(new RecordTypeRow(id, owner, systemId, key, vault.encryptText(binding(id, owner), name),
                        fingerprint(name), 0, now));
            }
        }
        if (!rows.isEmpty()) {
            store.insertRecordTypes(rows);
        }
        return rows.size();
    }

    /** The owner's record type {@code id}, when its system is the owner's and ready. */
    private Optional<RecordTypeRow> visible(UUID id) {
        return store.findRecordType(id).filter(row -> library.system(row.systemId()).isPresent());
    }

    private RecordType open(RecordTypeRow row, Map<String, Long> counts, Map<UUID, Origin> from) {
        return new RecordType(row.id(), vault.decryptText(binding(row.id(), row.ownerId()), row.name()), row.core(),
                row.key(), row.proposalId() == null ? null : from.get(row.proposalId()), records(row, counts),
                row.version());
    }

    /** The approving proposal of a record type, as a list: none for a core type. */
    private static List<UUID> proposals(RecordTypeRow row) {
        return row.proposalId() == null ? List.of() : List.of(row.proposalId());
    }

    /**
     * The records of the system's {@code ready} versions by type key (Story 4.2; an approved type's key from Story 4.4
     * part B): those of the materials attached to it and of its campaigns' materials.
     */
    private Map<String, Long> counts(UUID systemId) {
        return ready.ofSystem(systemId).map(records::countsByType).orElse(Map.of());
    }

    /** How many records a record type types: its key's count (Story 4.4 part B: an approved type's too). */
    private static long records(RecordTypeRow row, Map<String, Long> counts) {
        return counts.getOrDefault(row.key(), NO_RECORDS);
    }

    /** A record type's name, trimmed, or {@code ontology.record_type_name_invalid}. */
    private static String name(String typed) {
        String name = typed == null ? "" : typed.strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > NAME_MAX) {
            throw new ApiException(KnowledgeProblems.RECORD_TYPE_NAME_INVALID);
        }
        return name;
    }

    /** A record type's name fingerprint: of the name's unique form, as a system's and a campaign's are. */
    private byte[] fingerprint(String name) {
        return vault.fingerprint(RECORD_TYPE, FIELD_NAME, Library.uniqueForm(name));
    }

    /** A store write whose conflict becomes its problem: a taken name, or a version that moved meanwhile. */
    private static <T> T conflicts(Supplier<T> write) {
        try {
            return write.get();
        } catch (KnowledgeConflict conflict) {
            throw new ApiException(switch (conflict.reason()) {
                case NAME_TAKEN -> KnowledgeProblems.RECORD_TYPE_NAME_TAKEN;
                case CORE_TAKEN -> KnowledgeProblems.CORE_RECORD_TYPE;
                case STALE_VERSION -> KnowledgeProblems.STALE_VERSION;
            });
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** One read-only transaction for the reads of one answer, so they come from one session. */
    private <T> T reading(Supplier<T> read) {
        OwnerContext.require();
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }

    /** One transaction for a write and its checks: a problem thrown inside rolls everything back. */
    private <T> T writing(Supplier<T> write) {
        OwnerContext.require();
        return new TransactionTemplate(transactions.getObject()).execute(status -> write.get());
    }

    private static Binding binding(UUID rowId, UUID owner) {
        return new Binding(RECORD_TYPE, FIELD_NAME, rowId, owner);
    }
}
