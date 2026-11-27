package io.github.gmshield.knowledge.service;

import io.github.gmshield.knowledge.data.RecordCorrection;
import io.github.gmshield.knowledge.data.RecordRelationRow;
import io.github.gmshield.knowledge.data.RecordRelationStore;
import io.github.gmshield.knowledge.data.RecordRow;
import io.github.gmshield.knowledge.data.RecordStore;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.service.ExtractedRecords;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The records a version's ingestion extracts (Story 4.2), stored for the {@code materials} module
 * ({@link ExtractedRecords}), as the owner of the work and in the caller's transaction. Each record gets its id here; its
 * name is encrypted bound to the entity {@value #RECORD}, the field {@value #FIELD_NAME} and the row, and its fields (a
 * JSON array of {@code {name, value}} in order) bound to the field {@value #FIELD_FIELDS}. Its core type key, ordinal
 * and first page stay plain, so the counts by type need no decryption. {@link #readRange} decrypts them for the input of
 * an index job only (Story 4.2 part B). From Story 4.3 part B each record also stores its name's keyed fingerprint
 * (entity {@value #RECORD}, field {@value #FIELD_NAME}, of the name's {@link Library#uniqueForm}), which
 * {@link #named} compares with a search's query; {@link #names} decrypts names for a search's answer only. From Story
 * 4.3 part C {@link #read} decrypts names and fields for an answer (the records handed to the model, and those it
 * cited, stored by value with the turn). From Story 4.5 {@link #carryCorrections} carries a replaced version's
 * corrections over to the version reprocessing made from it, each value encrypted again bound to its new row. From Story
 * 4.5 part B a deleted record (a tombstone) is read only by {@link #located(Collection, Reach)} and
 * {@link #read(Collection, Reach)} with {@link Reach#ALL}, and by {@link #deleted}; {@link #pointsRemoved}
 * marks its point gone, and {@link #carryCorrections} carries tombstones over too, never reading their name or fields.
 * From Story 5.5 part A {@link #carryCorrections} also moves the overrides of the old version's records to the records
 * of the new version they match ({@link RecordRelations#carry}), and from part B the relations too. Nothing here logs a
 * name or a field.
 */
@Service
public class ExtractedRecordStore implements ExtractedRecords {

    static final String RECORD = "record";
    static final String FIELD_NAME = "name";
    static final String FIELD_FIELDS = "fields";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RecordStore store;
    private final RecordRelationStore relations;
    private final Vault vault;
    private final Clock clock;

    ExtractedRecordStore(RecordStore store, RecordRelationStore relations, Vault vault, ObjectProvider<Clock> clock) {
        this.store = store;
        this.relations = relations;
        this.vault = vault;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    @Override
    public int replaceRange(UUID versionId, int firstPage, int lastPage, List<Extracted> records) {
        UUID owner = requireTransaction();
        int removed = store.deleteRange(versionId, firstPage, lastPage);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<RecordRow> rows = new ArrayList<>(records.size());
        for (int ordinal = 0; ordinal < records.size(); ordinal++) {
            Extracted record = records.get(ordinal);
            if (record.firstPage() < firstPage || record.firstPage() > lastPage) {
                throw new IllegalArgumentException("knowledge.record_outside_range");
            }
            UUID id = Ids.newId();
            rows.add(new RecordRow(id, owner, versionId, ordinal, record.firstPage(), record.type(),
                    vault.encryptText(name(id, owner), record.name()), nameFingerprint(record.name()),
                    vault.encrypt(fields(id, owner), fieldsJson(record.fields())), record.passageIds(), now));
        }
        store.insert(rows);
        return removed;
    }

    @Override
    public int removeVersion(UUID versionId) {
        requireTransaction();
        return store.deleteOfVersion(versionId);
    }

    @Override
    public Map<String, Long> countsByType(Collection<UUID> versionIds) {
        OwnerContext.require();
        return store.countsByType(versionIds);
    }

    @Override
    public List<Integer> firstPages(UUID versionId) {
        OwnerContext.require();
        return store.ofVersion(versionId).stream().map(RecordRow::firstPage).toList();
    }

    @Override
    public List<Stored> readRange(UUID versionId, int firstPage, int lastPage) {
        UUID owner = OwnerContext.require();
        List<Stored> found = new ArrayList<>();
        for (RecordRow row : store.ofVersion(versionId)) {
            if (row.firstPage() >= firstPage && row.firstPage() <= lastPage) {
                found.add(new Stored(row.id(), row.type(), row.firstPage(),
                        vault.decryptText(name(row.id(), owner), row.name()),
                        fieldsOf(vault.decrypt(fields(row.id(), owner), row.fields()))));
            }
        }
        return List.copyOf(found);
    }

    @Override
    public List<Located> located(Collection<UUID> ids, Reach reach) {
        OwnerContext.require();
        List<UUID> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return List.of();
        }
        Map<UUID, RecordRow> found = new HashMap<>();
        store.findAll(wanted, reachOf(reach)).forEach(row -> found.put(row.id(), row));
        return wanted.stream().filter(found::containsKey).map(id -> located(found.get(id))).toList();
    }

    @Override
    public Set<UUID> deleted(Collection<UUID> ids) {
        OwnerContext.require();
        List<UUID> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return Set.of();
        }
        return store.findAll(wanted, RecordStore.Reach.ALL).stream().filter(RecordRow::deleted).map(RecordRow::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public int pointsRemoved(Collection<UUID> ids) {
        requireTransaction();
        List<UUID> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        return wanted.isEmpty() ? 0 : store.pointsRemoved(wanted, clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    @Override
    public List<Located> named(Collection<UUID> versionIds, String name) {
        OwnerContext.require();
        if (versionIds.isEmpty() || name == null || name.isBlank()) {
            return List.of();
        }
        return store.named(Set.copyOf(versionIds), nameFingerprint(name)).stream()
                .map(ExtractedRecordStore::located).toList();
    }

    @Override
    public Map<UUID, String> names(Collection<UUID> ids) {
        UUID owner = OwnerContext.require();
        List<UUID> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> found = new HashMap<>();
        if (!wanted.isEmpty()) {
            store.findAll(wanted, RecordStore.Reach.LIVE).forEach(row -> found.put(row.id(),
                    vault.decryptText(name(row.id(), owner), row.name())));
        }
        return Map.copyOf(found);
    }

    @Override
    public List<Content> read(Collection<UUID> ids, Reach reach) {
        UUID owner = OwnerContext.require();
        List<UUID> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return List.of();
        }
        Map<UUID, RecordRow> found = new HashMap<>();
        store.findAll(wanted, reachOf(reach)).forEach(row -> found.put(row.id(), row));
        return wanted.stream().filter(found::containsKey).map(found::get).map(row -> new Content(row.id(),
                row.versionId(), row.type(), row.firstPage(), vault.decryptText(name(row.id(), owner), row.name()),
                fieldsOf(vault.decrypt(fields(row.id(), owner), row.fields())))).toList();
    }

    @Override
    public Carried carryCorrections(UUID fromVersionId, UUID toVersionId,
                                    Function<List<UUID>, Optional<Remapped>> remap) {
        UUID owner = requireTransaction();
        List<RecordRow> corrections = store.ofVersion(fromVersionId).stream().filter(RecordRow::corrected).toList();
        List<RecordRow> tombstones = store.tombstones(fromVersionId);
        if (corrections.isEmpty() && tombstones.isEmpty()) {
            return new Carried(0, 0, 0, 0, 0, carryLinks(fromVersionId, toVersionId, RecordRelationRow.OVERRIDES),
                    carryLinks(fromVersionId, toVersionId, RecordRelationRow.RELATED));
        }
        List<RecordRow> newRecords = store.ofVersion(toVersionId);
        List<RecordRow> fresh = new ArrayList<>(newRecords.stream().filter(row -> !row.corrected()).toList());
        int nextOrdinal = Stream.concat(newRecords.stream(), store.tombstones(toVersionId).stream())
                .mapToInt(RecordRow::ordinal).max().orElse(-1) + 1;
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int matched = 0;
        int dropped = 0;
        List<RecordRow> copies = new ArrayList<>();
        for (RecordRow correction : corrections) {
            Optional<RecordRow> match = match(correction, fresh);
            if (match.isPresent()) {
                RecordRow target = match.get();
                fresh.remove(target);
                store.carry(target.id(), reencrypted(correction, target.id(), owner), correction.extractedType(),
                        correction.extractedNameFingerprint());
                matched++;
                continue;
            }
            Optional<Remapped> remapped = remap.apply(correction.passageIds());
            if (remapped.isEmpty() || remapped.get().passageIds().isEmpty()) {
                dropped++;
                continue;
            }
            UUID id = Ids.newId();
            RecordCorrection values = reencrypted(correction, id, owner);
            copies.add(new RecordRow(id, owner, toVersionId, nextOrdinal++, remapped.get().firstPage(),
                    values.type(), values.name(), values.nameFingerprint(), values.fields(), remapped.get().passageIds(),
                    now, 0, correction.correctedAt(), correction.extractedType(),
                    correction.extractedNameFingerprint()));
        }
        int copied = copies.size();
        int deleted = 0;
        for (RecordRow tombstone : tombstones) {
            Optional<RecordRow> match = matchTombstone(tombstone, fresh);
            if (match.isPresent()) {
                fresh.remove(match.get());
                if (store.delete(match.get().id(), tombstone.deletedAt(), true)) {
                    deleted++;
                }
                continue;
            }
            copies.add(tombstoneCopy(tombstone, owner, toVersionId, nextOrdinal++, now));
        }
        store.insert(copies);
        return new Carried(matched, copied, dropped, deleted, copies.size() - copied,
                carryLinks(fromVersionId, toVersionId, RecordRelationRow.OVERRIDES),
                carryLinks(fromVersionId, toVersionId, RecordRelationRow.RELATED));
    }

    /**
     * The links of the kind {@code kind} (the overrides, Story 5.5 part A, Q2; the relations, part B) to the old
     * version's live records moved to the new version's records, once the corrections are carried, as
     * {@link RecordRelations#carry} says: by the old record's (type key, name fingerprint), the nearest first page
     * winning, then the lowest ordinal.
     */
    private int carryLinks(UUID fromVersionId, UUID toVersionId, String kind) {
        List<RecordRow> newRecords = new ArrayList<>();
        return RecordRelations.carry(store, relations, fromVersionId, toVersionId, kind, old -> {
            if (newRecords.isEmpty()) {
                newRecords.addAll(store.ofVersion(toVersionId));
            }
            return nearest(old, newRecords.stream().filter(row -> sameKey(row, old.type(), old.nameFingerprint()))
                    .toList());
        });
    }

    /**
     * The record of the new version a deleted record matches (Story 4.5 part B): first among those whose (type,
     * fingerprint) equals its extracted key (its own key when it was never corrected), and only when none does among
     * those that equal its own key; within each, the nearest first page wins, then the nearest ordinal (a same-page
     * duplicate the GM kept is not the one taken), then the lowest ordinal.
     */
    private static Optional<RecordRow> matchTombstone(RecordRow tombstone, List<RecordRow> fresh) {
        String extractedType = tombstone.corrected() ? tombstone.extractedType() : tombstone.type();
        byte[] extractedFingerprint = tombstone.corrected() ? tombstone.extractedNameFingerprint()
                : tombstone.nameFingerprint();
        Optional<RecordRow> extracted = nearestTo(tombstone, fresh.stream().filter(row ->
                sameKey(row, extractedType, extractedFingerprint)).toList());
        return extracted.isPresent() ? extracted : nearestTo(tombstone, fresh.stream().filter(row ->
                sameKey(row, tombstone.type(), tombstone.nameFingerprint())).toList());
    }

    /**
     * A deleted record copied into the new version as a tombstone (Story 4.5 part B): a new id, no passage, its first
     * page, its type key and name fingerprint, its correction's time and extracted key, deleted when it was and its point
     * removed now (it is never indexed). Its name and fields are not read: an empty name and no field are stored,
     * encrypted bound to the new row, since a tombstone is never shown.
     */
    private RecordRow tombstoneCopy(RecordRow tombstone, UUID owner, UUID toVersionId, int ordinal, Instant now) {
        UUID id = Ids.newId();
        return new RecordRow(id, owner, toVersionId, ordinal, tombstone.firstPage(), tombstone.type(),
                vault.encryptText(name(id, owner), ""), tombstone.nameFingerprint(),
                vault.encrypt(fields(id, owner), fieldsJson(List.of())), List.of(), now, 0, tombstone.correctedAt(),
                tombstone.extractedType(), tombstone.extractedNameFingerprint(), tombstone.deletedAt(), now);
    }

    private static RecordStore.Reach reachOf(Reach reach) {
        return reach == Reach.ALL ? RecordStore.Reach.ALL : RecordStore.Reach.LIVE;
    }

    /**
     * The record of the new version a correction matches, as {@link #carryCorrections} says: first among those whose
     * (type, fingerprint) equals the correction's extracted key (the misread record read again), and only when none does
     * among those that equal its corrected key; within each, the nearest first page wins, then the lowest ordinal.
     */
    private static Optional<RecordRow> match(RecordRow correction, List<RecordRow> fresh) {
        Optional<RecordRow> extracted = nearest(correction, fresh.stream().filter(row ->
                sameKey(row, correction.extractedType(), correction.extractedNameFingerprint())).toList());
        return extracted.isPresent() ? extracted : nearest(correction, fresh.stream().filter(row ->
                sameKey(row, correction.type(), correction.nameFingerprint())).toList());
    }

    private static Optional<RecordRow> nearest(RecordRow correction, List<RecordRow> candidates) {
        return candidates.stream().min(Comparator.<RecordRow>comparingInt(row ->
                        Math.abs(row.firstPage() - correction.firstPage()))
                .thenComparingInt(RecordRow::ordinal).thenComparing(RecordRow::id));
    }

    /** As {@link #nearest}, but a page's tie goes to the ordinal nearest the deleted record's, then the lowest. */
    private static Optional<RecordRow> nearestTo(RecordRow tombstone, List<RecordRow> candidates) {
        return candidates.stream().min(Comparator.<RecordRow>comparingInt(row ->
                        Math.abs(row.firstPage() - tombstone.firstPage()))
                .thenComparingInt(row -> Math.abs(row.ordinal() - tombstone.ordinal()))
                .thenComparingInt(RecordRow::ordinal).thenComparing(RecordRow::id));
    }

    private static boolean sameKey(RecordRow row, String type, byte[] fingerprint) {
        return fingerprint != null && row.nameFingerprint() != null && row.type().equals(type)
                && Arrays.equals(row.nameFingerprint(), fingerprint);
    }

    /** A corrected record's values, decrypted and encrypted again bound to the row {@code rowId} they are written to. */
    private RecordCorrection reencrypted(RecordRow correction, UUID rowId, UUID owner) {
        String plainName = vault.decryptText(name(correction.id(), owner), correction.name());
        byte[] plainFields = vault.decrypt(fields(correction.id(), owner), correction.fields());
        try {
            return new RecordCorrection(correction.type(), vault.encryptText(name(rowId, owner), plainName),
                    nameFingerprint(plainName), vault.encrypt(fields(rowId, owner), plainFields), correction.correctedAt());
        } finally {
            Arrays.fill(plainFields, (byte) 0);
        }
    }

    private static Located located(RecordRow row) {
        return new Located(row.id(), row.versionId(), row.type(), row.firstPage(), row.passageIds());
    }

    /** The name's keyed fingerprint: of its unique form, so case, NFC and surrounding space make no difference. */
    private byte[] nameFingerprint(String name) {
        return nameFingerprint(vault, name);
    }

    /** A record name's keyed fingerprint, as {@link #nameFingerprint(String)} (a correction's, Story 4.5). */
    static byte[] nameFingerprint(Vault vault, String name) {
        return vault.fingerprint(RECORD, FIELD_NAME, Library.uniqueForm(name));
    }

    static List<Field> fieldsOf(byte[] json) {
        List<Field> found = new ArrayList<>();
        try {
            JSON.readTree(json).forEach(field -> found.add(new Field(field.path("name").asString(),
                    field.path("value").asString())));
        } finally {
            Arrays.fill(json, (byte) 0);
        }
        return List.copyOf(found);
    }

    static byte[] fieldsJson(List<Field> fields) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        fields.forEach(field -> array.addObject().put("name", field.name()).put("value", field.value()));
        return JSON.writeValueAsBytes(array);
    }

    private static UUID requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("knowledge.transaction_required");
        }
        return OwnerContext.require();
    }

    static Binding name(UUID rowId, UUID owner) {
        return new Binding(RECORD, FIELD_NAME, rowId, owner);
    }

    static Binding fields(UUID rowId, UUID owner) {
        return new Binding(RECORD, FIELD_FIELDS, rowId, owner);
    }
}
