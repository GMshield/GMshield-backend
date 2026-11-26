package io.github.gmshield.artifacts.service;

import io.github.gmshield.artifacts.data.ArtifactVersionRow;
import io.github.gmshield.artifacts.data.ArtifactVersionStore;
import io.github.gmshield.artifacts.data.VersionTaken;
import io.github.gmshield.knowledge.service.ArtifactRecords;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * A campaign's artifacts (Story 5.1 part A), for the owner of the work ({@link OwnerContext}): the GM writes one by hand,
 * lists a campaign's and opens one. Every call names the campaign, which must be the owner's and readable
 * ({@link Library#campaign}: an archived campaign too, a campaign being deleted or of a system being deleted not); an
 * artifact of another campaign, another owner's and an unknown id are not found, exactly like an unknown campaign.
 *
 * <ul>
 *   <li>{@link #create}: in one transaction, the artifact's identity ({@link ArtifactRecords#create}: a {@code record}
 *       row of the campaign, its type a key of the campaign's system) and its version 1 (the name, fields and content,
 *       each encrypted bound to the entity {@value #ENTITY}, its field, the artifact's id and the owner). The name and
 *       fields keep a record's limits ({@link ArtifactRecords#checked}); the content is optional, at most
 *       {@value #MAX_CONTENT} code points after trimming, and a blank one is none. A value outside its limits is
 *       {@code request.invalid} (400) naming its field, and nothing is stored. A character sheet stores what was
 *       written: nothing is calculated. From Story 5.1 part B the same transaction creates the version's index job
 *       ({@link ArtifactIndexing#start}): a rollback leaves neither.</li>
 *   <li>{@link #list}: the campaign's artifacts newest first (by id, a UUIDv7), of one type key or all, a page at a time
 *       (after a cursor, or at an offset), with the total and the type keys in use with their counts.</li>
 *   <li>{@link #get}: one artifact with its newest version, or (Story 5.2) with one of its versions by number, as a
 *       reference that cited it opens it.</li>
 *   <li>{@link #update} (Story 5.2): the GM's edit of the name, type, fields and content, with the limits and the type
 *       check of {@link #create}. It names the newest version the GM saw; it stores the next number, gives the identity
 *       the new version's type ({@link ArtifactRecords#retype}) and creates the new version's index job, all in one
 *       transaction, and every earlier version is kept. A version that is not the newest, or a number another write took
 *       first, is {@code request.stale_version} (409), and nothing is stored.</li>
 *   <li>{@link #restore} (Story 5.2): the same write, with the type, name, fields and content of an earlier version; a
 *       version whose type the system no longer has is {@code request.invalid} naming the record type.</li>
 *   <li>{@link #versions} (Story 5.2): the artifact's versions newest first, a page at a time, with the total.</li>
 * </ul>
 *
 * Names, fields and content leave only in these answers; logs carry ids and counts only.
 */
@Service
public class Artifacts {

    /** The entity the vault binds a version's values to. */
    static final String ENTITY = "record_version";
    static final String FIELD_NAME = "name";
    static final String FIELD_FIELDS = "fields";
    static final String FIELD_CONTENT = "content";
    /** The problem's {@code field} for the content. */
    public static final String CONTENT = "content";
    /** An artifact's content: at most this many code points after trimming. */
    public static final int MAX_CONTENT = 20_000;
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    public static final int MAX_OFFSET = 100_000;
    private static final int FIRST_VERSION = 1;
    /** An edit or a restore names a version the artifact has left (Story 5.2): someone changed it elsewhere. */
    public static final ApiProblem STALE_VERSION =
            ApiProblem.of(409, ApiProblem.CATEGORY_REQUEST, "stale_version", "Stale version");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(Artifacts.class);

    private final ArtifactRecords records;
    private final ArtifactVersionStore versions;
    private final Library library;
    private final Vault vault;
    private final ArtifactIndexing indexing;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final Clock clock;

    Artifacts(ArtifactRecords records, ArtifactVersionStore versions, Library library, Vault vault,
              ArtifactIndexing indexing, ObjectProvider<PlatformTransactionManager> transactions,
              ObjectProvider<Clock> clock) {
        this.records = records;
        this.versions = versions;
        this.library = library;
        this.vault = vault;
        this.indexing = indexing;
        this.transactions = transactions;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** A field of an artifact, in order. {@code toString} names neither part. */
    public record Field(String name, String value) {
        @Override
        public String toString() {
            return "Field";
        }
    }

    /**
     * What the GM wrote: the record type's key, the name, the fields in order and the content (null for none).
     * {@code toString} names the type and the count only.
     */
    public record Written(String recordType, String name, List<Field> fields, String content) {
        public Written {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }

        @Override
        public String toString() {
            return "Written[recordType=" + recordType + ", fields=" + fields.size() + "]";
        }
    }

    /**
     * One artifact with one of its versions, the newest unless an earlier one was asked for: its id, its campaign, the
     * version's type key, name, fields and content (null for none), the version's number, the number of the artifact's
     * newest version (Story 5.2; the same as {@code version} unless an earlier one is read) and when the artifact was
     * written. {@code toString} names ids and counts only.
     */
    public record Artifact(UUID id, UUID campaignId, String recordType, String name, List<Field> fields,
                           String content, int version, int currentVersion, Instant createdAt) {
        public Artifact {
            fields = List.copyOf(fields);
        }

        @Override
        public String toString() {
            return "Artifact[id=" + id + ", recordType=" + recordType + ", fields=" + fields.size() + ", version="
                    + version + ", currentVersion=" + currentVersion + "]";
        }
    }

    /** An artifact as a list shows it: its id, type key, name, version and when it was written. */
    public record Summary(UUID id, String recordType, String name, int version, Instant createdAt) {
        @Override
        public String toString() {
            return "Summary[id=" + id + ", recordType=" + recordType + "]";
        }
    }

    /** One version of an artifact as its versions list shows it (Story 5.2): its number, type key, name and time. */
    public record VersionSummary(int number, String recordType, String name, Instant createdAt) {
        @Override
        public String toString() {
            return "VersionSummary[number=" + number + ", recordType=" + recordType + "]";
        }
    }

    /**
     * One page of an artifact's versions, newest first (Story 5.2): its rows, the cursor of the next page (the last
     * number shown; null on the last page) and how many versions the artifact has.
     */
    public record VersionPage(List<VersionSummary> items, Integer nextCursor, long total) {
        public VersionPage {
            items = List.copyOf(items);
        }
    }

    /** A type key in use in the campaign's artifacts and how many have it. */
    public record TypeCount(String key, long count) {
    }

    /**
     * One page of a campaign's artifacts: its rows, the cursor of the next page (null on the last), how many the list
     * has in all (of its type when filtered) and the type keys in use in the whole campaign.
     */
    public record Page(List<Summary> items, UUID nextCursor, long total, List<TypeCount> types) {
        public Page {
            items = List.copyOf(items);
            types = List.copyOf(types);
        }
    }

    /**
     * Writes a new artifact of the owner's campaign {@code campaignId}, as the class says; answers it as stored, or empty
     * when the campaign is not the owner's or not readable (nothing is stored then).
     */
    public Optional<Artifact> create(UUID campaignId, Written written) {
        UUID owner = OwnerContext.require();
        Optional<Artifact> created = writing(() -> library.campaign(campaignId).map(campaign -> {
            String recordType = records.checkedType(campaign.system().id(), written.recordType());
            ArtifactRecords.Checked checked = ArtifactRecords.checked(written.name(), written.fields().stream()
                    .map(field -> new ArtifactRecords.Field(field.name(), field.value())).toList());
            String content = content(written.content());
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            ArtifactRecords.Identity identity = records.create(campaign.id(), recordType, now);
            List<Field> fields = checked.fields().stream().map(field -> new Field(field.name(), field.value()))
                    .toList();
            UUID id = identity.id();
            versions.insert(sealed(id, owner, FIRST_VERSION, recordType, checked.name(), fields, content, now));
            // Story 5.1 part B: its index job, in the same transaction.
            indexing.start(id, FIRST_VERSION, now);
            return new Artifact(id, campaign.id(), recordType, checked.name(), fields, content, FIRST_VERSION,
                    FIRST_VERSION, now);
        }));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("found", created.isPresent())
                .addKeyValue("artifactId", created.map(Artifact::id).orElse(null))
                .addKeyValue("fields", created.map(artifact -> artifact.fields().size()).orElse(0))
                .addKeyValue("content", created.map(artifact -> artifact.content() != null).orElse(false))
                .log("artifacts.created");
        return created;
    }

    /**
     * A page of the owner's campaign {@code campaignId}'s artifacts, as the class says: of the type key
     * {@code recordType} only when it is not null, after {@code cursor} (an artifact id of the previous page) or the
     * {@code offset} first skipped (0 to {@value #MAX_OFFSET}), at most {@code limit} (1 to {@value #MAX_LIMIT}).
     * Empty when the campaign is not the owner's or not readable.
     */
    public Optional<Page> list(UUID campaignId, String recordType, UUID cursor, int offset, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("artifacts.limit_invalid");
        }
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new IllegalArgumentException("artifacts.offset_invalid");
        }
        UUID owner = OwnerContext.require();
        Optional<Page> found = reading(() -> library.campaign(campaignId).map(campaign -> {
            // One row past the page tells whether a next page exists.
            List<ArtifactRecords.Identity> rows = records.list(campaign.id(), recordType, cursor, offset, limit + 1);
            List<ArtifactRecords.Identity> shown = rows.subList(0, Math.min(limit, rows.size()));
            Map<UUID, ArtifactVersionRow> latest = versions.latest(shown.stream().map(ArtifactRecords.Identity::id)
                    .toList());
            List<Summary> items = new ArrayList<>(shown.size());
            for (ArtifactRecords.Identity identity : shown) {
                ArtifactVersionRow version = latest.get(identity.id());
                if (version == null) {
                    throw new IllegalStateException("artifacts.version_missing");
                }
                items.add(new Summary(identity.id(), identity.recordType(),
                        vault.decryptText(binding(FIELD_NAME, identity.id(), owner), version.name()), version.number(),
                        identity.createdAt()));
            }
            UUID next = rows.size() > limit ? shown.getLast().id() : null;
            return new Page(items, next, records.count(campaign.id(), recordType), records.types(campaign.id())
                    .stream().map(type -> new TypeCount(type.key(), type.count())).toList());
        }));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("filtered", recordType != null).addKeyValue("offset", offset).addKeyValue("limit", limit)
                .addKeyValue("found", found.isPresent())
                .addKeyValue("shown", found.map(page -> page.items().size()).orElse(0))
                .addKeyValue("total", found.map(Page::total).orElse(0L)).log("artifacts.listed");
        return found;
    }

    /**
     * The owner's artifact {@code artifactId} of the campaign {@code campaignId} with its newest version; empty for any
     * other id, or when the campaign is not the owner's or not readable.
     */
    public Optional<Artifact> get(UUID campaignId, UUID artifactId) {
        return get(campaignId, artifactId, null);
    }

    /**
     * {@link #get(UUID, UUID)}, with the version {@code number} instead of the newest when it is not null (Story 5.2: a
     * reference that cited it); empty too for a number the artifact does not have.
     */
    public Optional<Artifact> get(UUID campaignId, UUID artifactId, Integer number) {
        UUID owner = OwnerContext.require();
        Optional<Artifact> found = reading(() -> library.campaign(campaignId)
                .flatMap(campaign -> records.find(campaign.id(), artifactId))
                .flatMap(identity -> {
                    ArtifactVersionRow newest = versions.latest(identity.id())
                            .orElseThrow(() -> new IllegalStateException("artifacts.version_missing"));
                    Optional<ArtifactVersionRow> shown = number == null || number == newest.number()
                            ? Optional.of(newest) : versions.find(identity.id(), number);
                    return shown.map(version -> open(vault, identity, version, newest.number(), owner));
                }));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("artifactId", artifactId).addKeyValue("version", number)
                .addKeyValue("found", found.isPresent()).log("artifacts.read");
        return found;
    }

    /**
     * The GM's edit of the owner's artifact {@code artifactId} of the campaign {@code campaignId}, as the class says:
     * {@code version} is the newest version the GM saw. Answers the new version, or empty when the campaign is not the
     * owner's or not readable, or the artifact is not the campaign's (nothing is stored then).
     */
    public Optional<Artifact> update(UUID campaignId, UUID artifactId, int version, Written written) {
        UUID owner = OwnerContext.require();
        Optional<Artifact> updated = writing(() -> library.campaign(campaignId).flatMap(campaign ->
                records.find(campaign.id(), artifactId).map(identity -> {
                    String recordType = records.checkedType(campaign.system().id(), written.recordType());
                    ArtifactRecords.Checked checked = ArtifactRecords.checked(written.name(), written.fields()
                            .stream().map(field -> new ArtifactRecords.Field(field.name(), field.value())).toList());
                    List<Field> fields = checked.fields().stream().map(field -> new Field(field.name(), field.value()))
                            .toList();
                    return next(identity, owner, version, recordType, checked.name(), fields,
                            content(written.content()));
                })));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("artifactId", artifactId).addKeyValue("seen", version)
                .addKeyValue("found", updated.isPresent())
                .addKeyValue("version", updated.map(Artifact::version).orElse(null))
                .addKeyValue("fields", updated.map(artifact -> artifact.fields().size()).orElse(0))
                .addKeyValue("content", updated.map(artifact -> artifact.content() != null).orElse(false))
                .log("artifacts.updated");
        return updated;
    }

    /**
     * The GM's restore of the version {@code number} of the owner's artifact {@code artifactId} of the campaign
     * {@code campaignId}, as the class says: {@code version} is the newest version the GM saw. Answers the new version,
     * or empty for anything {@link #update} answers empty for, and for a number the artifact does not have.
     */
    public Optional<Artifact> restore(UUID campaignId, UUID artifactId, int number, int version) {
        UUID owner = OwnerContext.require();
        Optional<Artifact> restored = writing(() -> library.campaign(campaignId).flatMap(campaign ->
                records.find(campaign.id(), artifactId).flatMap(identity -> versions.find(identity.id(), number)
                        .map(earlier -> {
                            Artifact copied = open(vault, identity, earlier, earlier.number(), owner);
                            String recordType = records.checkedType(campaign.system().id(), earlier.recordType());
                            return next(identity, owner, version, recordType, copied.name(), copied.fields(),
                                    copied.content());
                        }))));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("artifactId", artifactId).addKeyValue("restored", number).addKeyValue("seen", version)
                .addKeyValue("found", restored.isPresent())
                .addKeyValue("version", restored.map(Artifact::version).orElse(null)).log("artifacts.restored");
        return restored;
    }

    /**
     * A page of the versions of the owner's artifact {@code artifactId} of the campaign {@code campaignId}, newest first:
     * those below the number {@code cursor} (the previous page's last) or the {@code offset} first skipped (0 to
     * {@value #MAX_OFFSET}), at most {@code limit} (1 to {@value #MAX_LIMIT}). Empty when the campaign is not the
     * owner's or not readable, or the artifact is not the campaign's.
     */
    public Optional<VersionPage> versions(UUID campaignId, UUID artifactId, Integer cursor, int offset, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("artifacts.limit_invalid");
        }
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new IllegalArgumentException("artifacts.offset_invalid");
        }
        UUID owner = OwnerContext.require();
        Optional<VersionPage> found = reading(() -> library.campaign(campaignId)
                .flatMap(campaign -> records.find(campaign.id(), artifactId))
                .map(identity -> {
                    UUID id = identity.id();
                    // One row past the page tells whether a next page exists.
                    List<ArtifactVersionRow> rows = versions.page(id, cursor, offset, limit + 1);
                    List<ArtifactVersionRow> shown = rows.subList(0, Math.min(limit, rows.size()));
                    List<VersionSummary> items = shown.stream().map(row -> new VersionSummary(row.number(),
                            row.recordType(), vault.decryptText(binding(FIELD_NAME, id, owner), row.name()),
                            row.createdAt())).toList();
                    Integer next = rows.size() > limit ? shown.getLast().number() : null;
                    return new VersionPage(items, next, versions.count(id));
                }));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("artifactId", artifactId).addKeyValue("offset", offset).addKeyValue("limit", limit)
                .addKeyValue("found", found.isPresent())
                .addKeyValue("shown", found.map(page -> page.items().size()).orElse(0))
                .addKeyValue("total", found.map(VersionPage::total).orElse(0L)).log("artifacts.versions_listed");
        return found;
    }

    /**
     * The next version of the artifact {@code identity} (Story 5.2), in the caller's transaction: {@code seen} must be
     * its newest number, read again once that version is locked (behind its unended index jobs' locks, as
     * {@link ArtifactIndexing#lockUnended} says); a number taken first by another write is stale too. Then the version,
     * the identity's type and the version's index job.
     */
    private Artifact next(ArtifactRecords.Identity identity, UUID owner, int seen, String recordType, String name,
                          List<Field> fields, String content) {
        UUID id = identity.id();
        if (newestNumber(id) != seen) {
            throw new ApiException(STALE_VERSION);
        }
        indexing.lockUnended(id);
        if (versions.lock(id, seen).isEmpty() || newestNumber(id) != seen) {
            throw new ApiException(STALE_VERSION);
        }
        int number = seen + 1;
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        try {
            versions.insert(sealed(id, owner, number, recordType, name, fields, content, now));
        } catch (VersionTaken taken) {
            throw new ApiException(STALE_VERSION);
        }
        records.retype(id, recordType).orElseThrow(() -> new IllegalStateException("artifacts.identity_missing"));
        indexing.start(id, number, now);
        return new Artifact(id, identity.campaignId(), recordType, name, fields, content, number, number,
                identity.createdAt());
    }

    private int newestNumber(UUID id) {
        return versions.latest(id).map(ArtifactVersionRow::number)
                .orElseThrow(() -> new IllegalStateException("artifacts.version_missing"));
    }

    /** A version's row: its name, fields and content encrypted, each bound to its field, the artifact and the owner. */
    private ArtifactVersionRow sealed(UUID id, UUID owner, int number, String recordType, String name,
                                      List<Field> fields, String content, Instant at) {
        return new ArtifactVersionRow(id, owner, number, recordType,
                vault.encryptText(binding(FIELD_NAME, id, owner), name),
                vault.encrypt(binding(FIELD_FIELDS, id, owner), fieldsJson(fields)),
                content == null ? null : vault.encryptText(binding(FIELD_CONTENT, id, owner), content), at);
    }

    /**
     * An artifact's identity and one of its versions, decrypted, as the newest (Story 5.1 part B: its index input and
     * its sources). The type is the version's own (Story 5.2).
     */
    static Artifact open(Vault vault, ArtifactRecords.Identity identity, ArtifactVersionRow version, UUID owner) {
        return open(vault, identity, version, version.number(), owner);
    }

    private static Artifact open(Vault vault, ArtifactRecords.Identity identity, ArtifactVersionRow version,
                                 int currentVersion, UUID owner) {
        UUID id = identity.id();
        byte[] stored = version.content();
        return new Artifact(id, identity.campaignId(), version.recordType(),
                vault.decryptText(binding(FIELD_NAME, id, owner), version.name()),
                fieldsOf(vault.decrypt(binding(FIELD_FIELDS, id, owner), version.fields())),
                stored == null ? null : vault.decryptText(binding(FIELD_CONTENT, id, owner), stored),
                version.number(), currentVersion, identity.createdAt());
    }

    /** The content trimmed, null when blank or absent, or {@code request.invalid} naming it when it is too long. */
    private static String content(String typed) {
        if (typed == null) {
            return null;
        }
        String trimmed = typed.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_CONTENT) {
            throw new ApiException(ApiProblem.forStatus(400).withParams(Map.of("field", CONTENT, "min", 0,
                    "max", MAX_CONTENT)));
        }
        return trimmed;
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

    static Binding binding(String field, UUID artifactId, UUID owner) {
        return new Binding(ENTITY, field, artifactId, Objects.requireNonNull(owner, "owner"));
    }

    /** One transaction for a write and its checks: a problem thrown inside rolls everything back. */
    private <T> T writing(Supplier<T> write) {
        OwnerContext.require();
        return new TransactionTemplate(transactions.getObject()).execute(status -> write.get());
    }

    /** One read-only transaction for the reads of one answer, so they come from one session. */
    private <T> T reading(Supplier<T> read) {
        OwnerContext.require();
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }
}
