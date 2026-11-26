package io.github.gmshield.artifacts.service;

import io.github.gmshield.artifacts.data.ArtifactVersionRow;
import io.github.gmshield.artifacts.data.ArtifactVersionStore;
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
 *       written: nothing is calculated.</li>
 *   <li>{@link #list}: the campaign's artifacts newest first (by id, a UUIDv7), of one type key or all, a page at a time
 *       (after a cursor, or at an offset), with the total and the type keys in use with their counts.</li>
 *   <li>{@link #get}: one artifact with its newest version.</li>
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
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(Artifacts.class);

    private final ArtifactRecords records;
    private final ArtifactVersionStore versions;
    private final Library library;
    private final Vault vault;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final Clock clock;

    Artifacts(ArtifactRecords records, ArtifactVersionStore versions, Library library, Vault vault,
              ObjectProvider<PlatformTransactionManager> transactions, ObjectProvider<Clock> clock) {
        this.records = records;
        this.versions = versions;
        this.library = library;
        this.vault = vault;
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
     * One artifact with its newest version: its id, its campaign, its type key, its name, its fields, its content (null
     * for none), the version's number and when the artifact was written. {@code toString} names ids and counts only.
     */
    public record Artifact(UUID id, UUID campaignId, String recordType, String name, List<Field> fields,
                           String content, int version, Instant createdAt) {
        public Artifact {
            fields = List.copyOf(fields);
        }

        @Override
        public String toString() {
            return "Artifact[id=" + id + ", recordType=" + recordType + ", fields=" + fields.size() + ", version="
                    + version + "]";
        }
    }

    /** An artifact as a list shows it: its id, type key, name, version and when it was written. */
    public record Summary(UUID id, String recordType, String name, int version, Instant createdAt) {
        @Override
        public String toString() {
            return "Summary[id=" + id + ", recordType=" + recordType + "]";
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
            versions.insert(new ArtifactVersionRow(id, owner, FIRST_VERSION,
                    vault.encryptText(binding(FIELD_NAME, id, owner), checked.name()),
                    vault.encrypt(binding(FIELD_FIELDS, id, owner), fieldsJson(fields)),
                    content == null ? null : vault.encryptText(binding(FIELD_CONTENT, id, owner), content), now));
            return new Artifact(id, campaign.id(), recordType, checked.name(), fields, content, FIRST_VERSION, now);
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
        UUID owner = OwnerContext.require();
        Optional<Artifact> found = reading(() -> library.campaign(campaignId)
                .flatMap(campaign -> records.find(campaign.id(), artifactId))
                .map(identity -> {
                    ArtifactVersionRow version = versions.latest(identity.id())
                            .orElseThrow(() -> new IllegalStateException("artifacts.version_missing"));
                    return open(identity, version, owner);
                }));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("artifactId", artifactId).addKeyValue("found", found.isPresent())
                .log("artifacts.read");
        return found;
    }

    private Artifact open(ArtifactRecords.Identity identity, ArtifactVersionRow version, UUID owner) {
        UUID id = identity.id();
        byte[] stored = version.content();
        return new Artifact(id, identity.campaignId(), identity.recordType(),
                vault.decryptText(binding(FIELD_NAME, id, owner), version.name()),
                fieldsOf(vault.decrypt(binding(FIELD_FIELDS, id, owner), version.fields())),
                stored == null ? null : vault.decryptText(binding(FIELD_CONTENT, id, owner), stored),
                version.number(), identity.createdAt());
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
