package io.github.gmshield.artifacts.service;

import io.github.gmshield.artifacts.data.ArtifactReferenceRow;
import io.github.gmshield.artifacts.data.ArtifactReferenceStore;
import io.github.gmshield.artifacts.data.ArtifactVersionRow;
import io.github.gmshield.artifacts.data.ArtifactVersionStore;
import io.github.gmshield.artifacts.data.VersionTaken;
import io.github.gmshield.conversations.service.Conversations;
import io.github.gmshield.conversations.service.TurnReference;
import io.github.gmshield.knowledge.service.ArtifactRecords;
import io.github.gmshield.knowledge.service.RecordRelations;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.scope.service.Scopes;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
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
 *       first, is {@code request.stale_version} (409), and nothing is stored. An edit that changes nothing (the same
 *       type, name, fields and content as the newest version it names) answers that version and stores nothing.</li>
 *   <li>{@link #restore} (Story 5.2): the same write, with the type, name, fields and content of an earlier version; a
 *       version whose type the system no longer has is {@code request.invalid} naming the record type.</li>
 *   <li>{@link #versions} (Story 5.2): the artifact's versions newest first, a page at a time, with the total.</li>
 *   <li>{@link #delete} (Story 5.2 part B): in one transaction, the artifact's unended index jobs are locked (as an
 *       edit takes them), then its newest version, then its identity is marked deleting
 *       ({@link ArtifactRecords#markDeleting}) and its deletion recorded ({@link SystemDeletion#deleteArtifact}, whose
 *       handler cancels those jobs). From the commit every call here answers it as not found; the recorded, resumable
 *       deletion then removes its versions, index rows and identity, and its points through one removal job.</li>
 *   <li>{@link #saveDraft} (Story 5.4 part A): the GM's "Salvar" on a draft's save confirmation. In one transaction:
 *       the draft read ({@link Conversations#saveable}: the owner's offered draft, else {@code request.not_found},
 *       {@code conversation.turn_not_saveable} or {@code conversation.turn_saved}), the artifact created in the
 *       conversation's campaign as {@link #create} does (its limits and refusals, with no fields; the content is the
 *       draft's text unless the GM sent another), recording its conversation, its references copied from the draft's
 *       by value ({@code artifact_reference}, each value sealed again under the artifact, entity {@value #REFERENCE}),
 *       and the turn marked saved ({@link Conversations#markSaved}: a second save is {@code conversation.turn_saved},
 *       and the whole transaction rolls back, the artifact and its index job included).</li>
 * </ul>
 *
 * <p>Story 5.5 part A: {@link #override} marks the artifact as overriding one record of the system tier of its
 * campaign's scope, or points its mark to another, and {@link #removeOverride} drops the mark (safe to repeat), each in
 * one transaction through {@link RecordRelations}, which refuses a record that is no target
 * ({@code artifact.override_target}, 400) or that another live artifact of the campaign overrides
 * ({@code artifact.override_taken}, 409). {@link #targets} lists the records it can override. Every artifact read
 * carries its mark ({@link Artifact#overrides}, null for none), {@code removed} when it has no effect. The mark is not
 * versioned: an edit or a restore keeps it.
 *
 * <p>Story 5.5 part B: {@link #relate} relates the artifact to a record of any tier of its campaign's scope or to
 * another artifact of the campaign, and {@link #unrelate} removes a relation from either side (safe to repeat), each in
 * one transaction through {@link RecordRelations}, which refuses a record that is no target
 * ({@code artifact.relation_target}, 400) and a 51st relation ({@code artifact.relations_full}, 409).
 * {@link #relationTargets} lists the records it can be related to. Every artifact read carries its relations
 * ({@link Artifact#related}), from both sides; like the mark, they are not versioned.
 *
 * <p>From Story 5.4 part A every artifact read carries its conversation (null for one written by hand; from part B
 * null too unless that conversation is {@code ready}, {@link Conversations#ready}, so a deleting one is never named)
 * and its references, by number, each marked {@code removed} when read as an answer's are
 * ({@link Scopes#removedVersions} for a passage's or a record's, {@link Scopes#removedArtifacts} in its campaign for an
 * artifact's). An edit or a restore keeps them: they belong to the artifact, not to a version.
 *
 * Names, fields and content leave only in these answers; logs carry ids and counts only.
 */
@Service
public class Artifacts {

    /** The entity the vault binds a version's values to. */
    static final String ENTITY = "record_version";
    /** The entity the vault binds a saved draft's copied references to (Story 5.4 part A). */
    static final String REFERENCE = "artifact_reference";
    static final String FIELD_MATERIAL_NAME = "material_name";
    static final String FIELD_SECTION_PATH = "section_path";
    static final String FIELD_RECORD_NAME = "record_name";
    static final String FIELD_RECORD_FIELDS = "record_fields";
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
    private final RecordRelations relations;
    private final ArtifactVersionStore versions;
    private final ArtifactReferenceStore references;
    private final Library library;
    private final Vault vault;
    private final ArtifactIndexing indexing;
    private final Scopes scopes;
    private final ObjectProvider<Conversations> conversations;
    private final ObjectProvider<SystemDeletion> deletions;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final Clock clock;

    Artifacts(ArtifactRecords records, RecordRelations relations, ArtifactVersionStore versions,
              ArtifactReferenceStore references,
              Library library, Vault vault, ArtifactIndexing indexing, Scopes scopes,
              ObjectProvider<Conversations> conversations, ObjectProvider<SystemDeletion> deletions,
              ObjectProvider<PlatformTransactionManager> transactions, ObjectProvider<Clock> clock) {
        this.records = records;
        this.relations = relations;
        this.versions = versions;
        this.references = references;
        this.library = library;
        this.vault = vault;
        this.indexing = indexing;
        this.scopes = scopes;
        this.conversations = conversations;
        this.deletions = deletions;
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
     * newest version (Story 5.2; the same as {@code version} unless an earlier one is read), when the artifact was
     * written, (Story 5.4 part A) the conversation it was saved from (null for none) and its references by number
     * (empty for an artifact written by hand), (Story 5.5 part A) the record it overrides (null for none) and (part B)
     * the records it is related to, oldest first (empty for none). {@code toString} names ids and counts only.
     */
    public record Artifact(UUID id, UUID campaignId, String recordType, String name, List<Field> fields,
                           String content, int version, int currentVersion, Instant createdAt, UUID conversationId,
                           List<TurnReference> references, Overridden overrides, List<RecordRelations.Related> related) {
        public Artifact {
            fields = List.copyOf(fields);
            references = List.copyOf(references);
            related = List.copyOf(related);
        }

        /** An artifact that overrides nothing and is related to nothing. */
        public Artifact(UUID id, UUID campaignId, String recordType, String name, List<Field> fields, String content,
                        int version, int currentVersion, Instant createdAt, UUID conversationId,
                        List<TurnReference> references) {
            this(id, campaignId, recordType, name, fields, content, version, currentVersion, createdAt, conversationId,
                    references, null, List.of());
        }

        /** This artifact with no conversation (Story 5.4 part B: the one it was saved from is not ready). */
        Artifact unlinked() {
            return new Artifact(id, campaignId, recordType, name, fields, content, version, currentVersion, createdAt,
                    null, references, overrides, related);
        }

        /** This artifact with its references (Story 5.4 part A). */
        Artifact withReferences(List<TurnReference> found) {
            return new Artifact(id, campaignId, recordType, name, fields, content, version, currentVersion, createdAt,
                    conversationId, found, overrides, related);
        }

        /** This artifact with the record it overrides (Story 5.5 part A; null for none). */
        Artifact withOverride(Overridden mark) {
            return new Artifact(id, campaignId, recordType, name, fields, content, version, currentVersion, createdAt,
                    conversationId, references, mark, related);
        }

        /** This artifact with the records it is related to (Story 5.5 part B). */
        Artifact withRelated(List<RecordRelations.Related> found) {
            return new Artifact(id, campaignId, recordType, name, fields, content, version, currentVersion, createdAt,
                    conversationId, references, overrides, found);
        }

        @Override
        public String toString() {
            return "Artifact[id=" + id + ", recordType=" + recordType + ", fields=" + fields.size() + ", version="
                    + version + ", currentVersion=" + currentVersion + ", references=" + references.size()
                    + ", related=" + related.size() + "]";
        }
    }

    /**
     * The record an artifact overrides (Story 5.5 part A): its id, name and type key, its campaign's system's id, and
     * whether the mark has no effect ({@code removed}: the record is tombstoned, of a superseded version or out of the
     * campaign's scope). {@code toString} names ids only.
     */
    public record Overridden(UUID recordId, String name, String recordType, UUID systemId, boolean removed) {

        static Overridden of(RecordRelations.Mark mark) {
            return new Overridden(mark.recordId(), mark.name(), mark.recordType(), mark.systemId(), mark.removed());
        }

        @Override
        public String toString() {
            return "Overridden[recordId=" + recordId + ", recordType=" + recordType + ", removed=" + removed + "]";
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
        Optional<Artifact> created = writing(() -> created(campaignId, written, null, List.of(), owner));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("found", created.isPresent())
                .addKeyValue("artifactId", created.map(Artifact::id).orElse(null))
                .addKeyValue("fields", created.map(artifact -> artifact.fields().size()).orElse(0))
                .addKeyValue("content", created.map(artifact -> artifact.content() != null).orElse(false))
                .log("artifacts.created");
        return created;
    }

    /**
     * The GM's "Salvar" on the save confirmation of the owner's draft turn {@code turnId} (Story 5.4 part A), as the
     * class says: {@code name} and {@code recordType} as {@link #create} checks them, and {@code content} the draft's
     * text as the GM left it (null: the draft's text as stored). Answers the artifact as stored, or empty when the
     * conversation's campaign is not readable (nothing is stored then).
     */
    public Optional<Artifact> saveDraft(UUID turnId, String name, String recordType, String content) {
        UUID owner = OwnerContext.require();
        Optional<Artifact> saved = writing(() -> {
            Conversations.Saveable saveable = conversations.getObject().saveable(turnId);
            Written written = new Written(recordType, name, List.of(), content == null ? saveable.draft() : content);
            Optional<Artifact> created = created(saveable.campaignId(), written, saveable.conversationId(),
                    saveable.sources(), owner);
            created.ifPresent(artifact -> conversations.getObject().markSaved(saveable, artifact.id()));
            return created;
        });
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("turnId", turnId)
                .addKeyValue("found", saved.isPresent())
                .addKeyValue("artifactId", saved.map(Artifact::id).orElse(null))
                .addKeyValue("edited", content != null)
                .addKeyValue("references", saved.map(artifact -> artifact.references().size()).orElse(0))
                .log("artifacts.draft_saved");
        return saved;
    }

    /**
     * A new artifact of the owner's campaign {@code campaignId}, in the caller's transaction, as {@link #create} says:
     * from Story 5.4 part A saved from the conversation {@code conversationId} (null: written by hand) with these
     * references copied. Empty when the campaign is not the owner's or not readable.
     */
    private Optional<Artifact> created(UUID campaignId, Written written, UUID conversationId,
                                       List<Conversations.SavedSource> sources, UUID owner) {
        return library.campaign(campaignId).map(campaign -> {
            String recordType = records.checkedType(campaign.system().id(), written.recordType());
            ArtifactRecords.Checked checked = ArtifactRecords.checked(written.name(), written.fields().stream()
                    .map(field -> new ArtifactRecords.Field(field.name(), field.value())).toList());
            String content = content(written.content());
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            ArtifactRecords.Identity identity = records.create(campaign.id(), recordType, now, conversationId);
            List<Field> fields = checked.fields().stream().map(field -> new Field(field.name(), field.value()))
                    .toList();
            UUID id = identity.id();
            versions.insert(sealed(id, owner, FIRST_VERSION, recordType, checked.name(), fields, content, now));
            // Story 5.4 part A: the draft's references, by value, sealed again under the artifact.
            references.insert(sources.stream().map(source -> sealedReference(id, owner, source, now)).toList());
            // Story 5.1 part B: its index job, in the same transaction.
            indexing.start(id, FIRST_VERSION, now);
            return new Artifact(id, campaign.id(), recordType, checked.name(), fields, content, FIRST_VERSION,
                    FIRST_VERSION, now, conversationId, sources.isEmpty() ? List.of()
                    : referencesOf(id, campaign.id(), owner));
        });
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
                    return shown.map(version -> linked(open(vault, identity, version, newest.number(), owner)
                            .withReferences(referencesOf(identity.id(), identity.campaignId(), owner))));
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
                records.find(campaign.id(), artifactId).flatMap(identity -> {
                    String recordType = records.checkedType(campaign.system().id(), written.recordType());
                    ArtifactRecords.Checked checked = ArtifactRecords.checked(written.name(), written.fields()
                            .stream().map(field -> new ArtifactRecords.Field(field.name(), field.value())).toList());
                    List<Field> fields = checked.fields().stream().map(field -> new Field(field.name(), field.value()))
                            .toList();
                    String content = content(written.content());
                    ArtifactVersionRow newest = versions.latest(identity.id())
                            .orElseThrow(() -> new IllegalStateException("artifacts.version_missing"));
                    if (newest.number() == version) {
                        // Nothing changed: the newest version stays, with no new version and no index job.
                        Artifact current = open(vault, identity, newest, owner);
                        if (current.recordType().equals(recordType) && current.name().equals(checked.name())
                                && current.fields().equals(fields) && Objects.equals(current.content(), content)) {
                            return Optional.of(linked(current.withReferences(referencesOf(identity.id(),
                                    identity.campaignId(), owner))));
                        }
                    }
                    return next(identity, owner, version, recordType, checked.name(), fields, content);
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
                        .flatMap(earlier -> {
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
     * Starts the deletion of the owner's artifact {@code artifactId} of the campaign {@code campaignId} (Story 5.2 part
     * B), as the class says; true when it was recorded now. False (nothing changes) when the campaign is not the
     * owner's or not readable, or the artifact is not the campaign's, a deleting one included.
     */
    public boolean delete(UUID campaignId, UUID artifactId) {
        UUID owner = OwnerContext.require();
        boolean recorded = Boolean.TRUE.equals(writing(() -> library.campaign(campaignId)
                .flatMap(campaign -> records.find(campaign.id(), artifactId))
                .map(identity -> {
                    UUID id = identity.id();
                    // The order an edit takes: its unended index jobs, then its newest version, then its identity.
                    indexing.lockUnended(id);
                    Optional<ArtifactVersionRow> newest = versions.latest(id);
                    // A second delete that waited on the first's locks may find its versions gone: not found.
                    if (newest.isEmpty()) {
                        return false;
                    }
                    versions.lock(id, newest.get().number());
                    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                    // A second delete that waited on those locks finds it deleting: nothing changes.
                    if (!records.markDeleting(id, now)) {
                        return false;
                    }
                    if (!deletions.getObject().deleteArtifact(id)) {
                        // Marked but not recorded, no pass would ever remove it: the mark rolls back.
                        throw new IllegalStateException("artifacts.deletion_not_recorded");
                    }
                    return true;
                }).orElse(false)));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("artifactId", artifactId).addKeyValue("recorded", recorded).log("artifacts.delete");
        return recorded;
    }

    /**
     * The GM marks the owner's artifact {@code artifactId} of the campaign {@code campaignId} as overriding the record
     * {@code recordId} (Story 5.5 part A), as the class says, replacing its mark if it had one. Answers the artifact with
     * its newest version and its mark, or empty when the campaign is not the owner's or not readable, or the artifact is
     * not the campaign's (nothing changes then).
     */
    public Optional<Artifact> override(UUID campaignId, UUID artifactId, UUID recordId) {
        UUID owner = OwnerContext.require();
        Optional<Artifact> marked = writing(() -> library.campaign(campaignId)
                .flatMap(campaign -> records.find(campaign.id(), artifactId))
                .flatMap(identity -> relations.override(identity.campaignId(), identity.id(), recordId)
                        .map(mark -> newest(identity, owner))));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("artifactId", artifactId).addKeyValue("recordId", recordId)
                .addKeyValue("found", marked.isPresent()).log("artifacts.override_set");
        return marked;
    }

    /**
     * The GM removes the owner's artifact {@code artifactId}'s mark (Story 5.5 part A), whether it had one or not; true
     * when the artifact is the campaign's, false when the campaign is not the owner's or not readable, or the artifact is
     * not the campaign's (nothing changes then).
     */
    public boolean removeOverride(UUID campaignId, UUID artifactId) {
        UUID owner = OwnerContext.require();
        boolean found = Boolean.TRUE.equals(writing(() -> library.campaign(campaignId)
                .flatMap(campaign -> records.find(campaign.id(), artifactId))
                .map(identity -> relations.removeOverride(identity.campaignId(), identity.id())).orElse(false)));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("artifactId", artifactId).addKeyValue("found", found).log("artifacts.override_removed");
        return found;
    }

    /**
     * The records an artifact of the owner's campaign {@code campaignId} can override (Story 5.5 part A,
     * {@link RecordRelations#targets}): of the type key {@code recordType}, whose name contains {@code query} (case and
     * accents ignored; null or blank for all), the {@code offset} first skipped (0 to {@value #MAX_OFFSET}), at most
     * {@code limit} (1 to {@value #MAX_LIMIT}). Empty when the campaign is not the owner's or not readable.
     */
    public Optional<RecordRelations.TargetPage> targets(UUID campaignId, String recordType, String query, int offset,
                                                        int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("artifacts.limit_invalid");
        }
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new IllegalArgumentException("artifacts.offset_invalid");
        }
        return relations.targets(campaignId, recordType, query, offset, limit);
    }

    /**
     * The GM relates the owner's artifact {@code artifactId} of the campaign {@code campaignId} to the record
     * {@code recordId} (Story 5.5 part B), as the class says; a relation that exists already, in either direction, is
     * kept as it is. Answers the artifact with its newest version and its relations, or empty when the campaign is not
     * the owner's or not readable, or the artifact is not the campaign's (nothing changes then).
     */
    public Optional<Artifact> relate(UUID campaignId, UUID artifactId, UUID recordId) {
        UUID owner = OwnerContext.require();
        Optional<Artifact> related = writing(() -> library.campaign(campaignId)
                .flatMap(campaign -> records.find(campaign.id(), artifactId))
                .filter(identity -> relations.relate(identity.campaignId(), identity.id(), recordId))
                .map(identity -> newest(identity, owner)));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("artifactId", artifactId).addKeyValue("recordId", recordId)
                .addKeyValue("found", related.isPresent()).log("artifacts.relation_set");
        return related;
    }

    /**
     * The GM removes the relation between the owner's artifact {@code artifactId} and the record {@code recordId}
     * (Story 5.5 part B), whichever side made it, whether there was one or not; true when the artifact is the
     * campaign's, false when the campaign is not the owner's or not readable, or the artifact is not the campaign's
     * (nothing changes then).
     */
    public boolean unrelate(UUID campaignId, UUID artifactId, UUID recordId) {
        UUID owner = OwnerContext.require();
        boolean found = Boolean.TRUE.equals(writing(() -> library.campaign(campaignId)
                .flatMap(campaign -> records.find(campaign.id(), artifactId))
                .map(identity -> relations.unrelate(identity.campaignId(), identity.id(), recordId)).orElse(false)));
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("artifactId", artifactId).addKeyValue("recordId", recordId).addKeyValue("found", found)
                .log("artifacts.relation_removed");
        return found;
    }

    /**
     * The records the owner's artifact {@code artifactId} of the campaign {@code campaignId} can be related to (Story
     * 5.5 part B, {@link RecordRelations#relationTargets}): of the type key {@code recordType}, whose name contains
     * {@code query} (case and accents ignored; null or blank for all), the {@code offset} first skipped (0 to
     * {@value #MAX_OFFSET}), at most {@code limit} (1 to {@value #MAX_LIMIT}). Empty when the campaign is not the
     * owner's or not readable, or the artifact is not a live one of it.
     */
    public Optional<RecordRelations.RelationTargetPage> relationTargets(UUID campaignId, UUID artifactId,
                                                                        String recordType, String query, int offset,
                                                                        int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("artifacts.limit_invalid");
        }
        if (offset < 0 || offset > MAX_OFFSET) {
            throw new IllegalArgumentException("artifacts.offset_invalid");
        }
        return relations.relationTargets(campaignId, artifactId, recordType, query, offset, limit);
    }

    /** The artifact {@code identity} with its newest version, as {@link #get} answers it, in the caller's transaction. */
    private Artifact newest(ArtifactRecords.Identity identity, UUID owner) {
        ArtifactVersionRow newest = versions.latest(identity.id())
                .orElseThrow(() -> new IllegalStateException("artifacts.version_missing"));
        return linked(open(vault, identity, newest, newest.number(), owner)
                .withReferences(referencesOf(identity.id(), identity.campaignId(), owner)));
    }

    /**
     * The next version of the artifact {@code identity} (Story 5.2), in the caller's transaction: {@code seen} must be
     * its newest number, read again once that version is locked (behind its unended index jobs' locks, as
     * {@link ArtifactIndexing#lockUnended} says); a number taken first by another write is stale too. Then the version,
     * the identity's type and the version's index job. Empty (Story 5.2 part B) when the artifact turned deleting while
     * the write waited on those locks: nothing is stored then.
     */
    private Optional<Artifact> next(ArtifactRecords.Identity identity, UUID owner, int seen, String recordType,
                                    String name, List<Field> fields, String content) {
        UUID id = identity.id();
        if (newestNumber(id) != seen) {
            throw new ApiException(STALE_VERSION);
        }
        indexing.lockUnended(id);
        Optional<ArtifactVersionRow> locked = versions.lock(id, seen);
        // Story 5.2 part B: a delete that committed while this write waited (its pass may have removed the versions
        // too) makes the artifact not found, never stale.
        if (records.find(identity.campaignId(), id).isEmpty()) {
            return Optional.empty();
        }
        if (locked.isEmpty() || newestNumber(id) != seen) {
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
        return Optional.of(linked(new Artifact(id, identity.campaignId(), recordType, name, fields, content, number,
                number, identity.createdAt(), identity.conversationId(), referencesOf(id, identity.campaignId(),
                owner))));
    }

    /**
     * The artifact as an answer shows it (Story 5.4 part B): its conversation only while that conversation is
     * {@code ready} ({@link Conversations#ready}), so a deleting one's id never leaves; null otherwise. From Story 5.5
     * part A with its mark ({@link RecordRelations#mark}), null for none, and from part B with its relations
     * ({@link RecordRelations#related}).
     */
    private Artifact linked(Artifact artifact) {
        Artifact marked = artifact.withOverride(relations.mark(artifact.campaignId(), artifact.id())
                .map(Overridden::of).orElse(null)).withRelated(relations.related(artifact.campaignId(), artifact.id()));
        UUID conversationId = marked.conversationId();
        if (conversationId == null || conversations.getObject().ready(Set.of(conversationId))
                .contains(conversationId)) {
            return marked;
        }
        return marked.unlinked();
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
                version.number(), currentVersion, identity.createdAt(), identity.conversationId(), List.of());
    }

    /**
     * A draft's reference copied by value under the artifact {@code id} (Story 5.4 part A): each stored value sealed
     * again, bound to {@value #REFERENCE}, its field with the reference's number, the artifact and the owner.
     */
    private ArtifactReferenceRow sealedReference(UUID id, UUID owner, Conversations.SavedSource source, Instant at) {
        TurnReference reference = source.reference();
        int number = reference.number();
        TurnReference.Cited cited = reference.record();
        byte[] recordName = cited == null ? null
                : vault.encryptText(referenceBinding(FIELD_RECORD_NAME, number, id, owner), cited.name());
        byte[] recordFields = cited == null ? null : vault.encrypt(referenceBinding(FIELD_RECORD_FIELDS, number, id,
                owner), fieldsJson(cited.fields().stream().map(field -> new Field(field.name(), field.value()))
                .toList()));
        if (TurnReference.ARTIFACT.equals(reference.kind())) {
            // An artifact's reference always names the artifact it cites (Sonar S2259 cannot see that).
            TurnReference.Cited artifact = Objects.requireNonNull(cited, "artifacts.reference_without_artifact");
            return new ArtifactReferenceRow(id, owner, number, null, null, null, null, null, null, reference.tier(),
                    null, artifact.type(), recordName, recordFields, artifact.id(), artifact.artifactVersion(), at);
        }
        ArrayNode path = JsonNodeFactory.instance.arrayNode();
        reference.sectionPath().forEach(path::add);
        return new ArtifactReferenceRow(id, owner, number, source.versionId(), source.materialId(), reference.page(),
                reference.passageIds(),
                vault.encryptText(referenceBinding(FIELD_MATERIAL_NAME, number, id, owner), reference.materialName()),
                vault.encrypt(referenceBinding(FIELD_SECTION_PATH, number, id, owner), JSON.writeValueAsBytes(path)),
                reference.tier(), cited == null ? null : cited.id(), cited == null ? null : cited.type(), recordName,
                recordFields, null, null, at);
    }

    /**
     * The owner's artifact {@code id}'s references (Story 5.4 part A), decrypted, by number, each {@code removed} as of
     * now as an answer's: a passage's or a record's when its version is removed, an artifact's when it is not live in
     * the campaign {@code campaignId}. Empty for one written by hand. Runs in the caller's transaction.
     */
    private List<TurnReference> referencesOf(UUID id, UUID campaignId, UUID owner) {
        List<ArtifactReferenceRow> rows = references.of(id);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<UUID> removedVersions = scopes.removedVersions(rows.stream().map(ArtifactReferenceRow::versionId)
                .filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet()));
        Set<UUID> removedArtifacts = scopes.removedArtifacts(campaignId, rows.stream()
                .filter(ArtifactReferenceRow::artifact).map(ArtifactReferenceRow::artifactId)
                .collect(Collectors.toUnmodifiableSet()));
        List<TurnReference> found = new ArrayList<>(rows.size());
        for (ArtifactReferenceRow row : rows) {
            int number = row.number();
            UUID citedId = row.artifact() ? row.artifactId() : row.citedRecordId();
            TurnReference.Cited cited = citedId == null ? null : new TurnReference.Cited(citedId, row.recordType(),
                    vault.decryptText(referenceBinding(FIELD_RECORD_NAME, number, id, owner), row.recordName()),
                    fieldsOf(vault.decrypt(referenceBinding(FIELD_RECORD_FIELDS, number, id, owner),
                            row.recordFields())).stream().map(field -> new TurnReference.Field(field.name(),
                            field.value())).toList(), row.artifactVersion());
            if (row.artifact()) {
                found.add(new TurnReference(number, null, List.of(), 0, List.of(),
                        removedArtifacts.contains(row.artifactId()), row.tier(), cited));
                continue;
            }
            found.add(new TurnReference(number,
                    vault.decryptText(referenceBinding(FIELD_MATERIAL_NAME, number, id, owner), row.materialName()),
                    pathOf(vault.decrypt(referenceBinding(FIELD_SECTION_PATH, number, id, owner), row.sectionPath())),
                    row.page(), row.passageIds(), removedVersions.contains(row.versionId()), row.tier(), cited));
        }
        return List.copyOf(found);
    }

    private static List<String> pathOf(byte[] json) {
        List<String> path = new ArrayList<>();
        try {
            for (JsonNode part : JSON.readTree(json)) {
                path.add(part.asString());
            }
        } finally {
            Arrays.fill(json, (byte) 0);
        }
        return List.copyOf(path);
    }

    static Binding referenceBinding(String field, int number, UUID artifactId, UUID owner) {
        return new Binding(REFERENCE, field + ":" + number, artifactId, Objects.requireNonNull(owner, "owner"));
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
