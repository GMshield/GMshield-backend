package io.github.gmshield.knowledge.service;

import io.github.gmshield.knowledge.data.KnowledgeConflict;
import io.github.gmshield.knowledge.data.KnowledgeStore;
import io.github.gmshield.knowledge.data.RecordCorrection;
import io.github.gmshield.knowledge.data.RecordRow;
import io.github.gmshield.knowledge.data.RecordStore;
import io.github.gmshield.knowledge.data.RecordTypeRow;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.service.ExtractedRecords;
import io.github.gmshield.materials.service.ReadyVersions;
import io.github.gmshield.materials.service.RecordIndexing;
import io.github.gmshield.materials.service.RecordSources;
import io.github.gmshield.scope.service.CampaignScopes;
import io.github.gmshield.scope.service.Scopes;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.text.Collator;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A system's records, read-only (Story 4.3): the records of one of its record types, a page at a time, and one record
 * with its fields and source references, for the owner of the work ({@link OwnerContext}). Only the records of the
 * system's {@code ready} versions are read ({@link ReadyVersions#ofSystem}: the materials attached to it and those of
 * its campaigns), the rule the records tab counts by; anything else (another owner's record, one of a version that is
 * not accepted or is superseded, one of another system's materials, a system being deleted) is not found, exactly like
 * an unknown id.
 *
 * <ul>
 *   <li>{@link #records}: a record type's records, alphabetical by name: the names are decrypted and sorted with a
 *       root-locale {@link Collator} at secondary strength (case ignored; accents order only names that are otherwise
 *       equal, the unaccented first), the record id breaking ties, then paged in memory. Each shows as it was
 *       extracted: two records with one name are two rows. From Story 4.4 part B a record stores its type's key, so a
 *       type the GM approved lists the records of its key.</li>
 *   <li>{@link #record}: one record's name, the system's record type of its key (none: not found), its fields in
 *       stored order and its source references: one per distinct (version, section path, page) of its passages, in
 *       page order.</li>
 *   <li>{@link #page} and {@link #picture}: a page of the record's version, which the source view opens at a
 *   reference's
 *       page and turns from there.</li>
 *   <li>Story 4.3 part B, {@link #inCampaign}: one record as a campaign shows it (a search's record result): the same
 *       answer as {@link #record}, its type the campaign's system's type of its key (none: not found), for a record of
 *       a version that is an allowed source of the campaign's current scope ({@link Scopes#resolveSources}, its stored
 *       settings, no filter, any tier switched on, so a record of another system's material opens too while the other
 *       tier is on); anything else is not found. Its references open the source view on the ground of the campaign's
 *       current scope, so this class serves no page for it.</li>
 * </ul>
 *
 * <p>Story 4.5, {@link #correct} and {@link #correctInCampaign}: the GM corrects a record's name, type and fields on the
 * record's version (its row version, {@link Detail#version}), under the same visibility as the reads, so anything else
 * is not found. The name and each field's name and value are trimmed and keep the extraction's limits
 * ({@link ExtractedRecords#MAX_NAME} and the others); the type is a key of the page's system's record types (the
 * campaign's system on the campaign's page), core or approved. A stale version is {@code request.stale_version}. The
 * correction keeps the record's id, source references, ordinal and first page; its name and fields are encrypted again
 * bound to the row and its fingerprint worked out again, so the next question and search read it. The records run of its
 * first page joins its version's index changes ({@link RecordIndexing}) in the same transaction, which locks the
 * version first, so a correction never crosses the acceptance of a version that replaces it.
 *
 * Names, fields and places leave only in these answers; logs carry ids and counts only.
 */
@Service
public class Records {

    private static final Logger log = LoggerFactory.getLogger(Records.class);

    private final KnowledgeStore types;
    private final RecordStore store;
    private final Vault vault;
    private final Library library;
    private final ReadyVersions ready;
    private final RecordSources sources;
    private final Scopes scopes;
    private final CampaignScopes campaignScopes;
    private final RecordIndexing indexing;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final Clock clock;

    Records(KnowledgeStore types, RecordStore store, Vault vault, Library library, ReadyVersions ready,
            RecordSources sources, Scopes scopes, CampaignScopes campaignScopes, RecordIndexing indexing,
            ObjectProvider<PlatformTransactionManager> transactions, ObjectProvider<Clock> clock) {
        this.types = types;
        this.store = store;
        this.vault = vault;
        this.library = library;
        this.ready = ready;
        this.sources = sources;
        this.scopes = scopes;
        this.campaignScopes = campaignScopes;
        this.indexing = indexing;
        this.transactions = transactions;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** A record as its type's list shows it: its id, name, its material's name and its first page. */
    public record Summary(UUID id, String name, String materialName, int page) {
        @Override
        public String toString() {
            return "Summary[id=" + id + ", page=" + page + "]";
        }
    }

    /** One page of a record type's records and how many it has in all. */
    public record SummaryPage(List<Summary> items, long total) {
    }

    /**
     * The system's record type of a record: its id, its core key (null for a type the GM approved), its key (Story 4.4
     * part B), and its stored name.
     */
    public record TypeOf(UUID id, String core, String key, String name) {
        @Override
        public String toString() {
            return "TypeOf[id=" + id + ", core=" + core + ", key=" + key + "]";
        }
    }

    /** A record's field, in stored order. {@code toString} names neither part. */
    public record Field(String name, String value) {
        @Override
        public String toString() {
            return "Field";
        }
    }

    /**
     * Where a record was read from: its version, the material's name, the section path and the page, with the ids of the
     * record's passages there, in the order {@link RecordSources#places} gives them, so the source view marks them as it
     * marks an answer's cited passages.
     */
    public record Reference(UUID versionId, String materialName, List<String> sectionPath, int page,
                            List<UUID> passageIds) {
        public Reference {
            sectionPath = List.copyOf(sectionPath);
            passageIds = List.copyOf(passageIds);
        }

        @Override
        public String toString() {
            return "Reference[versionId=" + versionId + ", page=" + page + ", passages=" + passageIds.size() + "]";
        }
    }

    /**
     * One record as its page shows it, with its row version (Story 4.5: a correction names it). {@code toString} names
     * ids and counts only.
     */
    public record Detail(UUID id, String name, TypeOf recordType, List<Field> fields, List<Reference> references,
                         long version) {
        public Detail {
            fields = List.copyOf(fields);
            references = List.copyOf(references);
        }

        @Override
        public String toString() {
            return "Detail[id=" + id + ", recordType=" + recordType.id() + ", fields=" + fields.size()
                    + ", references=" + references.size() + ", version=" + version + "]";
        }
    }

    /**
     * The GM's correction of a record (Story 4.5): the row version it was made on, the new name, the new type's key and
     * the new fields in order. {@code toString} names the version, the type and the count only.
     */
    public record Correction(long version, String name, String recordType, List<Field> fields) {
        public Correction {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(recordType, "recordType");
            fields = List.copyOf(fields);
        }

        @Override
        public String toString() {
            return "Correction[version=" + version + ", recordType=" + recordType + ", fields=" + fields.size() + "]";
        }
    }

    /**
     * The records of the owner's record type {@code recordTypeId}, as the class says: the {@code offset} first ones
     * skipped (0 to {@value Ontology#MAX_OFFSET}), at most {@code limit} (1 to {@value Ontology#MAX_LIMIT}), with how
     * many it has in all. Empty for an unknown record type, another owner's or one of a system being deleted.
     */
    public Optional<SummaryPage> records(UUID recordTypeId, int offset, int limit) {
        if (limit < 1 || limit > Ontology.MAX_LIMIT) {
            throw new IllegalArgumentException("knowledge.limit_invalid");
        }
        if (offset < 0 || offset > Ontology.MAX_OFFSET) {
            throw new IllegalArgumentException("knowledge.offset_invalid");
        }
        Optional<SummaryPage> found = reading(() -> types.findRecordType(recordTypeId)
                .filter(type -> library.system(type.systemId()).isPresent()).map(type -> {
                    Set<UUID> versions = ready.ofSystem(type.systemId()).orElse(Set.of());
                    List<Named> named = sorted(versions.isEmpty() ? List.of() : store.ofTypeIn(versions, type.key()));
                    List<Named> shown = named.subList(Math.min(offset, named.size()),
                            Math.min(offset + limit, named.size()));
                    Map<UUID, String> materials = sources.materialNames(shown.stream()
                            .map(record -> record.row().versionId()).toList());
                    return new SummaryPage(shown.stream().map(record -> new Summary(record.row().id(), record.name(),
                            materials.getOrDefault(record.row().versionId(), ""), record.row().firstPage())).toList(),
                            named.size());
                }));
        log.atInfo().addKeyValue("recordTypeId", recordTypeId).addKeyValue("offset", offset)
                .addKeyValue("limit", limit).addKeyValue("found", found.isPresent())
                .addKeyValue("shown", found.map(page -> page.items().size()).orElse(0))
                .addKeyValue("total", found.map(SummaryPage::total).orElse(0L))
                .log("knowledge.records_listed");
        return found;
    }

    /** The owner's record {@code recordId} as the system {@code systemId} shows it, as the class says; else empty. */
    public Optional<Detail> record(UUID systemId, UUID recordId) {
        UUID owner = OwnerContext.require();
        Optional<Detail> found = reading(() -> visible(systemId, recordId).flatMap(row -> typeOf(systemId, row.type())
                .map(type -> detail(row, type, owner))));
        log.atInfo().addKeyValue("systemId", systemId).addKeyValue("recordId", recordId)
                .addKeyValue("found", found.isPresent())
                .addKeyValue("references", found.map(detail -> detail.references().size()).orElse(0))
                .log("knowledge.record_read");
        return found;
    }

    /**
     * The owner's record {@code recordId} as the campaign {@code campaignId}'s current scope shows it, as the class says
     * (Story 4.3 part B); else empty.
     */
    public Optional<Detail> inCampaign(UUID campaignId, UUID recordId) {
        UUID owner = OwnerContext.require();
        Optional<Detail> found = reading(() -> library.campaign(campaignId).flatMap(campaign -> {
            Set<UUID> allowed = scopes.resolveSources(campaign.id(), campaignScopes.settings(campaign.id()))
                    .allowedSources();
            return store.find(recordId).filter(row -> allowed.contains(row.versionId()))
                    .flatMap(row -> typeOf(campaign.system().id(), row.type()).map(type -> detail(row, type, owner)));
        }));
        log.atInfo().addKeyValue("campaignId", campaignId).addKeyValue("recordId", recordId)
                .addKeyValue("found", found.isPresent())
                .addKeyValue("references", found.map(detail -> detail.references().size()).orElse(0))
                .log("knowledge.campaign_record_read");
        return found;
    }

    /**
     * Corrects the owner's record {@code recordId} as the system {@code systemId} shows it, as the class says; answers it
     * as stored, its version raised by one, or empty when the system does not show it.
     */
    public Optional<Detail> correct(UUID systemId, UUID recordId, Correction correction) {
        Checked checked = checked(correction);
        Optional<Detail> corrected = writing(() -> store.find(recordId)
                .filter(row -> indexing.lockReady(row.versionId()))
                .filter(row -> library.system(systemId).isPresent()
                        && ready.ofSystem(systemId).orElse(Set.of()).contains(row.versionId()))
                .flatMap(row -> write(systemId, row, checked)));
        log.atInfo().addKeyValue("systemId", systemId).addKeyValue("recordId", recordId)
                .addKeyValue("found", corrected.isPresent())
                .addKeyValue("version", corrected.map(Detail::version).orElse(-1L))
                .addKeyValue("fields", checked.fields().size()).log("knowledge.record_corrected");
        return corrected;
    }

    /**
     * Corrects the owner's record {@code recordId} as the campaign {@code campaignId}'s current scope shows it, as the
     * class says; answers it as stored, or empty when the campaign does not show it.
     */
    public Optional<Detail> correctInCampaign(UUID campaignId, UUID recordId, Correction correction) {
        Checked checked = checked(correction);
        Optional<Detail> corrected = writing(() -> library.campaign(campaignId).flatMap(campaign ->
                store.find(recordId).filter(row -> indexing.lockReady(row.versionId()))
                        .filter(row -> scopes.resolveSources(campaign.id(), campaignScopes.settings(campaign.id()))
                                .allowedSources().contains(row.versionId()))
                        .flatMap(row -> write(campaign.system().id(), row, checked))));
        log.atInfo().addKeyValue("campaignId", campaignId).addKeyValue("recordId", recordId)
                .addKeyValue("found", corrected.isPresent())
                .addKeyValue("version", corrected.map(Detail::version).orElse(-1L))
                .addKeyValue("fields", checked.fields().size()).log("knowledge.campaign_record_corrected");
        return corrected;
    }

    /** A correction's values, trimmed and within their limits. */
    private record Checked(long version, String name, String recordType, List<ExtractedRecords.Field> fields) {
        @Override
        public String toString() {
            return "Checked[version=" + version + ", fields=" + fields.size() + "]";
        }
    }

    /** The correction's values trimmed, or the problem of the first one outside its limits. */
    private static Checked checked(Correction correction) {
        String name = correction.name().strip();
        if (!within(name, ExtractedRecords.MAX_NAME)) {
            throw new ApiException(KnowledgeProblems.RECORD_NAME_INVALID);
        }
        if (correction.fields().size() > ExtractedRecords.MAX_FIELDS) {
            throw new ApiException(KnowledgeProblems.RECORD_FIELDS_INVALID);
        }
        List<ExtractedRecords.Field> fields = new ArrayList<>(correction.fields().size());
        for (int index = 0; index < correction.fields().size(); index++) {
            Field field = correction.fields().get(index);
            String fieldName = field.name() == null ? "" : field.name().strip();
            String value = field.value() == null ? "" : field.value().strip();
            if (!within(fieldName, ExtractedRecords.MAX_FIELD_NAME)) {
                throw new ApiException(KnowledgeProblems.RECORD_FIELD_NAME_INVALID.withParams(Map.of("min", 1,
                        "max", ExtractedRecords.MAX_FIELD_NAME, "index", index)));
            }
            if (!within(value, ExtractedRecords.MAX_FIELD_VALUE)) {
                throw new ApiException(KnowledgeProblems.RECORD_FIELD_VALUE_INVALID.withParams(Map.of("min", 1,
                        "max", ExtractedRecords.MAX_FIELD_VALUE, "index", index)));
            }
            fields.add(new ExtractedRecords.Field(fieldName, value));
        }
        return new Checked(correction.version(), name, correction.recordType(), List.copyOf(fields));
    }

    /** Whether a trimmed text has 1 to {@code max} code points. */
    private static boolean within(String text, int max) {
        int points = text.codePointCount(0, text.length());
        return points >= 1 && points <= max;
    }

    /**
     * Writes the checked correction over the visible row, in the caller's transaction, with its version locked and
     * {@code ready}: empty when the system has no type of the record's current key (it does not show it), else the
     * record as stored. Its first page's records run joins its version's index changes.
     */
    private Optional<Detail> write(UUID systemId, RecordRow row, Checked checked) {
        if (typeOf(systemId, row.type()).isEmpty()) {
            return Optional.empty();
        }
        TypeOf type = typeOf(systemId, checked.recordType())
                .orElseThrow(() -> new ApiException(KnowledgeProblems.RECORD_TYPE_INVALID));
        if (row.version() != checked.version()) {
            throw new ApiException(KnowledgeProblems.STALE_VERSION);
        }
        UUID owner = row.ownerId();
        RecordCorrection values = new RecordCorrection(type.key(),
                vault.encryptText(ExtractedRecordStore.name(row.id(), owner), checked.name()),
                ExtractedRecordStore.nameFingerprint(vault, checked.name()),
                vault.encrypt(ExtractedRecordStore.fields(row.id(), owner),
                        ExtractedRecordStore.fieldsJson(checked.fields())),
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        RecordRow stored;
        try {
            stored = store.correct(row.id(), checked.version(), values)
                    .orElseThrow(() -> new IllegalStateException("knowledge.record_vanished"));
        } catch (KnowledgeConflict conflict) {
            throw new ApiException(KnowledgeProblems.STALE_VERSION);
        }
        if (!indexing.reindexPage(row.versionId(), row.firstPage())) {
            throw new IllegalStateException("knowledge.record_version_not_ready");
        }
        return Optional.of(detail(stored, type, owner));
    }

    private Detail detail(RecordRow row, TypeOf type, UUID owner) {
        return new Detail(row.id(), vault.decryptText(ExtractedRecordStore.name(row.id(), owner), row.name()), type,
                fields(row, owner), references(row), row.version());
    }

    /** One transaction for a correction and its checks: a problem thrown inside rolls everything back. */
    private <T> T writing(Supplier<T> write) {
        OwnerContext.require();
        return new TransactionTemplate(transactions.getObject()).execute(status -> write.get());
    }

    /** Page {@code page} of the version of the record {@code recordId} as the system shows it; else empty. */
    public Optional<RecordSources.Page> page(UUID systemId, UUID recordId, int page) {
        Optional<RecordSources.Page> served = reading(() -> visible(systemId, recordId)
                .flatMap(row -> sources.page(row.versionId(), page)));
        log.atInfo().addKeyValue("systemId", systemId).addKeyValue("recordId", recordId).addKeyValue("page", page)
                .addKeyValue("served", served.isPresent()).log("knowledge.record_page");
        return served;
    }

    /** The picture of that page, as {@link #page} finds it; empty also for a page with no picture. */
    public Optional<RecordSources.Picture> picture(UUID systemId, UUID recordId, int page) {
        Optional<RecordSources.Picture> served = reading(() -> visible(systemId, recordId)
                .flatMap(row -> sources.picture(row.versionId(), page)));
        log.atInfo().addKeyValue("systemId", systemId).addKeyValue("recordId", recordId).addKeyValue("page", page)
                .addKeyValue("served", served.isPresent()).log("knowledge.record_picture");
        return served;
    }

    /** The owner's record, when the system is the owner's and ready and the record's version is one of its ready ones. */
    private Optional<RecordRow> visible(UUID systemId, UUID recordId) {
        if (library.system(systemId).isEmpty()) {
            return Optional.empty();
        }
        Set<UUID> versions = ready.ofSystem(systemId).orElse(Set.of());
        return store.find(recordId).filter(row -> versions.contains(row.versionId()));
    }

    /**
     * The system's record type of a key, its name decrypted; empty when the system has no type with that key (Story 4.4
     * part B: a record of a type approved in another system shows nowhere here).
     */
    private Optional<TypeOf> typeOf(UUID systemId, String key) {
        return types.findByKey(systemId, key).map(type -> new TypeOf(type.id(), type.core(), type.key(),
                vault.decryptText(new Binding(Ontology.RECORD_TYPE, Ontology.FIELD_NAME, type.id(), type.ownerId()),
                        type.name())));
    }

    private List<Field> fields(RecordRow row, UUID owner) {
        return ExtractedRecordStore.fieldsOf(vault.decrypt(ExtractedRecordStore.fields(row.id(), owner), row.fields()))
                .stream().map(Records::field).toList();
    }

    private static Field field(ExtractedRecords.Field field) {
        return new Field(field.name(), field.value());
    }

    /**
     * One reference per distinct (version, section path, page) of the record's passages, in page order (first seen
     * first within a page), each with the ids of the record's passages there.
     */
    private List<Reference> references(RecordRow row) {
        Map<Place, String> materials = new LinkedHashMap<>();
        Map<Place, List<UUID>> passages = new LinkedHashMap<>();
        for (RecordSources.Place place : sources.places(row.passageIds(), Set.of(row.versionId()))) {
            Place key = new Place(place.versionId(), place.sectionPath(), place.page());
            materials.putIfAbsent(key, place.materialName());
            passages.computeIfAbsent(key, ignored -> new ArrayList<>()).add(place.passageId());
        }
        List<Reference> ordered = new ArrayList<>(passages.size());
        passages.forEach((key, ids) -> ordered.add(new Reference(key.versionId(), materials.get(key),
                key.sectionPath(), key.page(), ids)));
        ordered.sort(Comparator.comparingInt(Reference::page));
        return ordered;
    }

    /** A place a record was read from, as its references group its passages. */
    private record Place(UUID versionId, List<String> sectionPath, int page) {
    }

    /** A record and its decrypted name. */
    private record Named(RecordRow row, String name) {
    }

    /**
     * The records by name (case ignored; accents order only names that are otherwise equal), then by id. The store
     * answers one row per record.
     */
    private List<Named> sorted(List<RecordRow> rows) {
        UUID owner = OwnerContext.require();
        Collator collator = Collator.getInstance(Locale.ROOT);
        collator.setStrength(Collator.SECONDARY);
        List<Named> named = new ArrayList<>(rows.size());
        for (RecordRow row : rows) {
            named.add(new Named(row, vault.decryptText(ExtractedRecordStore.name(row.id(), owner), row.name())));
        }
        named.sort(Comparator.comparing(Named::name, collator).thenComparing(record -> record.row().id()));
        return named;
    }

    /** One read-only transaction for the reads of one answer, so they come from one session. */
    private <T> T reading(Supplier<T> read) {
        OwnerContext.require();
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }
}
