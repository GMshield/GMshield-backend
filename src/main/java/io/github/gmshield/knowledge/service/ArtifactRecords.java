package io.github.gmshield.knowledge.service;

import io.github.gmshield.knowledge.data.ArtifactRow;
import io.github.gmshield.knowledge.data.KnowledgeStore;
import io.github.gmshield.knowledge.data.RecordStore;
import io.github.gmshield.materials.service.ExtractedRecords;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The identity of the artifacts (Story 5.1), for the {@code artifacts} module: an artifact is a record of its campaign,
 * a row of {@code record} with the campaign instead of a material version, its id minted here and its record type's key
 * plain. This class owns that row (it stores, lists, finds, retypes and, with its campaign, deletes it); the artifact's
 * content is the {@code artifacts} module's, in its versions. Everything runs as the owner of the work
 * ({@link OwnerContext}), in the caller's transaction, and within one campaign: another campaign's artifact, another
 * owner's or an unknown id is never found. No read of a system's records reaches an artifact ({@code RecordStore}).
 *
 * <p>The checks of what the GM wrote keep the limits of an extracted record ({@link ExtractedRecords#MAX_NAME} and the
 * others, shared with the GM's correction since Story 4.5): a name of 1 to 200 code points and at most 64 fields, each
 * with a name of 1 to 200 and a value of 1 to 8,000, all trimmed. The type is the key of one of the campaign's system's
 * record types, core or approved ({@link CoreRecordTypes} are in every system, {@link Ontology} adds the approved ones).
 * A value outside its limits is {@code request.invalid} (400) naming its field in {@code params.field}
 * ({@value #NAME}, {@value #RECORD_TYPE}, {@value #FIELDS}, {@value #FIELD_NAME} or {@value #FIELD_VALUE}, the last two
 * with the field's {@code index} from 0), with its limits; no param carries what was written.
 *
 * <p>Story 5.2 part B: the GM's delete marks the row deleting ({@link #markDeleting}) until the artifact's deletion
 * removes it. Every read here leaves a deleting artifact out (its page, an edit, the lists and counts, the campaign
 * tier, the backfill and retries, the type-in-use guard), except {@link #allIdsOf} and {@link #allOf}, which a
 * deletion's own removal reads.
 */
@Service
public class ArtifactRecords {

    /** The problem's {@code field}: the artifact's name. */
    public static final String NAME = "name";
    /** The problem's {@code field}: the artifact's record type. */
    public static final String RECORD_TYPE = "recordType";
    /** The problem's {@code field}: the artifact's fields as a whole (too many). */
    public static final String FIELDS = "fields";
    /** The problem's {@code field}: one field's name, at {@code index}. */
    public static final String FIELD_NAME = "fieldName";
    /** The problem's {@code field}: one field's value, at {@code index}. */
    public static final String FIELD_VALUE = "fieldValue";
    /** A record type key's text (V40); anything else is no key of any system. */
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,31}");

    private final RecordStore store;
    private final KnowledgeStore types;

    ArtifactRecords(RecordStore store, KnowledgeStore types) {
        this.store = store;
        this.types = types;
    }

    /** A field the GM wrote: a name and a value. {@code toString} names neither. */
    public record Field(String name, String value) {
        @Override
        public String toString() {
            return "Field";
        }
    }

    /** What the GM wrote, trimmed and within its limits. {@code toString} names the count only. */
    public record Checked(String name, List<Field> fields) {
        public Checked {
            fields = List.copyOf(fields);
        }

        @Override
        public String toString() {
            return "Checked[fields=" + fields.size() + "]";
        }
    }

    /** An artifact's identity: its id, its campaign, its record type's key and when it was written. */
    public record Identity(UUID id, UUID campaignId, String recordType, Instant createdAt) {
    }

    /** A record type key in use in a campaign's artifacts, and how many of them have it. */
    public record TypeCount(String key, long count) {
    }

    /**
     * The name and fields trimmed, or {@code request.invalid} naming the first one outside its limits, as the class
     * says. A null name or field part counts as blank.
     */
    public static Checked checked(String name, List<Field> fields) {
        String trimmed = name == null ? "" : name.strip();
        if (!within(trimmed, ExtractedRecords.MAX_NAME)) {
            throw invalid(Map.of("field", NAME, "min", 1, "max", ExtractedRecords.MAX_NAME));
        }
        if (fields.size() > ExtractedRecords.MAX_FIELDS) {
            throw invalid(Map.of("field", FIELDS, "max", ExtractedRecords.MAX_FIELDS));
        }
        List<Field> kept = new ArrayList<>(fields.size());
        for (int index = 0; index < fields.size(); index++) {
            Field field = fields.get(index);
            String fieldName = field == null || field.name() == null ? "" : field.name().strip();
            String value = field == null || field.value() == null ? "" : field.value().strip();
            if (!within(fieldName, ExtractedRecords.MAX_FIELD_NAME)) {
                throw invalid(Map.of("field", FIELD_NAME, "index", index, "min", 1,
                        "max", ExtractedRecords.MAX_FIELD_NAME));
            }
            if (!within(value, ExtractedRecords.MAX_FIELD_VALUE)) {
                throw invalid(Map.of("field", FIELD_VALUE, "index", index, "min", 1,
                        "max", ExtractedRecords.MAX_FIELD_VALUE));
            }
            kept.add(new Field(fieldName, value));
        }
        return new Checked(trimmed, kept);
    }

    /**
     * Whether {@code recordType} is the key of one of the owner's system {@code systemId}'s record types, core or
     * approved; else {@code request.invalid} naming the record type.
     */
    public String checkedType(UUID systemId, String recordType) {
        OwnerContext.require();
        if (recordType == null || !KEY.matcher(recordType).matches()
                || types.findByKey(systemId, recordType).isEmpty()) {
            throw invalid(Map.of("field", RECORD_TYPE));
        }
        return recordType;
    }

    /**
     * Stores a new artifact of the owner's campaign {@code campaignId} (checked by the caller), of the type key
     * {@code recordType}, which {@link #checkedType} answered for the campaign's system in the same transaction, at
     * {@code at}; in the caller's transaction, which must exist. Answers its identity, with its new id.
     */
    public Identity create(UUID campaignId, String recordType, Instant at) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("knowledge.transaction_required");
        }
        UUID owner = OwnerContext.require();
        ArtifactRow row = new ArtifactRow(Ids.newId(), owner, campaignId, recordType, at);
        store.insertArtifact(row);
        return identity(row);
    }

    /**
     * Gives the owner's artifact {@code id} the type key {@code recordType} (Story 5.2: an edit's or a restore's newest
     * version has it, and the identity always mirrors the newest version), which {@link #checkedType} answered for the
     * artifact's campaign's system in the same transaction; with the row locked, in the caller's transaction, which must
     * exist. Answers the identity as changed, or empty for any id that is not one of the owner's artifacts. It never
     * goes through a record's correction.
     */
    public Optional<Identity> retype(UUID id, String recordType) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("knowledge.transaction_required");
        }
        OwnerContext.require();
        return store.retypeArtifact(id, recordType).map(ArtifactRecords::identity);
    }

    /**
     * Marks the owner's live artifact {@code id} deleting at {@code at} (Story 5.2 part B), with its row locked, in the
     * caller's transaction, which must exist: from the commit every read here but {@link #allIdsOf} and {@link #allOf}
     * leaves it out. Answers whether it was marked now: false for a deleting one or any id that is not one of the
     * owner's artifacts.
     */
    public boolean markDeleting(UUID id, Instant at) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("knowledge.transaction_required");
        }
        OwnerContext.require();
        return store.markArtifactDeleting(id, at);
    }

    /** The owner's live artifact {@code id} of the campaign {@code campaignId}; empty for any other id. */
    public Optional<Identity> find(UUID campaignId, UUID id) {
        OwnerContext.require();
        return store.findArtifact(campaignId, id).map(ArtifactRecords::identity);
    }

    /**
     * The owner's live artifacts with these ids, of whichever campaign, by id (Story 5.1 part B); an extracted record's
     * id, an unknown one, another owner's or (Story 5.2 part B) a deleting one is left out.
     */
    public List<Identity> findAll(Collection<UUID> ids) {
        OwnerContext.require();
        return ids.isEmpty() ? List.of() : store.findArtifacts(ids, RecordStore.Reach.LIVE).stream()
                .map(ArtifactRecords::identity).toList();
    }

    /**
     * {@link #findAll}, deleting artifacts included (Story 5.2 part B): what an artifact's deletion still has to
     * remove.
     */
    public List<Identity> allOf(Collection<UUID> ids) {
        OwnerContext.require();
        return ids.isEmpty() ? List.of() : store.findArtifacts(ids, RecordStore.Reach.ALL).stream()
                .map(ArtifactRecords::identity).toList();
    }

    /**
     * The owner's artifacts of the campaign {@code campaignId}, newest first: of the type key {@code recordType} only
     * when it is not null, only those older than {@code before} when it is not null, the first {@code offset} skipped,
     * at most {@code limit}.
     */
    public List<Identity> list(UUID campaignId, String recordType, UUID before, int offset, int limit) {
        OwnerContext.require();
        return store.artifacts(campaignId, recordType, before, offset, limit).stream().map(ArtifactRecords::identity)
                .toList();
    }

    /** How many artifacts the owner's campaign has, of the type key {@code recordType} when it is not null. */
    public long count(UUID campaignId, String recordType) {
        OwnerContext.require();
        return store.artifactCount(campaignId, recordType);
    }

    /** The type keys in use in the owner's campaign's artifacts, by key, each with how many artifacts have it. */
    public List<TypeCount> types(UUID campaignId) {
        OwnerContext.require();
        return store.artifactTypes(campaignId).entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new TypeCount(entry.getKey(), entry.getValue())).toList();
    }

    /**
     * How many live artifacts the owner's campaigns with these ids hold (Story 5.1: the deletion dialogs name them;
     * from Story 5.2 part B a deleting one is not counted).
     */
    public long count(Collection<UUID> campaignIds) {
        OwnerContext.require();
        return store.artifactsIn(campaignIds);
    }

    /**
     * The ids of every live artifact of the owner's campaigns with these ids (the campaign tier of a question's scope);
     * a deleting one (Story 5.2 part B) is left out.
     */
    public List<UUID> idsOf(Collection<UUID> campaignIds) {
        OwnerContext.require();
        return store.artifactIdsOf(campaignIds, RecordStore.Reach.LIVE);
    }

    /** {@link #idsOf}, deleting artifacts included (Story 5.2 part B): what a campaign's deletion removes. */
    public List<UUID> allIdsOf(Collection<UUID> campaignIds) {
        OwnerContext.require();
        return store.artifactIdsOf(campaignIds, RecordStore.Reach.ALL);
    }

    /**
     * Deletes the owner's artifact identities with these ids, in the caller's transaction, which must exist (a
     * campaign's deletion, once the artifacts' versions are gone); answers how many went.
     */
    public int delete(Collection<UUID> ids) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("knowledge.transaction_required");
        }
        OwnerContext.require();
        return store.deleteArtifacts(ids);
    }

    private static Identity identity(ArtifactRow row) {
        return new Identity(row.id(), row.campaignId(), row.type(), row.createdAt());
    }

    /** Whether a trimmed text has 1 to {@code max} code points. */
    private static boolean within(String text, int max) {
        int points = text.codePointCount(0, text.length());
        return points >= 1 && points <= max;
    }

    private static ApiException invalid(Map<String, Object> params) {
        return new ApiException(ApiProblem.forStatus(400).withParams(params));
    }
}
