package io.github.gmshield.artifacts.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.gmshield.artifacts.service.Artifacts;
import io.github.gmshield.conversations.service.TurnReference;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
 */
@RestController
@Tag(name = "artifacts")
class ArtifactController {

    static final String ARTIFACTS = "/api/v1/campaigns/{campaignId}/artifacts";
    static final String ARTIFACT = "/api/v1/campaigns/{campaignId}/artifacts/{artifactId}";
    static final String VERSIONS = ARTIFACT + "/versions";
    static final String VERSION = VERSIONS + "/{number}";
    static final String RESTORE = VERSION + "/restore";
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
    private static final String NOT_FOUND = " request.not_found (404) for a campaign that is not the account's, and for"
            + " an artifact that is not this campaign's (unknown, another account's, another campaign's or malformed);"
            + " none is told apart;";

    private final Artifacts artifacts;

    ArtifactController(Artifacts artifacts) {
        this.artifacts = artifacts;
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

    @Schema(name = "Artifact", requiredProperties = {"id", "campaignId", "recordType", "name", "fields", "content",
        "version", "currentVersion", "createdAt", "conversationId", "references"}, description = "An artifact with its"
            + " newest version, or one of its versions when asked by number (contract 1.42.0; currentVersion and"
            + " versions from 1.44.0; conversationId and references from 1.48.0)")
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
            List<ReferenceView> references) {

        static ArtifactView of(Artifacts.Artifact artifact) {
            return new ArtifactView(artifact.id().toString(), artifact.campaignId().toString(), artifact.recordType(),
                    artifact.name(), artifact.fields().stream().map(field -> new FieldView(field.name(), field.value()))
                    .toList(), artifact.content(), artifact.version(), artifact.currentVersion(),
                    artifact.createdAt().toString(),
                    artifact.conversationId() == null ? null : artifact.conversationId().toString(),
                    artifact.references().stream().map(ReferenceView::of).toList());
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
                return new ReferenceView(reference.number(), reference.kind(), null, null, null, null, null,
                        cited.id().toString(), cited.artifactVersion(), cited.type(), cited.name(), fields,
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

    @Schema(name = "ArtifactSummary", requiredProperties = {"id", "recordType", "name", "version", "createdAt"},
            description = "An artifact in a list (contract 1.42.0)")
    record SummaryView(
            @Schema(description = "The artifact's id", format = "uuid", example = ID_EXAMPLE) String id,
            @Schema(description = "The key of its record type", pattern = "^[a-z][a-z0-9_]{0,31}$", example = "item")
            String recordType,
            @Schema(description = "Its name, in its newest version", example = "Espada de Kael") String name,
            @Schema(description = "The number of its newest version, from 1", format = "int32", minimum = "1",
                    example = "1") int version,
            @Schema(description = "When it was written", format = "date-time") String createdAt) {

        static SummaryView of(Artifacts.Summary summary) {
            return new SummaryView(summary.id().toString(), summary.recordType(), summary.name(), summary.version(),
                    summary.createdAt().toString());
        }

        @Override
        public String toString() {
            return "SummaryView[id=" + id + ", recordType=" + recordType + "]";
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
                    + " whatever the page", format = "int64", minimum = "0", example = "3") long total,
            @ArraySchema(arraySchema = @Schema(description = "The record types in use in the campaign's artifacts,"
                    + " whatever the filter, by key, each with its count; empty for a campaign without artifacts"),
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
            + " campaign with their counts. request.not_found (404) for a campaign that is not the account's (unknown,"
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
            + " version (contract 1.42.0). request.not_found (404) for a campaign that is not the account's, and for an"
            + " artifact that is not this campaign's (unknown, another account's, another campaign's or malformed);"
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

    @GetMapping(path = VERSION, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getArtifactVersion", summary = "One version of one of the account's campaigns' artifacts,"
            + " by its number (contract 1.44.0), as a reference that cited it opens it; currentVersion names the newest."
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
            + NOT_FOUND + SESSION_SUMMARY,
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
            + " the system's; request.invalid (400) without a field for a malformed body;" + STALE + NOT_FOUND
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
    private static Optional<UUID> parse(String id) {
        return id != null && UUID_TEXT.matcher(id).matches() ? Optional.of(UUID.fromString(id)) : Optional.empty();
    }

    /** A page's size: the default without one, else 1 to 50, or {@code request.invalid}. */
    private static int limit(String limit) {
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
    private static int offset(String offset, Object cursor) {
        if (offset == null) {
            return 0;
        }
        if (!OFFSET_TEXT.matcher(offset).matches() || Integer.parseInt(offset) > Artifacts.MAX_OFFSET
                || (cursor != null && Integer.parseInt(offset) > 0)) {
            throw invalid();
        }
        return Integer.parseInt(offset);
    }

    private static ApiException invalid() {
        return new ApiException(ApiProblem.forStatus(400));
    }

    private static ApiException notFound() {
        return new ApiException(ApiProblem.forStatus(404));
    }
}
