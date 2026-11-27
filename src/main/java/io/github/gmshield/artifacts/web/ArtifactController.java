package io.github.gmshield.artifacts.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.gmshield.artifacts.service.ArtifactExports;
import io.github.gmshield.artifacts.service.Artifacts;
import io.github.gmshield.conversations.service.TurnReference;
import io.github.gmshield.knowledge.service.RecordRelations;
import io.github.gmshield.library.service.Language;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A campaign's artifacts (Story 5.1 part A, contract 1.42.0): a new artifact written by hand (201), the campaign's
 * artifacts a page at a time, filtered by record type, and one artifact with its newest version. The owner is the
 * session's account, set by the request edge ({@code accounts.web}) before any of these runs; nothing here takes an
 * owner from the request. Another owner's campaign or artifact, an artifact of another campaign, an unknown id, a
 * malformed id and a campaign being deleted are the same {@code request.not_found} (404). A value outside its limits is
 * {@code request.invalid} (400) with {@code params.field} naming it; a malformed body, limit, offset, cursor or type is
 * {@code request.invalid} (400) without one. Every answer is {@code no-store}.
 *
 * <p>Story 5.2 part A (contract 1.44.0): an edit ({@code PATCH}) and a restore of an earlier version each store the next
 * version and name the newest version the GM saw; a stale one is {@code request.stale_version} (409). The versions list
 * newest first, and one version opens by its number (a reference that cited it). A version number that is malformed or
 * that the artifact does not have is {@code request.not_found} too.
 *
 * <p>Story 5.2 part B (contract 1.45.0): a {@code DELETE} starts the artifact's recorded deletion (202, no body,
 * {@code no-store}). From then on it is not found on every route; a deleting artifact is {@code request.not_found} to
 * a second delete too.
 *
 * <p>Story 5.4 part A (contract 1.48.0): every artifact answer carries {@code conversationId} (the conversation a saved
 * draft came from; null for one written by hand) and {@code references} (a saved draft's sources, copied by value, in
 * the shape of a turn's references, each {@code removed} as of the answer; empty for one written by hand). The save
 * itself is {@link DraftSaveController}. From Story 5.4 part B (contract 1.49.0) {@code conversationId} is null as
 * soon as that conversation's deletion starts.
 *
 * <p>Story 5.5 part A (contract 1.50.0): a {@code PUT} on {@code .../override} marks the artifact as overriding one
 * record of the system tier of its campaign's scope (or points its mark to another), and a {@code DELETE} removes the
 * mark (204, safe to repeat); both apply from the next question. A record that is no target is
 * {@code artifact.override_target} (400), one another artifact of the campaign overrides {@code artifact.override_taken}
 * (409, {@code params.artifactId} naming it). {@code .../override-targets} lists the records an artifact of the
 * campaign can override, alphabetical, filtered by type and name. Every artifact answer carries {@code overrides}, null
 * for none.
 *
 * <p>Story 5.5 part B (contract 1.51.0): a {@code POST} on {@code .../relations} relates the artifact to a record of any
 * tier of its campaign's scope or to another artifact of the campaign, and a {@code DELETE} on
 * {@code .../relations/{recordId}} removes the relation from either side (204, safe to repeat). A relation has no
 * direction and changes no answer. A record that is no target is {@code artifact.relation_target} (400), a 51st relation
 * {@code artifact.relations_full} (409). {@code .../relation-targets} lists the records the artifact can be related to,
 * alphabetical, filtered by type and name, each with its tier. Every artifact answer carries {@code related}, from both
 * sides, oldest first.
 *
 * <p>Story 5.6 part A (contract 1.52.0): a {@code PUT} on {@code .../sharing} sets the whole set of the account's other
 * campaigns of the artifact's system it is shared with (empty unshares it from all), from the next question. A campaign
 * that is no target is {@code artifact.share_target} (400), and nothing is stored. Every artifact answer carries
 * {@code sharedWith} (by name; empty in a read from another campaign) and {@code sharedFrom} (null in its own campaign).
 * {@code getArtifact} and {@code getArtifactVersion} also answer, read-only, a live artifact shared into the path
 * campaign; every other route answers it as not found there.
 *
 * <p>Story 5.6 part B (contract 1.53.0): {@code listArtifacts} also lists the live artifacts shared into the campaign,
 * whatever its tiers, in the same order and counts, each with {@code sharedFrom} naming its own campaign (null for the
 * campaign's own).
 *
 * <p>Story 5.7 part A (contract 1.54.0): {@code GET .../artifacts/export?ids=a,b&format=pdf|txt|markdown|jpg&language=
 * pt-BR|en} answers one file of 1 to 50 distinct artifacts of the campaign, in the order given, each read as
 * {@code getArtifact} reads it (the campaign's own and those shared into it, at their newest version), built in memory
 * ({@link ArtifactExports}): PDF, TXT or Markdown as one file, JPG as one image for one artifact or a zip of one image
 * each. Any unreadable id is {@code request.not_found} (404) naming none; a duplicate id, a count outside 1 to 50, or an
 * unknown format or language is {@code request.invalid} (400); a PDF or JPG export of more than 200 pages is
 * {@code artifact.export_too_large} (400), and one while 2 others render {@code artifact.export_busy} (503). The
 * answer is an attachment (RFC 6266 {@code filename*}), {@code no-store} and {@code nosniff}; a refusal stays problem
 * JSON (no {@code produces}).
 *
 * <p>Story 6.2 part A (contract 1.57.0): every artifact answer and list row carries {@code shieldId}, the Shield holding
 * a note written there ({@link ShieldNoteController}); null for every other artifact. An edit or a restore of such a
 * note to another type is {@code request.note_type_fixed} (409), and nothing is stored.
 */
@RestController
@Tag(name = "artifacts")
class ArtifactController {

    static final String ARTIFACTS = "/api/v1/campaigns/{campaignId}/artifacts";
    static final String ARTIFACT = "/api/v1/campaigns/{campaignId}/artifacts/{artifactId}";
    /** Story 5.7: an export of the campaign's artifacts; a literal segment, so it is never read as an artifact's id. */
    static final String EXPORT = ARTIFACTS + "/export";
    static final String VERSIONS = ARTIFACT + "/versions";
    static final String VERSION = VERSIONS + "/{number}";
    static final String RESTORE = VERSION + "/restore";
    static final String OVERRIDE = ARTIFACT + "/override";
    static final String OVERRIDE_TARGETS = "/api/v1/campaigns/{campaignId}/override-targets";
    static final String RELATIONS = ARTIFACT + "/relations";
    static final String RELATION = RELATIONS + "/{recordId}";
    static final String RELATION_TARGETS = ARTIFACT + "/relation-targets";
    static final String SHARING = ARTIFACT + "/sharing";
    /** A version's number: 1 to 999,999,999, no leading zero. */
    private static final Pattern NUMBER = Pattern.compile("[1-9][0-9]{0,8}");
    /** A UUID in its canonical text form; anything else is an id that exists nowhere. */
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Pattern LIMIT = Pattern.compile("[1-9][0-9]?");
    /** A page's position: 0, or up to six digits with no leading zero. */
    private static final Pattern OFFSET_TEXT = Pattern.compile("0|[1-9][0-9]{0,5}");
    /** A record type key's text: a lower-case letter, then up to 31 lower-case letters, digits or underscores. */
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    private static final String SESSION = "X-Session-Token";
    private static final String SESSION_SUMMARY = " session.required (401) without a session.";
    private static final String ID_EXAMPLE = "0190a0b0-0000-7000-8000-000000000030";
    private static final String LIMITS = " request.invalid (400) with params.field naming the value outside its limits"
            + " (and its min and max): recordType (not a key of the campaign's system's record types, core or"
            + " approved), name (1 to 200 code points after trimming), fields (at most 64), fieldName or fieldValue"
            + " (with index, from 0: a name of 1 to 200, a value of 1 to 8,000, after trimming) or content (at most"
            + " 20,000 after trimming); request.invalid (400) without a field for a malformed body;";
    private static final String STALE = " request.stale_version (409) when version is not the artifact's newest (it"
            + " changed elsewhere, as in another tab); nothing is stored then;";
    private static final String NOTE_TYPE_FIXED = " request.note_type_fixed (409) when the artifact is a note a Shield"
            + " holds (shieldId set) and the record type would not be note (contract 1.57.0); nothing is stored then;";
    private static final String NOT_FOUND = " request.not_found (404) for a campaign that is not the account's, and for"
            + " an artifact that is not this campaign's (unknown, another account's, another campaign's or malformed);"
            + " none is told apart;";

    /** RFC 5987's attr-char beside letters and digits: kept as they are in {@code filename*}. */
    private static final String ATTR_CHAR = "!#$&+-.^_`|~";

    private final Artifacts artifacts;
    private final ArtifactExports exports;

    ArtifactController(Artifacts artifacts, ArtifactExports exports) {
        this.artifacts = artifacts;
        this.exports = exports;
    }

    @Schema(name = "ArtifactField", requiredProperties = {"name", "value"})
    record FieldView(
            @Schema(description = "The field's name, as the GM wrote it (trimmed)", example = "Dano") String name,
            @Schema(description = "The field's value, as the GM wrote it (trimmed): nothing is calculated",
                    example = "1d8 cortante") String value) {

        @Override
        public String toString() {
            return "FieldView";
        }
    }

    @Schema(name = "ArtifactOverride", requiredProperties = {"recordId", "name", "recordType", "systemId", "removed"},
            description = "The record an artifact overrides in its campaign (contract 1.50.0)")
    record OverrideView(
            @Schema(description = "The record's id; its page is /search/records/{recordId} in the campaign",
                    format = "uuid") String recordId,
            @Schema(description = "The record's name, as extracted or corrected", example = "Bola de Fogo") String name,
            @Schema(description = "The key of the record's type", pattern = "^[a-z][a-z0-9_]{0,31}$", example = "spell")
            String recordType,
            @Schema(description = "The campaign's system's id", format = "uuid") String systemId,
            @Schema(description = "True when the mark has no effect: the record was deleted, its version superseded"
                    + " or its material left the system tier of the campaign's scope. Point the mark to another record,"
                    + " or remove it") boolean removed) {

        static OverrideView of(Artifacts.Overridden overridden) {
            return overridden == null ? null : new OverrideView(overridden.recordId().toString(), overridden.name(),
                    overridden.recordType(), overridden.systemId().toString(), overridden.removed());
        }

        @Override
        public String toString() {
            return "OverrideView[recordId=" + recordId + ", removed=" + removed + "]";
        }
    }

    @Schema(name = "ArtifactRelated", requiredProperties = {"recordId", "name", "recordType", "tier", "artifact",
        "removed"}, description = "A record an artifact is related to (contract 1.51.0)")
    record RelatedView(
            @Schema(description = "The other record's id: an artifact's page is in the campaign; an extracted record's is"
                    + " /search/records/{recordId} in the campaign", format = "uuid") String recordId,
            @Schema(description = "Its name: an artifact's newest version's, or a record's as extracted or corrected",
                    example = "Troll") String name,
            @Schema(description = "The key of its record type", pattern = "^[a-z][a-z0-9_]{0,31}$",
                    example = "creature") String recordType,
            @Schema(description = "Its tier in the campaign's scope: campaign for an artifact; null for a record out of"
                    + " the scope", types = {"string", "null"}, allowableValues = {"campaign", "system", "other"})
            String tier,
            @Schema(description = "True for another artifact of the campaign, false for an extracted record")
            boolean artifact,
            @Schema(description = "True when the record no longer shows in the campaign: it was deleted, its version"
                    + " superseded or its material left the campaign's scope. Show its name with no link, or remove the"
                    + " relation") boolean removed) {

        static RelatedView of(RecordRelations.Related related) {
            return new RelatedView(related.recordId().toString(), related.name(), related.recordType(),
                    related.tier() == null ? null : related.tier().word(), related.artifact(), related.removed());
        }

        @Override
        public String toString() {
            return "RelatedView[recordId=" + recordId + ", tier=" + tier + ", artifact=" + artifact + ", removed="
                    + removed + "]";
        }
    }

    @Schema(name = "ArtifactSharedWith", requiredProperties = {"campaignId", "name", "archived"},
            description = "A campaign of the same system an artifact is shared with (contract 1.52.0)")
    record SharedWithView(
            @Schema(description = "The campaign's id", format = "uuid") String campaignId,
            @Schema(description = "The campaign's name", example = "Mesa de quinta") String name,
            @Schema(description = "True when the campaign is archived") boolean archived) {

        static SharedWithView of(Artifacts.SharedWith shared) {
            return new SharedWithView(shared.campaignId().toString(), shared.name(), shared.archived());
        }

        @Override
        public String toString() {
            return "SharedWithView[campaignId=" + campaignId + ", archived=" + archived + "]";
        }
    }

    @Schema(name = "ArtifactSharedFrom", requiredProperties = {"campaignId", "name"},
            description = "The campaign an artifact read from another campaign is shared from: its own (contract"
            + " 1.52.0)")
    record SharedFromView(
            @Schema(description = "The artifact's own campaign's id; its page there is where it is changed",
                    format = "uuid") String campaignId,
            @Schema(description = "That campaign's name", example = "A Coroa Partida") String name) {

        static SharedFromView of(Artifacts.SharedFrom from) {
            return from == null ? null : new SharedFromView(from.campaignId().toString(), from.name());
        }

        @Override
        public String toString() {
            return "SharedFromView[campaignId=" + campaignId + "]";
        }
    }

    @Schema(name = "Artifact", requiredProperties = {"id", "campaignId", "recordType", "name", "fields", "content",
        "version", "currentVersion", "createdAt", "conversationId", "references", "overrides", "related", "sharedWith",
        "sharedFrom", "shieldId"},
            description = "An artifact with its newest version, or one of its versions when asked by number (contract"
            + " 1.42.0; currentVersion and versions from 1.44.0; conversationId and references from 1.48.0; overrides"
            + " from 1.50.0; related from 1.51.0; sharedWith and sharedFrom from 1.52.0; shieldId from 1.57.0)")
    record ArtifactView(
            @Schema(description = "The artifact's id", format = "uuid", example = ID_EXAMPLE) String id,
            @Schema(description = "Its campaign's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000002")
            String campaignId,
            @Schema(description = "The key of the version's record type, one of its campaign's system's (core or"
                    + " approved); each version keeps its own (contract 1.44.0)",
                    pattern = "^[a-z][a-z0-9_]{0,31}$", example = "item") String recordType,
            @Schema(description = "The version's name", example = "Espada de Kael") String name,
            @ArraySchema(arraySchema = @Schema(description = "The version's fields, in the order written; empty for"
                    + " none"), schema = @Schema(implementation = FieldView.class)) List<FieldView> fields,
            @Schema(description = "The version's free text, as written (trimmed); null for none",
                    types = {"string", "null"}, example = "Forjada nas montanhas do norte.") String content,
            @Schema(description = "The number of the version shown, from 1", format = "int32", minimum = "1",
                    example = "1") int version,
            @Schema(description = "The number of the artifact's newest version (contract 1.44.0): the same as version"
                    + " unless an earlier version is shown", format = "int32", minimum = "1", example = "1")
            int currentVersion,
            @Schema(description = "When the artifact was written", format = "date-time") String createdAt,
            @Schema(description = "The conversation the artifact was saved from, a draft's (contract 1.48.0); its page"
                    + " is that conversation's. Null for an artifact written by hand, and once that conversation is"
                    + " deleted (from contract 1.49.0, from the moment its deletion starts)",
                    types = {"string", "null"}, format = "uuid") String conversationId,
            @ArraySchema(arraySchema = @Schema(description = "The sources of the draft the artifact was saved from"
                    + " (contract 1.48.0), copied by value when it was saved, by number, in the shape of a turn's"
                    + " references (a passage's, a record's or an artifact's) without their positions in the text."
                    + " removed is worked out as of this answer (a source deleted since reads as removed, and the"
                    + " artifact's content is unchanged). Kept by every edit and restore. Empty for an artifact written"
                    + " by hand"), schema = @Schema(ref = "#/components/schemas/TurnSourceReference"))
            List<ReferenceView> references,
            @Schema(description = "The record of its campaign's system the artifact overrides (contract 1.50.0): in"
                    + " this campaign, an answer that reaches that record reads the artifact first, as replacing it. Not"
                    + " versioned: every version shows the artifact's current mark. Null for none",
                    types = {"object", "null"}, implementation = OverrideView.class) OverrideView overrides,
            @ArraySchema(arraySchema = @Schema(description = "The records the artifact is related to (contract 1.51.0),"
                    + " from either side, oldest first: extracted records of the campaign's scope and the campaign's"
                    + " other artifacts. A relation changes no answer. Not versioned: every version shows the"
                    + " artifact's current relations. Empty for none"),
                    schema = @Schema(implementation = RelatedView.class)) List<RelatedView> related,
            @ArraySchema(arraySchema = @Schema(description = "The account's other campaigns of the same system the"
                    + " artifact is shared with (contract 1.52.0), by name: in each, from the next question, it is a"
                    + " source of the system tier, cited under that tier. A campaign being deleted is left out. Not"
                    + " versioned. Empty for none, and in a read from another campaign (sharedFrom set)"),
                    schema = @Schema(implementation = SharedWithView.class)) List<SharedWithView> sharedWith,
            @Schema(description = "Set when the artifact is read from a campaign it is shared into (contract 1.52.0):"
                    + " its own campaign, where alone it is changed. Such a read is read-only: it has no overrides, no"
                    + " related, no sharedWith and a null conversationId, and every other artifact route answers"
                    + " request.not_found there. Null in its own campaign",
                    types = {"object", "null"}, implementation = SharedFromView.class) SharedFromView sharedFrom,
            @Schema(description = "The Shield of its campaign holding the artifact, a note written in that Shield"
                    + " (contract 1.57.0): its recordType is always note, and an edit or a restore to another type is"
                    + " request.note_type_fixed (409). Null for an artifact no Shield holds, and in a read from another"
                    + " campaign (sharedFrom set)", types = {"string", "null"}, format = "uuid") String shieldId) {

        static ArtifactView of(Artifacts.Artifact artifact) {
            return new ArtifactView(artifact.id().toString(), artifact.campaignId().toString(), artifact.recordType(),
                    artifact.name(), artifact.fields().stream().map(field -> new FieldView(field.name(), field.value()))
                    .toList(), artifact.content(), artifact.version(), artifact.currentVersion(),
                    artifact.createdAt().toString(),
                    artifact.conversationId() == null ? null : artifact.conversationId().toString(),
                    artifact.references().stream().map(ReferenceView::of).toList(),
                    OverrideView.of(artifact.overrides()),
                    artifact.related().stream().map(RelatedView::of).toList(),
                    artifact.sharedWith().stream().map(SharedWithView::of).toList(),
                    SharedFromView.of(artifact.sharedFrom()),
                    artifact.shieldId() == null ? null : artifact.shieldId().toString());
        }

        @Override
        public String toString() {
            return "ArtifactView[id=" + id + ", recordType=" + recordType + ", fields=" + fields.size() + ", version="
                    + version + ", currentVersion=" + currentVersion + "]";
        }
    }

    /**
     * One of a saved draft's references as the artifact answers it (Story 5.4 part A): the JSON of a turn's
     * {@code TurnSourceReference}, whose schema the contract names; the fields a kind does not have are absent.
     * {@code toString} names the number and the kind only.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ReferenceView(int number, String kind, String materialName, List<String> sectionPath, Integer page,
                         List<String> passageIds, String recordId, String artifactId, Integer artifactVersion,
                         String recordType, String name, List<FieldView> fields, boolean removed, String tier) {

        static ReferenceView of(TurnReference reference) {
            TurnReference.Cited cited = reference.record();
            List<FieldView> fields = cited == null ? null : cited.fields().stream()
                    .map(field -> new FieldView(field.name(), field.value())).toList();
            if (TurnReference.ARTIFACT.equals(reference.kind())) {
                // An artifact's reference always names the artifact it cites (Sonar S2259 cannot see that).
                TurnReference.Cited artifact = Objects.requireNonNull(cited, "artifacts.reference_without_artifact");
                return new ReferenceView(reference.number(), reference.kind(), null, null, null, null, null,
                        artifact.id().toString(), artifact.artifactVersion(), artifact.type(), artifact.name(), fields,
                        reference.removed(), reference.tier());
            }
            return new ReferenceView(reference.number(), reference.kind(), reference.materialName(),
                    reference.sectionPath(), reference.page(), reference.passageIds().stream().map(UUID::toString)
                    .toList(), cited == null ? null : cited.id().toString(), null, null,
                    cited == null ? null : cited.type(), cited == null ? null : cited.name(), fields,
                    reference.removed(), reference.tier());
        }

        @Override
        public String toString() {
            return "ReferenceView[number=" + number + ", kind=" + kind + "]";
        }
    }

    @Schema(name = "ArtifactVersionSummary", requiredProperties = {"number", "recordType", "name", "createdAt"},
            description = "One version of an artifact in its versions list (contract 1.44.0)")
    record VersionSummaryView(
            @Schema(description = "The version's number, from 1", format = "int32", minimum = "1", example = "2")
            int number,
            @Schema(description = "The key of the version's record type", pattern = "^[a-z][a-z0-9_]{0,31}$",
                    example = "weapon") String recordType,
            @Schema(description = "The version's name", example = "Espada de Kael") String name,
            @Schema(description = "When the version was written", format = "date-time") String createdAt) {

        static VersionSummaryView of(Artifacts.VersionSummary summary) {
            return new VersionSummaryView(summary.number(), summary.recordType(), summary.name(),
                    summary.createdAt().toString());
        }

        @Override
        public String toString() {
            return "VersionSummaryView[number=" + number + ", recordType=" + recordType + "]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "ArtifactVersionPage", requiredProperties = {"items", "total"},
            description = "A page of an artifact's versions, newest first (contract 1.44.0)")
    record VersionPageView(
            @ArraySchema(schema = @Schema(implementation = VersionSummaryView.class)) List<VersionSummaryView> items,
            @Schema(description = "The cursor of the next page (the last number shown); absent on the last",
                    format = "int32", minimum = "1", example = "3") Integer nextCursor,
            @Schema(description = "How many versions the artifact has, whatever the page", format = "int64",
                    minimum = "1", example = "3") long total) {
    }

    @Schema(name = "ArtifactSummary", requiredProperties = {"id", "recordType", "name", "version", "createdAt",
        "sharedFrom", "shieldId"}, description = "An artifact in a list (contract 1.42.0; sharedFrom from 1.53.0;"
            + " shieldId from 1.57.0)")
    record SummaryView(
            @Schema(description = "The artifact's id", format = "uuid", example = ID_EXAMPLE) String id,
            @Schema(description = "The key of its record type", pattern = "^[a-z][a-z0-9_]{0,31}$", example = "item")
            String recordType,
            @Schema(description = "Its name, in its newest version", example = "Espada de Kael") String name,
            @Schema(description = "The number of its newest version, from 1", format = "int32", minimum = "1",
                    example = "1") int version,
            @Schema(description = "When it was written", format = "date-time") String createdAt,
            @Schema(description = "Set for an artifact of another of the account's campaigns shared into the listed"
                    + " one (contract 1.53.0): its own campaign, where alone it is changed. getArtifact answers it"
                    + " read-only from the listed campaign. Null for the listed campaign's own artifacts",
                    types = {"object", "null"}, implementation = SharedFromView.class) SharedFromView sharedFrom,
            @Schema(description = "The Shield of its campaign holding it, a note's (contract 1.57.0); null for an"
                    + " artifact no Shield holds and for one shared into the listed campaign",
                    types = {"string", "null"}, format = "uuid") String shieldId) {

        static SummaryView of(Artifacts.Summary summary) {
            return new SummaryView(summary.id().toString(), summary.recordType(), summary.name(), summary.version(),
                    summary.createdAt().toString(), SharedFromView.of(summary.sharedFrom()),
                    summary.shieldId() == null ? null : summary.shieldId().toString());
        }

        @Override
        public String toString() {
            return "SummaryView[id=" + id + ", recordType=" + recordType + ", shared=" + (sharedFrom != null) + "]";
        }
    }

    @Schema(name = "ArtifactTypeCount", requiredProperties = {"key", "count"},
            description = "A record type in use in a campaign's artifacts")
    record TypeCountView(
            @Schema(description = "The record type's key", pattern = "^[a-z][a-z0-9_]{0,31}$",
                    example = "character_sheet") String key,
            @Schema(description = "How many of the campaign's artifacts have it", format = "int64", minimum = "1",
                    example = "2") long count) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "ArtifactPage", requiredProperties = {"items", "total", "types"})
    record PageView(
            @ArraySchema(schema = @Schema(implementation = SummaryView.class)) List<SummaryView> items,
            @Schema(description = "The cursor of the next page; absent on the last", format = "uuid",
                    example = ID_EXAMPLE) String nextCursor,
            @Schema(description = "How many artifacts the list has in all (of its record type when filtered),"
                    + " whatever the page; from contract 1.53.0 the shared ones included", format = "int64",
                    minimum = "0", example = "3") long total,
            @ArraySchema(arraySchema = @Schema(description = "The record types in use in the campaign's artifacts,"
                    + " whatever the filter, by key, each with its count (from contract 1.53.0 the shared ones"
                    + " included); empty for a campaign without artifacts"),
                    schema = @Schema(implementation = TypeCountView.class)) List<TypeCountView> types) {
    }

    @Schema(name = "NewArtifactField", requiredProperties = {"name", "value"})
    record FieldBody(
            @Schema(description = "The field's name: 1 to 200 code points after trimming", minLength = 1,
                    maxLength = 200, example = "Dano") String name,
            @Schema(description = "The field's value: 1 to 8,000 code points after trimming", minLength = 1,
                    maxLength = 8000, example = "1d8 cortante") String value) {

        @Override
        public String toString() {
            return "FieldBody";
        }
    }

    @Schema(name = "NewArtifact", requiredProperties = {"recordType", "name", "fields"})
    record NewArtifact(
            @Schema(description = "The key of one of the campaign's system's record types, core or approved",
                    pattern = "^[a-z][a-z0-9_]{0,31}$", example = "item") String recordType,
            @Schema(description = "The artifact's name: 1 to 200 code points after trimming", minLength = 1,
                    maxLength = 200, example = "Espada de Kael") String name,
            @ArraySchema(arraySchema = @Schema(description = "The artifact's fields in order, at most 64"),
                    maxItems = 64, schema = @Schema(implementation = FieldBody.class)) List<FieldBody> fields,
            @Schema(description = "The artifact's free text: at most 20,000 code points after trimming; absent, null"
                    + " or blank for none", types = {"string", "null"}, maxLength = 20000,
                    example = "Forjada nas montanhas do norte.") String content) {

        @Override
        public String toString() {
            return "NewArtifact[recordType=" + recordType + ", fields=" + (fields == null ? 0 : fields.size()) + "]";
        }

        /** What the GM wrote, or {@code request.invalid} for a body without a type, a name or fields. */
        Artifacts.Written written() {
            if (recordType == null || name == null || fields == null) {
                throw invalid();
            }
            return new Artifacts.Written(recordType, name, fields.stream()
                    .map(field -> field == null ? new Artifacts.Field(null, null)
                            : new Artifacts.Field(field.name(), field.value())).toList(), content);
        }
    }

    @Schema(name = "ArtifactEdit", requiredProperties = {"version", "recordType", "name", "fields"})
    record ArtifactEdit(
            @Schema(description = "The number of the artifact's newest version, as the GM last saw it", format = "int32",
                    minimum = "1", example = "1") Integer version,
            @Schema(description = "The key of one of the campaign's system's record types, core or approved",
                    pattern = "^[a-z][a-z0-9_]{0,31}$", example = "weapon") String recordType,
            @Schema(description = "The artifact's name: 1 to 200 code points after trimming", minLength = 1,
                    maxLength = 200, example = "Espada de Kael") String name,
            @ArraySchema(arraySchema = @Schema(description = "The artifact's fields in order, at most 64"),
                    maxItems = 64, schema = @Schema(implementation = FieldBody.class)) List<FieldBody> fields,
            @Schema(description = "The artifact's free text: at most 20,000 code points after trimming; absent, null"
                    + " or blank for none", types = {"string", "null"}, maxLength = 20000,
                    example = "Forjada nas montanhas do norte.") String content) {

        @Override
        public String toString() {
            return "ArtifactEdit[version=" + version + ", recordType=" + recordType + ", fields="
                    + (fields == null ? 0 : fields.size()) + "]";
        }

        /** The newest version the GM saw, or {@code request.invalid} for none. */
        int seen() {
            return checkedVersion(version);
        }

        /** What the GM wrote, as a new artifact's body says it. */
        Artifacts.Written written() {
            return new NewArtifact(recordType, name, fields, content).written();
        }
    }

    @Schema(name = "ArtifactRestore", requiredProperties = {"version"})
    record ArtifactRestore(
            @Schema(description = "The number of the artifact's newest version, as the GM last saw it", format = "int32",
                    minimum = "1", example = "3") Integer version) {
    }

    @PostMapping(path = ARTIFACTS, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createArtifact", summary = "A new artifact of one of the account's campaigns, written by"
            + " hand (contract 1.42.0; 201): its record type, name, fields and optional content become its version 1,"
            + " as getArtifact answers it. A character sheet stores what was written: nothing is calculated."
            + LIMITS + " request.not_found (404) for a campaign that is not the account's (unknown, another account's,"
            + " malformed or being deleted); nothing is stored then;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<ArtifactView> createArtifact(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @RequestBody NewArtifact body) {
        UUID campaign = parse(campaignId).orElseThrow(ArtifactController::notFound);
        if (body == null) {
            throw invalid();
        }
        Artifacts.Artifact created = artifacts.create(campaign, body.written())
                .orElseThrow(ArtifactController::notFound);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ArtifactView.of(created));
    }

    @GetMapping(path = ARTIFACTS, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listArtifacts", summary = "One of the account's campaigns' artifacts (contract 1.42.0),"
            + " newest first, of one record type when recordType is given. A page starts after a cursor or at an"
            + " offset (a numbered page), and names the total of the list and the record types in use in the whole"
            + " campaign with their counts. From contract 1.53.0 the list also holds, whatever the campaign's tiers, the"
            + " live artifacts of the account's other campaigns shared into this one (sharedFrom set), in the same"
            + " order, counted in total and types. request.not_found (404) for a campaign that is not the account's (unknown,"
            + " another account's, malformed or being deleted); request.invalid (400) for a recordType that is not a"
            + " key's text, a limit outside 1 to 50, a malformed cursor, an offset outside 0 to 100000 or a cursor"
            + " together with an offset above 0;" + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "recordType", in = ParameterIn.QUERY, description = "Only the artifacts of this"
                        + " record type key (a key no artifact has lists none)",
                        schema = @Schema(type = "string", pattern = "^[a-z][a-z0-9_]{0,31}$")),
                @Parameter(name = "limit", in = ParameterIn.QUERY, description = "At most this many, 1 to 50",
                        schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "50",
                                defaultValue = "20")),
                @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "The nextCursor of the previous page",
                        schema = @Schema(type = "string", format = "uuid")),
                @Parameter(name = "offset", in = ParameterIn.QUERY, description = "How many of the newest to skip, 0 to"
                        + " 100000, for a numbered page; not together with cursor",
                        schema = @Schema(type = "integer", format = "int32", minimum = "0", maximum = "100000",
                                defaultValue = "0"))})
    ResponseEntity<PageView> listArtifacts(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(hidden = true) @RequestParam(value = "recordType", required = false) String recordType,
            @Parameter(hidden = true) @RequestParam(value = "limit", required = false) String limit,
            @Parameter(hidden = true) @RequestParam(value = "cursor", required = false) String cursor,
            @Parameter(hidden = true) @RequestParam(value = "offset", required = false) String offset) {
        UUID campaign = parse(campaignId).orElseThrow(ArtifactController::notFound);
        if (recordType != null && !KEY.matcher(recordType).matches()) {
            throw invalid();
        }
        int size = limit(limit);
        UUID after = cursor == null ? null : parse(cursor).orElseThrow(ArtifactController::invalid);
        int from = offset(offset, after);
        Artifacts.Page page = artifacts.list(campaign, recordType, after, from, size)
                .orElseThrow(ArtifactController::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PageView(
                page.items().stream().map(SummaryView::of).toList(),
                page.nextCursor() == null ? null : page.nextCursor().toString(), page.total(),
                page.types().stream().map(type -> new TypeCountView(type.key(), type.count())).toList()));
    }

    @GetMapping(path = ARTIFACT, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getArtifact", summary = "One artifact of one of the account's campaigns with its newest"
            + " version (contract 1.42.0). From contract 1.52.0 also, read-only, a live artifact of another of the"
            + " account's campaigns shared into this one (sharedFrom set), whatever this campaign's tiers."
            + " request.not_found (404) for a campaign that is not the account's, and for an artifact that is not this"
            + " campaign's nor shared into it (unknown, another account's, another campaign's, deleting or malformed);"
            + " none is told apart;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<ArtifactView> getArtifact(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(description = "The artifact's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("artifactId") String artifactId) {
        UUID campaign = parse(campaignId).orElseThrow(ArtifactController::notFound);
        UUID artifact = parse(artifactId).orElseThrow(ArtifactController::notFound);
        return artifacts.get(campaign, artifact).map(found -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ArtifactView.of(found))).orElseThrow(ArtifactController::notFound);
    }

    // No `produces`: a refusal is problem JSON, which a file's Accept would otherwise turn into a 406.
    @GetMapping(path = EXPORT)
    @Operation(operationId = "exportArtifacts", summary = "One file of 1 to 50 of the account's artifacts readable in"
            + " one of its campaigns (contract 1.54.0), in the order given: each read as getArtifact reads it (the"
            + " campaign's own and the live ones shared into it, at their newest version) and printed with its name,"
            + " record type, fields in the order written, content, then its sources as numbered references (a"
            + " material's place, a record's type and name, an artifact's type, name and version; a removed one"
            + " marked), labelled in the language given. pdf, txt and markdown give one file, each artifact a new"
            + " section (a new page in the PDF); jpg gives one image (all the pages of the PDF stacked) for one artifact,"
            + " a zip of 01-<name>.jpg, 02-... for several. The file is named after the artifact, or artefatos /"
            + " artifacts for several, as an attachment (filename*), no-store and nosniff. Built in memory: nothing is"
            + " kept. request.not_found (404) for a campaign that is not the account's, and when any id is not readable"
            + " there (unknown, another account's, another campaign's, deleting or malformed), naming none;"
            + " request.invalid (400) for no ids, more than 50, a duplicate or an empty one, or a format or language"
            + " that is not one of these; artifact.export_too_large (400) for a pdf or jpg export of more than 200 PDF"
            + " pages in all (txt and markdown are not counted); artifact.export_busy (503) while 2 pdf or jpg exports"
            + " render, to try again in a moment;" + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "ids", in = ParameterIn.QUERY, required = true, description = "The artifacts' ids,"
                        + " comma-separated, 1 to 50, distinct, in the order the file holds them",
                        schema = @Schema(type = "string", example = ID_EXAMPLE)),
                @Parameter(name = "format", in = ParameterIn.QUERY, required = true, description = "The file's format",
                        schema = @Schema(type = "string", allowableValues = {"pdf", "txt", "markdown", "jpg"})),
                @Parameter(name = "language", in = ParameterIn.QUERY, description = "The language of the labels the"
                        + " export adds (record type names of the core types, Fontes or Sources, a removed source's"
                        + " mark)", schema = @Schema(type = "string", allowableValues = {"pt-BR", "en"},
                                defaultValue = "pt-BR"))},
            responses = @ApiResponse(responseCode = "200", description = "The file, as an attachment", content = {
                @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")),
                @Content(mediaType = "text/plain", schema = @Schema(type = "string", format = "binary")),
                @Content(mediaType = "text/markdown", schema = @Schema(type = "string", format = "binary")),
                @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")),
                @Content(mediaType = "application/zip", schema = @Schema(type = "string", format = "binary"))}))
    void exportArtifacts(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(hidden = true) @RequestParam(value = "ids", required = false) String ids,
            @Parameter(hidden = true) @RequestParam(value = "format", required = false) String format,
            @Parameter(hidden = true) @RequestParam(value = "language", required = false) String language,
            @Parameter(hidden = true) HttpServletResponse response) throws IOException {
        UUID campaign = parse(campaignId).orElseThrow(ArtifactController::notFound);
        ArtifactExports.Format chosen = ArtifactExports.Format.of(format).orElseThrow(ArtifactController::invalid);
        Language labels = language == null ? Language.DEFAULT
                : Language.fromTag(language).orElseThrow(ArtifactController::invalid);
        List<UUID> chosenIds = exportIds(ids);
        ArtifactExports.Rendered file = exports.export(campaign, chosenIds, chosen, labels);
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(file.mediaType());
        response.setContentLengthLong(file.bytes().length);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, attachment(file.fileName()));
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        response.setHeader("X-Content-Type-Options", "nosniff");
        try (OutputStream out = response.getOutputStream()) {
            out.write(file.bytes());
        }
    }

    /**
     * An export's ids (Story 5.7): 1 to {@value ArtifactExports#MAX_IDS}, comma-separated, none empty and none twice,
     * else {@code request.invalid}; a malformed one is an id that exists nowhere, so {@code request.not_found}, once
     * the list itself is valid.
     */
    private static List<UUID> exportIds(String ids) {
        if (ids == null || ids.isEmpty()) {
            throw invalid();
        }
        String[] parts = ids.split(",", -1);
        if (parts.length > ArtifactExports.MAX_IDS) {
            throw invalid();
        }
        Set<String> seen = new HashSet<>();
        for (String part : parts) {
            if (part.isBlank() || !seen.add(part.toLowerCase(Locale.ROOT))) {
                throw invalid();
            }
        }
        List<UUID> parsed = new ArrayList<>(parts.length);
        for (String part : parts) {
            parsed.add(parse(part).orElseThrow(ArtifactController::notFound));
        }
        return parsed;
    }

    /**
     * {@code Content-Disposition} for an attachment named {@code name} (RFC 6266): {@code filename} its ASCII form (accents
     * dropped, anything else outside printable ASCII, a quote, a backslash or a percent sign as {@code _}) for old
     * clients, and {@code filename*} the UTF-8 name, percent-encoded (RFC 5987).
     */
    static String attachment(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder fallback = new StringBuilder(ascii.length());
        ascii.codePoints().forEach(c -> fallback.append(c >= 0x20 && c < 0x7F && c != '"' && c != '\\' && c != '%'
                ? (char) c : '_'));
        StringBuilder encoded = new StringBuilder();
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || ATTR_CHAR.indexOf(c) >= 0) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }

    @GetMapping(path = VERSION, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getArtifactVersion", summary = "One version of one of the account's campaigns' artifacts,"
            + " by its number (contract 1.44.0), as a reference that cited it opens it; currentVersion names the newest."
            + " From contract 1.52.0 also, read-only, a version of a live artifact shared into this campaign."
            + NOT_FOUND + " request.not_found (404) too for a number the artifact does not have, or a malformed one;"
            + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<ArtifactView> getArtifactVersion(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(description = "The artifact's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("artifactId") String artifactId,
            @Parameter(description = "The version's number, from 1",
                    schema = @Schema(type = "integer", format = "int32", minimum = "1"))
            @PathVariable("number") String number) {
        UUID campaign = parse(campaignId).orElseThrow(ArtifactController::notFound);
        UUID artifact = parse(artifactId).orElseThrow(ArtifactController::notFound);
        int version = number(number).orElseThrow(ArtifactController::notFound);
        return artifacts.get(campaign, artifact, version).map(found -> ResponseEntity.ok()
                .cacheControl(CacheControl.noStore()).body(ArtifactView.of(found)))
                .orElseThrow(ArtifactController::notFound);
    }

    @GetMapping(path = VERSIONS, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listArtifactVersions", summary = "The versions of one of the account's campaigns'"
            + " artifacts (contract 1.44.0), newest first, each with its number, record type, name and time; nothing is"
            + " ever deleted. A page starts after a cursor (the previous page's nextCursor) or at an offset (a numbered"
            + " page), and names the total." + NOT_FOUND + " request.invalid (400) for a limit outside 1 to 50, a"
            + " malformed cursor, an offset outside 0 to 100000 or a cursor together with an offset above 0;"
            + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "limit", in = ParameterIn.QUERY, description = "At most this many, 1 to 50",
                        schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "50",
                                defaultValue = "20")),
                @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "The nextCursor of the previous page",
                        schema = @Schema(type = "integer", format = "int32", minimum = "1")),
                @Parameter(name = "offset", in = ParameterIn.QUERY, description = "How many of the newest to skip, 0 to"
                        + " 100000, for a numbered page; not together with cursor",
                        schema = @Schema(type = "integer", format = "int32", minimum = "0", maximum = "100000",
                                defaultValue = "0"))})
    ResponseEntity<VersionPageView> listArtifactVersions(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(description = "The artifact's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("artifactId") String artifactId,
            @Parameter(hidden = true) @RequestParam(value = "limit", required = false) String limit,
            @Parameter(hidden = true) @RequestParam(value = "cursor", required = false) String cursor,
            @Parameter(hidden = true) @RequestParam(value = "offset", required = false) String offset) {
        UUID campaign = parse(campaignId).orElseThrow(ArtifactController::notFound);
        UUID artifact = parse(artifactId).orElseThrow(ArtifactController::notFound);
        int size = limit(limit);
        Integer before = cursor == null ? null : number(cursor).orElseThrow(ArtifactController::invalid);
        int from = offset(offset, before);
        Artifacts.VersionPage page = artifacts.versions(campaign, artifact, before, from, size)
                .orElseThrow(ArtifactController::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new VersionPageView(
                page.items().stream().map(VersionSummaryView::of).toList(), page.nextCursor(), page.total()));
    }

    @PatchMapping(path = ARTIFACT, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateArtifact", summary = "The GM's edit of one of the account's campaigns' artifacts"
            + " (contract 1.44.0): its record type, name, fields and optional content become its next version, and every"
            + " earlier version is kept. The edit applies from the next question (the new version is indexed). version"
            + " names the newest version the GM saw. An edit that changes nothing (the same record type, name, fields"
            + " and content, after trimming) answers that version as it is and stores nothing." + LIMITS + STALE
            + NOTE_TYPE_FIXED + NOT_FOUND + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<ArtifactView> updateArtifact(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(description = "The artifact's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("artifactId") String artifactId,
            @RequestBody ArtifactEdit body) {
        UUID campaign = parse(campaignId).orElseThrow(ArtifactController::notFound);
        UUID artifact = parse(artifactId).orElseThrow(ArtifactController::notFound);
        if (body == null) {
            throw invalid();
        }
        int seen = body.seen();
        Artifacts.Artifact updated = artifacts.update(campaign, artifact, seen, body.written())
                .orElseThrow(ArtifactController::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ArtifactView.of(updated));
    }

    @PostMapping(path = RESTORE, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "restoreArtifactVersion", summary = "The GM's restore of an earlier version of one of the"
            + " account's campaigns' artifacts (contract 1.44.0): its record type, name, fields and content are copied"
            + " as the artifact's next version; nothing is deleted. version names the newest version the GM saw."
            + " request.invalid (400) with params.field recordType when the version's record type is no longer one of"
            + " the system's; request.invalid (400) without a field for a malformed body;" + STALE + NOTE_TYPE_FIXED
            + NOT_FOUND
            + " request.not_found (404) too for a number the artifact does not have, or a malformed one;"
            + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<ArtifactView> restoreArtifactVersion(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(description = "The artifact's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("artifactId") String artifactId,
            @Parameter(description = "The number of the version to restore, from 1",
                    schema = @Schema(type = "integer", format = "int32", minimum = "1"))
            @PathVariable("number") String number,
            @RequestBody ArtifactRestore body) {
        UUID campaign = parse(campaignId).orElseThrow(ArtifactController::notFound);
        UUID artifact = parse(artifactId).orElseThrow(ArtifactController::notFound);
        int restored = number(number).orElseThrow(ArtifactController::notFound);
        if (body == null) {
            throw invalid();
        }
        int seen = checkedVersion(body.version());
        Artifacts.Artifact created = artifacts.restore(campaign, artifact, restored, seen)
                .orElseThrow(ArtifactController::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ArtifactView.of(created));
    }

    @DeleteMapping(path = ARTIFACT)
    @Operation(operationId = "deleteArtifact", summary = "Deletes one of the account's campaigns' artifacts with every"
            + " version (contract 1.45.0; 202, no body): from now on it is not found on every route (its page, its"
            + " versions, an edit or a restore), gone from the campaign's list and type counts and from the campaign"
            + " tier of every question, and an answer still being written leaves it out; its versions, index rows and"
            + " index points are removed in the background. A reference an answer stored to it reads as a removed"
            + " source. Nothing of its campaign, of its system's materials or of other artifacts changes. Cannot be"
            + " undone." + NOT_FOUND + " request.not_found (404) too for an artifact already being deleted;"
            + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "202", description = "The deletion is recorded"))
    ResponseEntity<Void> deleteArtifact(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(description = "The artifact's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("artifactId") String artifactId) {
        UUID campaign = parse(campaignId).orElseThrow(ArtifactController::notFound);
        UUID artifact = parse(artifactId).orElseThrow(ArtifactController::notFound);
        if (!artifacts.delete(campaign, artifact)) {
            throw notFound();
        }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @Schema(name = "ArtifactOverrideChange", requiredProperties = "recordId",
            description = "The record an artifact overrides from now on (contract 1.50.0)")
    record OverrideBody(
            @Schema(description = "The record's id: a record of the system tier of the campaign's scope, as"
                    + " listOverrideTargets lists them", format = "uuid") String recordId) {
    }

    @Schema(name = "OverrideTarget", requiredProperties = {"id", "name", "recordType", "materialName"},
            description = "A record an artifact of the campaign can override (contract 1.50.0)")
    record TargetView(
            @Schema(description = "The record's id", format = "uuid") String id,
            @Schema(description = "The record's name", example = "Bola de Fogo") String name,
            @Schema(description = "The key of the record's type", pattern = "^[a-z][a-z0-9_]{0,31}$", example = "spell")
            String recordType,
            @Schema(description = "The name of the material it was extracted from", example = "Livro do Jogador")
            String materialName) {

        @Override
        public String toString() {
            return "TargetView[id=" + id + ", recordType=" + recordType + "]";
        }
    }

    @Schema(name = "OverrideTargetPage", requiredProperties = {"items", "total"},
            description = "A page of the records an artifact of the campaign can override (contract 1.50.0)")
    record TargetPageView(
            @ArraySchema(schema = @Schema(implementation = TargetView.class)) List<TargetView> items,
            @Schema(description = "How many records match in all, whatever the page", format = "int64", minimum = "0",
                    example = "12") long total) {
    }

    @PutMapping(path = OVERRIDE, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "setArtifactOverride", summary = "The GM marks one of the account's campaigns' artifacts as"
            + " overriding one record of the campaign's system (contract 1.50.0), answering the artifact as"
            + " getArtifact does. From the next question, in this campaign only, an answer that reaches that record"
            + " reads the artifact first, as replacing it, and the record follows as an alternative. An artifact"
            + " overrides at most one record: a mark it had points to the new record. artifact.override_target (400)"
            + " for a record that is not a live record of the system tier of the campaign's scope (another system's,"
            + " a campaign material's, a deleted one, another account's, unknown or malformed, or none);"
            + " artifact.override_taken (409) for a record another artifact of the campaign overrides already, with"
            + " params.artifactId naming it; request.invalid (400) for a malformed body;" + NOT_FOUND
            + " nothing changes then;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<ArtifactView> setArtifactOverride(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(description = "The artifact's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("artifactId") String artifactId,
            @RequestBody OverrideBody body) {
        UUID campaign = parse(campaignId).orElseThrow(ArtifactController::notFound);
        UUID artifact = parse(artifactId).orElseThrow(ArtifactController::notFound);
        if (body == null) {
            throw invalid();
        }
        // A missing or malformed id is a record that is no target: the same refusal, after the artifact is found.
        UUID record = parse(body.recordId()).orElse(null);
        Artifacts.Artifact marked = artifacts.override(campaign, artifact, record)
                .orElseThrow(ArtifactController::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ArtifactView.of(marked));
    }

    @DeleteMapping(path = OVERRIDE)
    @Operation(operationId = "removeArtifactOverride", summary = "The GM removes the mark of one of the account's"
            + " campaigns' artifacts (contract 1.50.0; 204, no body): from the next question the record is no longer"
            + " replaced, and the artifact stays an ordinary source of its campaign. An artifact with no mark answers"
            + " 204 too, so repeating it is safe." + NOT_FOUND + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "204", description = "The artifact overrides nothing"))
    ResponseEntity<Void> removeArtifactOverride(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(description = "The artifact's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("artifactId") String artifactId) {
        UUID campaign = parse(campaignId).orElseThrow(ArtifactController::notFound);
        UUID artifact = parse(artifactId).orElseThrow(ArtifactController::notFound);
        if (!artifacts.removeOverride(campaign, artifact)) {
            throw notFound();
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping(path = OVERRIDE_TARGETS, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listOverrideTargets", summary = "The records an artifact of one of the account's"
            + " campaigns can override (contract 1.50.0): the live records of one record type in the system tier of the"
            + " campaign's scope (its stored settings), alphabetical by name, a page at a time with the total; q keeps"
            + " those whose name contains it, case and accents ignored. A record type the system does not have lists"
            + " none. request.not_found (404) for a campaign that is not the account's (unknown, another account's,"
            + " malformed or being deleted); request.invalid (400) for a missing or malformed recordType, a q over 200"
            + " characters, a limit outside 1 to 50 or an offset outside 0 to 100000;" + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "recordType", in = ParameterIn.QUERY, required = true, description = "The record"
                        + " type key of the records listed", schema = @Schema(type = "string",
                        pattern = "^[a-z][a-z0-9_]{0,31}$")),
                @Parameter(name = "q", in = ParameterIn.QUERY, description = "Only the records whose name contains"
                        + " this, case and accents ignored; at most 200 characters, blank for all",
                        schema = @Schema(type = "string", maxLength = 200)),
                @Parameter(name = "limit", in = ParameterIn.QUERY, description = "At most this many, 1 to 50",
                        schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "50",
                                defaultValue = "20")),
                @Parameter(name = "offset", in = ParameterIn.QUERY, description = "How many of the first to skip, 0 to"
                        + " 100000, for a numbered page", schema = @Schema(type = "integer", format = "int32",
                        minimum = "0", maximum = "100000", defaultValue = "0"))})
    ResponseEntity<TargetPageView> listOverrideTargets(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(hidden = true) @RequestParam(value = "recordType", required = false) String recordType,
            @Parameter(hidden = true) @RequestParam(value = "q", required = false) String query,
            @Parameter(hidden = true) @RequestParam(value = "limit", required = false) String limit,
            @Parameter(hidden = true) @RequestParam(value = "offset", required = false) String offset) {
        UUID campaign = parse(campaignId).orElseThrow(ArtifactController::notFound);
        if (recordType == null || !KEY.matcher(recordType).matches()
                || (query != null && query.strip().codePointCount(0, query.strip().length())
                > RecordRelations.MAX_QUERY)) {
            throw invalid();
        }
        int size = limit(limit);
        int from = offset(offset, null);
        RecordRelations.TargetPage page = artifacts.targets(campaign, recordType, query, from, size)
                .orElseThrow(ArtifactController::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new TargetPageView(page.items().stream()
                .map(target -> new TargetView(target.id().toString(), target.name(), target.recordType(),
                        target.materialName())).toList(), page.total()));
    }

    @Schema(name = "ArtifactRelationChange", requiredProperties = "recordId",
            description = "A record the artifact is related to from now on (contract 1.51.0)")
    record RelationBody(
            @Schema(description = "The record's id: an extracted record of the campaign's scope or another artifact of"
                    + " the campaign, as listRelationTargets lists them", format = "uuid") String recordId) {
    }

    @Schema(name = "RelationTarget", requiredProperties = {"id", "name", "recordType", "tier", "artifact",
        "materialName"}, description = "A record an artifact can be related to (contract 1.51.0)")
    record RelationTargetView(
            @Schema(description = "The record's id", format = "uuid") String id,
            @Schema(description = "The record's name", example = "Troll") String name,
            @Schema(description = "The key of the record's type", pattern = "^[a-z][a-z0-9_]{0,31}$",
                    example = "creature") String recordType,
            @Schema(description = "Its tier in the campaign's scope: campaign for an artifact",
                    allowableValues = {"campaign", "system", "other"}) String tier,
            @Schema(description = "True for another artifact of the campaign") boolean artifact,
            @Schema(description = "The name of the material it was extracted from; null for an artifact",
                    types = {"string", "null"}, example = "Livro dos Monstros") String materialName) {

        static RelationTargetView of(RecordRelations.RelationTarget target) {
            return new RelationTargetView(target.id().toString(), target.name(), target.recordType(),
                    target.tier().word(), target.artifact(), target.materialName());
        }

        @Override
        public String toString() {
            return "RelationTargetView[id=" + id + ", tier=" + tier + ", artifact=" + artifact + "]";
        }
    }

    @Schema(name = "RelationTargetPage", requiredProperties = {"items", "total"},
            description = "A page of the records an artifact can be related to (contract 1.51.0)")
    record RelationTargetPageView(
            @ArraySchema(schema = @Schema(implementation = RelationTargetView.class)) List<RelationTargetView> items,
            @Schema(description = "How many records match in all, whatever the page", format = "int64", minimum = "0",
                    example = "12") long total) {
    }

    @PostMapping(path = RELATIONS, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "relateArtifact", summary = "The GM relates one of the account's campaigns' artifacts to a"
            + " record (contract 1.51.0), answering the artifact as getArtifact does. The record is a live extracted"
            + " record of any tier of the campaign's scope, or another live artifact of the campaign. A relation has no"
            + " direction: it shows on both records, and relating a record that is related to the artifact already,"
            + " from either side, stores nothing and answers 200. A relation changes no answer."
            + " artifact.relation_target (400) for a record that is no target (out of the scope, a deleted one,"
            + " another campaign's artifact, another account's, unknown or malformed, the artifact itself, or none);"
            + " artifact.relations_full (409) when the artifact, or the artifact it is related to, has 50 relations"
            + " already; request.invalid (400) for a malformed body;" + NOT_FOUND + " nothing changes then;"
            + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<ArtifactView> relateArtifact(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(description = "The artifact's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("artifactId") String artifactId,
            @RequestBody RelationBody body) {
        UUID campaign = parse(campaignId).orElseThrow(ArtifactController::notFound);
        UUID artifact = parse(artifactId).orElseThrow(ArtifactController::notFound);
        if (body == null) {
            throw invalid();
        }
        // A missing or malformed id is a record that is no target: the same refusal, after the artifact is found.
        UUID record = parse(body.recordId()).orElse(null);
        Artifacts.Artifact related = artifacts.relate(campaign, artifact, record)
                .orElseThrow(ArtifactController::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ArtifactView.of(related));
    }

    @DeleteMapping(path = RELATION)
    @Operation(operationId = "unrelateArtifact", summary = "The GM removes the relation between one of the account's"
            + " campaigns' artifacts and a record (contract 1.51.0; 204, no body), whichever side made it: it shows on"
            + " neither record any more. A record the artifact is not related to (unknown or malformed included)"
            + " answers 204 too, so repeating it is safe." + NOT_FOUND + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "204", description = "The artifact is not related to the record"))
    ResponseEntity<Void> unrelateArtifact(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(description = "The artifact's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("artifactId") String artifactId,
            @Parameter(description = "The related record's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("recordId") String recordId) {
        UUID campaign = parse(campaignId).orElseThrow(ArtifactController::notFound);
        UUID artifact = parse(artifactId).orElseThrow(ArtifactController::notFound);
        if (!artifacts.unrelate(campaign, artifact, parse(recordId).orElse(null))) {
            throw notFound();
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping(path = RELATION_TARGETS, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listRelationTargets", summary = "The records one of the account's campaigns' artifacts"
            + " can be related to (contract 1.51.0): of one record type, the live extracted records of every tier of"
            + " the campaign's scope (its stored settings) and the campaign's other live artifacts, each with its tier"
            + " (campaign for an artifact), alphabetical by name, a page at a time with the total; the artifact itself"
            + " and the records it is related to already are left out; q keeps those whose name contains it, case and"
            + " accents ignored." + NOT_FOUND + " request.invalid (400) for a missing or malformed recordType, a q over"
            + " 200 characters, a limit outside 1 to 50 or an offset outside 0 to 100000;" + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "recordType", in = ParameterIn.QUERY, required = true, description = "The record"
                        + " type key of the records listed", schema = @Schema(type = "string",
                        pattern = "^[a-z][a-z0-9_]{0,31}$")),
                @Parameter(name = "q", in = ParameterIn.QUERY, description = "Only the records whose name contains"
                        + " this, case and accents ignored; at most 200 characters, blank for all",
                        schema = @Schema(type = "string", maxLength = 200)),
                @Parameter(name = "limit", in = ParameterIn.QUERY, description = "At most this many, 1 to 50",
                        schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "50",
                                defaultValue = "20")),
                @Parameter(name = "offset", in = ParameterIn.QUERY, description = "How many of the first to skip, 0 to"
                        + " 100000, for a numbered page", schema = @Schema(type = "integer", format = "int32",
                        minimum = "0", maximum = "100000", defaultValue = "0"))})
    ResponseEntity<RelationTargetPageView> listRelationTargets(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(description = "The artifact's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("artifactId") String artifactId,
            @Parameter(hidden = true) @RequestParam(value = "recordType", required = false) String recordType,
            @Parameter(hidden = true) @RequestParam(value = "q", required = false) String query,
            @Parameter(hidden = true) @RequestParam(value = "limit", required = false) String limit,
            @Parameter(hidden = true) @RequestParam(value = "offset", required = false) String offset) {
        UUID campaign = parse(campaignId).orElseThrow(ArtifactController::notFound);
        UUID artifact = parse(artifactId).orElseThrow(ArtifactController::notFound);
        if (recordType == null || !KEY.matcher(recordType).matches()
                || (query != null && query.strip().codePointCount(0, query.strip().length())
                > RecordRelations.MAX_QUERY)) {
            throw invalid();
        }
        int size = limit(limit);
        int from = offset(offset, null);
        RecordRelations.RelationTargetPage page = artifacts.relationTargets(campaign, artifact, recordType, query,
                from, size).orElseThrow(ArtifactController::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new RelationTargetPageView(page.items()
                .stream().map(RelationTargetView::of).toList(), page.total()));
    }

    @Schema(name = "ArtifactSharing", requiredProperties = "campaignIds",
            description = "The whole set of campaigns an artifact is shared with from now on (contract 1.52.0)")
    record SharingBody(
            @ArraySchema(arraySchema = @Schema(description = "The ids of the account's other campaigns of the"
                    + " artifact's system, archived or not; at most 200; empty unshares it from every campaign"),
                    schema = @Schema(type = "string", format = "uuid"), maxItems = Artifacts.MAX_SHARES)
            List<String> campaignIds) {
    }

    @PutMapping(path = SHARING, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "setArtifactSharing", summary = "The GM shares one of the account's campaigns' artifacts"
            + " with exactly these other campaigns of its system (contract 1.52.0), answering the artifact as"
            + " getArtifact does, with sharedWith. It stays one artifact: an edit shows everywhere. From the next"
            + " question, in each campaign it is shared with, it is a source of the system tier while that tier is on,"
            + " cited under that tier, and it opens read-only there; its override and relations apply in its own"
            + " campaign only. A campaign it no longer names stops at once: a reference stored there reads as removed."
            + " artifact.share_target (400) when any id is not another of the account's campaigns of the artifact's"
            + " system (its own campaign, another system's, another account's, one being deleted, unknown or"
            + " malformed); request.invalid (400) for a malformed body or more than 200 ids; nothing is stored then;"
            + NOT_FOUND + " request.not_found (404) too from any campaign but the artifact's own;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<ArtifactView> setArtifactSharing(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(description = "The artifact's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("artifactId") String artifactId,
            @RequestBody SharingBody body) {
        UUID campaign = parse(campaignId).orElseThrow(ArtifactController::notFound);
        UUID artifact = parse(artifactId).orElseThrow(ArtifactController::notFound);
        if (body == null || body.campaignIds() == null || body.campaignIds().size() > Artifacts.MAX_SHARES) {
            throw invalid();
        }
        // A null or malformed id is a campaign that is no target: the same refusal, after the artifact is found.
        List<UUID> targets = body.campaignIds().stream().map(id -> parse(id).orElse(null)).toList();
        Artifacts.Artifact shared = artifacts.share(campaign, artifact, targets)
                .orElseThrow(ArtifactController::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ArtifactView.of(shared));
    }

    /** A version's number in a path or a cursor: 1 to 999,999,999; anything else is none. */
    private static Optional<Integer> number(String text) {
        return text != null && NUMBER.matcher(text).matches() ? Optional.of(Integer.parseInt(text))
                : Optional.empty();
    }

    /** The newest version the GM saw, from a body: 1 or more, or {@code request.invalid}. */
    private static int checkedVersion(Integer version) {
        if (version == null || version < 1) {
            throw invalid();
        }
        return version;
    }

    /** A malformed id is no different from an unknown one. */
    static Optional<UUID> parse(String id) {
        return id != null && UUID_TEXT.matcher(id).matches() ? Optional.of(UUID.fromString(id)) : Optional.empty();
    }

    /** A page's size: the default without one, else 1 to 50, or {@code request.invalid}. */
    static int limit(String limit) {
        if (limit == null) {
            return Artifacts.DEFAULT_LIMIT;
        }
        if (!LIMIT.matcher(limit).matches() || Integer.parseInt(limit) > Artifacts.MAX_LIMIT) {
            throw invalid();
        }
        return Integer.parseInt(limit);
    }

    /**
     * The position a page starts at: 0 without one, else 0 to {@value Artifacts#MAX_OFFSET}; {@code request.invalid}
     * for anything else, and for a position above 0 together with a cursor (a page starts at one or the other).
     */
    static int offset(String offset, Object cursor) {
        if (offset == null) {
            return 0;
        }
        if (!OFFSET_TEXT.matcher(offset).matches() || Integer.parseInt(offset) > Artifacts.MAX_OFFSET
                || (cursor != null && Integer.parseInt(offset) > 0)) {
            throw invalid();
        }
        return Integer.parseInt(offset);
    }

    static ApiException invalid() {
        return new ApiException(ApiProblem.forStatus(400));
    }

    static ApiException notFound() {
        return new ApiException(ApiProblem.forStatus(404));
    }
}
