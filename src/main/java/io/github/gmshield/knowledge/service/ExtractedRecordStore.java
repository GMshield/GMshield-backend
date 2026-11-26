package io.github.gmshield.knowledge.service;

import io.github.gmshield.knowledge.data.RecordCorrection;
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
 * corrections over to the version reprocessing made from it, each value encrypted again bound to its new row. Nothing
 * here logs a name or a field.
 */
@Service
public class ExtractedRecordStore implements ExtractedRecords {

    static final String RECORD = "record";
    static final String FIELD_NAME = "name";
    static final String FIELD_FIELDS = "fields";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RecordStore store;
    private final Vault vault;
    private final Clock clock;

    ExtractedRecordStore(RecordStore store, Vault vault, ObjectProvider<Clock> clock) {
        this.store = store;
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
    public List<Located> located(Collection<UUID> ids) {
        OwnerContext.require();
        List<UUID> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return List.of();
        }
        Map<UUID, RecordRow> found = new HashMap<>();
        store.findAll(wanted).forEach(row -> found.put(row.id(), row));
        return wanted.stream().filter(found::containsKey).map(id -> located(found.get(id))).toList();
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
            store.findAll(wanted).forEach(row -> found.put(row.id(), vault.decryptText(name(row.id(), owner),
                    row.name())));
        }
        return Map.copyOf(found);
    }

    @Override
    public List<Content> read(Collection<UUID> ids) {
        UUID owner = OwnerContext.require();
        List<UUID> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return List.of();
        }
        Map<UUID, RecordRow> found = new HashMap<>();
        store.findAll(wanted).forEach(row -> found.put(row.id(), row));
        return wanted.stream().filter(found::containsKey).map(found::get).map(row -> new Content(row.id(),
                row.versionId(), row.type(), row.firstPage(), vault.decryptText(name(row.id(), owner), row.name()),
                fieldsOf(vault.decrypt(fields(row.id(), owner), row.fields())))).toList();
    }

    @Override
    public Carried carryCorrections(UUID fromVersionId, UUID toVersionId,
                                    Function<List<UUID>, Optional<Remapped>> remap) {
        UUID owner = requireTransaction();
        List<RecordRow> corrections = store.ofVersion(fromVersionId).stream().filter(RecordRow::corrected).toList();
        if (corrections.isEmpty()) {
            return new Carried(0, 0, 0);
        }
        List<RecordRow> fresh = new ArrayList<>(store.ofVersion(toVersionId).stream()
                .filter(row -> !row.corrected()).toList());
        int nextOrdinal = store.ofVersion(toVersionId).stream().mapToInt(RecordRow::ordinal).max().orElse(-1) + 1;
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
            copies.add(new RecordRow(id, owner, toVersionId, nextOrdinal++, remapped.get().firstPage(), values.type(),
                    values.name(), values.nameFingerprint(), values.fields(), remapped.get().passageIds(), now, 0,
                    correction.correctedAt(), correction.extractedType(), correction.extractedNameFingerprint()));
        }
        store.insert(copies);
        return new Carried(matched, copies.size(), dropped);
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
