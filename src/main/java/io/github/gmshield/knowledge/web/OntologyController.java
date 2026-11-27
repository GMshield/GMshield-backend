package io.github.gmshield.knowledge.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.gmshield.knowledge.service.Ontology;
import io.github.gmshield.knowledge.service.RecordRelations;
import io.github.gmshield.knowledge.service.Records;
import io.github.gmshield.materials.service.RecordSources;
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
import java.util.List;
import java.util.Optional;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A system's ontology (Story 4.1): its record types, each with its record count, and the rename and removal of one that
 * is not core. The owner is the session's account, set by the request edge ({@code accounts.web}) before any of these
 * runs; nothing here takes an owner from the request. Another owner's id, an unknown id, a malformed id and an item of a
 * system being deleted are the same {@code request.not_found} (404). A core type renamed or removed is
 * {@code ontology.core_record_type} (409) and nothing changes. A malformed body, limit or offset is
 * {@code request.invalid} (400). Every answer is {@code no-store}.
 *
 * <p>Story 4.3: a record type's records, alphabetical by name, a page at a time; one record as its system shows it (its
 * name, its record type, its fields and its source references); and a page of the record's version with its picture,
 * the same shape as a material's page, streamed as WebP with {@code nosniff}. Only the records of the system's accepted
 * versions are read ({@link Records}); any other record is the same {@code request.not_found}, and no name or field
 * leaves then.
 *
 * <p>Story 4.3 part B: one record as a campaign shows it (a search's record result), the same answer, for a record of a
 * version in the campaign's current scope; its references open the source view on the ground of that scope, whose
 * pages the conversations module serves. From Story 5.5 part B (contract 1.51.0) that read alone also answers
 * {@code related}, the campaign's live artifacts related to the record.
 *
 * <p>Story 4.5: a record is corrected on either route ({@code PATCH}), under the same visibility as its read: its name,
 * type and fields, on the version it names ({@code request.stale_version}, 409, when it moved); anything the read would
 * not answer is the same {@code request.not_found}.
 */
@RestController
@Tag(name = "ontology")
class OntologyController {

    static final String SYSTEM_RECORD_TYPES = "/api/v1/systems/{id}/record-types";
    static final String RECORD_TYPE = "/api/v1/record-types/{id}";
    static final String RECORD_TYPE_RECORDS = "/api/v1/record-types/{id}/records";
    static final String SYSTEM_RECORD = "/api/v1/systems/{systemId}/records/{recordId}";
    static final String SYSTEM_RECORD_PAGE = SYSTEM_RECORD + "/pages/{page}";
    static final String SYSTEM_RECORD_PICTURE = SYSTEM_RECORD_PAGE + "/picture";
    static final String CAMPAIGN_RECORD = "/api/v1/campaigns/{campaignId}/records/{recordId}";
    static final String WEBP = "image/webp";
    /** A UUID in its canonical text form; anything else is an id that exists nowhere. */
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Pattern LIMIT = Pattern.compile("[1-9][0-9]?");
    /** A page's position: 0, or up to six digits with no leading zero. */
    private static final Pattern OFFSET_TEXT = Pattern.compile("0|[1-9][0-9]{0,5}");
    /** A page number from 1: no sign, no leading zero, at most seven digits. */
    private static final Pattern NUMBER = Pattern.compile("[1-9][0-9]{0,6}");
    private static final String SESSION = "X-Session-Token";
    private static final String SESSION_SUMMARY = " session.required (401) without a session.";

    private final Ontology ontology;
    private final Records records;

    OntologyController(Ontology ontology, Records records) {
        this.ontology = ontology;
        this.records = records;
    }

    @Schema(name = "Origin", requiredProperties = {"kind", "proposalId", "materialId", "materialName"})
    record OriginView(
            @Schema(description = "core for a core type; proposal for a record type or term the GM approved from a"
                    + " material's proposal", allowableValues = {"core", "proposal"}, example = "proposal") String kind,
            @Schema(description = "The approving proposal; null for a core type", format = "uuid",
                    types = {"string", "null"}, example = "0190a0b0-0000-7000-8000-000000000030") String proposalId,
            @Schema(description = "The material that proposed it; null for a core type, and once that material was"
                    + " deleted", format = "uuid", types = {"string", "null"},
                    example = "0190a0b0-0000-7000-8000-000000000004") String materialId,
            @Schema(description = "That material's name; null when materialId is null", types = {"string", "null"},
                    example = "Livro do Jogador") String materialName) {

        static final OriginView CORE = new OriginView("core", null, null, null);

        static OriginView of(Ontology.Origin origin) {
            if (origin == null) {
                return CORE;
            }
            return new OriginView("proposal", origin.proposalId().toString(),
                    origin.materialId() == null ? null : origin.materialId().toString(), origin.materialName());
        }

        @Override
        public String toString() {
            return "OriginView[kind=" + kind + ", proposalId=" + proposalId + ", materialId=" + materialId + "]";
        }
    }

    @Schema(name = "RecordType", requiredProperties = {"id", "name", "core", "key", "origin", "records", "version"})
    record RecordTypeView(
            @Schema(description = "The record type's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000003")
            String id,
            @Schema(description = "The record type's name. For a core type it is the core key in English (\"character"
                    + " sheet\"); a client shows its own translation of core instead", example = "character sheet")
            String name,
            @Schema(description = "The core key of a core type, which can be neither renamed nor removed; null for a type"
                    + " that is not core", types = {"string", "null"}, allowableValues = {"rule", "spell", "item",
                    "creature", "character_sheet", "encounter", "dungeon", "note"}, example = "character_sheet")
            String core,
            @Schema(description = "The record type's stable key (contract 1.38.0): a core type's core key, or the slug of"
                    + " an approved type's name at its approval (lower-case letters, digits and underscores); it never"
                    + " changes, a rename included", pattern = "^[a-z0-9_]{1,32}$", example = "feat") String key,
            @Schema(description = "Where the record type came from (contract 1.38.0): core, or the approving proposal"
                    + " and its material") OriginView origin,
            @Schema(description = "How many records of the system have this type: from contract 1.34.0, the records"
                    + " of the accepted versions of the materials of the system and of its campaigns",
                    format = "int64", minimum = "0",
                    example = "0") long records,
            @Schema(description = "The record type's version: a rename names it and raises it by one", format = "int64",
                    example = "0") long version) {

        static RecordTypeView of(Ontology.RecordType type) {
            return new RecordTypeView(type.id().toString(), type.name(), type.core(), type.key(),
                    type.core() == null ? OriginView.of(type.origin()) : OriginView.CORE, type.records(),
                    type.version());
        }

        @Override
        public String toString() {
            return "RecordTypeView[id=" + id + ", core=" + core + ", version=" + version + "]";
        }
    }

    @Schema(name = "RecordTypePage", requiredProperties = {"items", "total"})
    record RecordTypePageView(
            @ArraySchema(schema = @Schema(implementation = RecordTypeView.class)) List<RecordTypeView> items,
            @Schema(description = "How many record types the system has in all, whatever the page", format = "int64",
                    example = "8") long total) {
    }

    @Schema(name = "RecordTypeChange", requiredProperties = {"version", "name"})
    record RecordTypeChangeBody(
            @Schema(description = "The version the change was made on, as the record type was read", format = "int64",
                    minimum = "0", example = "0") Long version,
            @Schema(description = "The new name, 1 to 120 code points after trimming; unique within the system, core"
                    + " types included, whatever the case", minLength = 1, maxLength = 120, example = "vehicle")
            String name) {

        @Override
        public String toString() {
            return "RecordTypeChangeBody[version=" + version + "]";
        }
    }

    @GetMapping(path = SYSTEM_RECORD_TYPES, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listRecordTypes", summary = "The record types of one of the account's game systems, each"
            + " with its record count: the eight core types first, in their fixed order (rule, spell, item, creature,"
            + " character_sheet, encounter, dungeon, note), then the others oldest first. A page starts at an offset and"
            + " names the total of the list. request.not_found (404) for any other system id, a system being deleted"
            + " included; request.invalid (400) for a limit outside 1 to 50 or an offset outside 0 to 100000;"
            + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "limit", in = ParameterIn.QUERY, description = "At most this many, 1 to 50",
                        schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "50",
                                defaultValue = "20")),
                @Parameter(name = "offset", in = ParameterIn.QUERY, description = "How many to skip, 0 to 100000, for a"
                        + " numbered page", schema = @Schema(type = "integer", format = "int32", minimum = "0",
                                maximum = "100000", defaultValue = "0"))})
    ResponseEntity<RecordTypePageView> recordTypes(
            @Parameter(description = "The system's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(hidden = true) @RequestParam(value = "limit", required = false) String limit,
            @Parameter(hidden = true) @RequestParam(value = "offset", required = false) String offset) {
        int size = limit(limit);
        int from = offset(offset);
        Ontology.RecordTypePage page = parse(id).flatMap(systemId -> ontology.recordTypes(systemId, from, size))
                .orElseThrow(OntologyController::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new RecordTypePageView(
                page.items().stream().map(RecordTypeView::of).toList(), page.total()));
    }

    @GetMapping(path = RECORD_TYPE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getRecordType", summary = "One of the account's record types, with its record count;"
            + " request.not_found (404) for any other id;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<RecordTypeView> recordType(
            @Parameter(description = "The record type's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        return found(parse(id).flatMap(ontology::recordType).map(RecordTypeView::of));
    }

    @PatchMapping(path = RECORD_TYPE, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "changeRecordType", summary = "Renames one of the account's record types on the version it"
            + " names: the record type as stored, its version raised by one. ontology.core_record_type (409) for a core"
            + " type, which can be neither renamed nor removed; request.stale_version (409) when the record type has"
            + " left that version; ontology.record_type_name_taken (409) when another record type of the system, a core"
            + " one included, has the new name (trimmed, whatever the case); ontology.record_type_name_invalid (400) for"
            + " a name outside its limits (params min and max); request.invalid (400) without a version or a name, or"
            + " for a malformed body; request.not_found (404) for any other id; nothing changes then;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<RecordTypeView> changeRecordType(
            @Parameter(description = "The record type's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @RequestBody RecordTypeChangeBody body) {
        UUID recordTypeId = parse(id).orElseThrow(OntologyController::notFound);
        if (body == null || body.version() == null || body.version() < 0 || body.name() == null) {
            throw invalid();
        }
        return found(ontology.rename(recordTypeId, body.version(), body.name()).map(RecordTypeView::of));
    }

    @DeleteMapping(path = RECORD_TYPE)
    @Operation(operationId = "deleteRecordType", summary = "Removes one of the account's record types that is not core"
            + " (204, no body). ontology.core_record_type (409) for a core type, which can be neither renamed nor"
            + " removed; ontology.record_type_in_use (409, contract 1.34.0) for a type that types records;"
            + " request.not_found (404) for any other id; nothing changes then;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "204", description = "The record type was removed"))
    ResponseEntity<Void> deleteRecordType(
            @Parameter(description = "The record type's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        if (!parse(id).map(ontology::delete).orElse(false)) {
            throw notFound();
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @Schema(name = "RecordSummary", requiredProperties = {"id", "name", "materialName", "page"})
    record RecordSummaryView(
            @Schema(description = "The record's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000010")
            String id,
            @Schema(description = "The record's name, as it was extracted", example = "Fire Bolt") String name,
            @Schema(description = "The name of the material the record was read from", example = "Livro do Jogador")
            String materialName,
            @Schema(description = "The first page the record was read from", format = "int32", minimum = "1",
                    example = "242") int page) {

        static RecordSummaryView of(Records.Summary summary) {
            return new RecordSummaryView(summary.id().toString(), summary.name(), summary.materialName(),
                    summary.page());
        }

        @Override
        public String toString() {
            return "RecordSummaryView[id=" + id + ", page=" + page + "]";
        }
    }

    @Schema(name = "RecordPage", requiredProperties = {"items", "total"})
    record RecordPageView(
            @ArraySchema(schema = @Schema(implementation = RecordSummaryView.class)) List<RecordSummaryView> items,
            @Schema(description = "How many records the record type has in all, whatever the page", format = "int64",
                    example = "230") long total) {
    }

    @Schema(name = "RecordTypeOfRecord", requiredProperties = {"id", "core", "key", "name"})
    record RecordTypeOfView(
            @Schema(description = "The record type's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000003")
            String id,
            @Schema(description = "The core key of a core record type: a client shows its own translation of it; null"
                    + " for a type the GM approved (contract 1.39.0), whose name is shown", types = {"string", "null"},
                    allowableValues = {"rule", "spell", "item", "creature",
                    "character_sheet", "encounter", "dungeon", "note"}, example = "spell") String core,
            @Schema(description = "The record type's stable key (contract 1.39.0), the one its records store",
                    pattern = "^[a-z][a-z0-9_]{0,31}$", example = "spell") String key,
            @Schema(description = "The record type's stored name", example = "spell") String name) {

        static RecordTypeOfView of(Records.TypeOf type) {
            return new RecordTypeOfView(type.id().toString(), type.core(), type.key(), type.name());
        }
    }

    @Schema(name = "RecordField", requiredProperties = {"name", "value"})
    record RecordFieldView(
            @Schema(description = "The field's name, as it was extracted", example = "Alcance") String name,
            @Schema(description = "The field's value, as it was extracted", example = "36 metros") String value) {

        @Override
        public String toString() {
            return "RecordFieldView";
        }
    }

    @Schema(name = "RecordReference", requiredProperties = {"versionId", "materialName", "sectionPath", "page",
        "passageIds"})
    record RecordReferenceView(
            @Schema(description = "The material version the record was read from", format = "uuid",
                    example = "0190a0b0-0000-7000-8000-000000000020") String versionId,
            @Schema(description = "The material's name", example = "Livro do Jogador") String materialName,
            @ArraySchema(schema = @Schema(description = "A section title, outermost first", example = "Magias"))
            List<String> sectionPath,
            @Schema(description = "The page", format = "int32", minimum = "1", example = "242") int page,
            @ArraySchema(arraySchema = @Schema(description = "The ids of the record's passages at this place, so the"
                    + " source view marks them on the page as it marks an answer's cited passages"), minItems = 1,
                    schema = @Schema(type = "string", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000009"))
            List<String> passageIds) {

        static RecordReferenceView of(Records.Reference reference) {
            return new RecordReferenceView(reference.versionId().toString(), reference.materialName(),
                    reference.sectionPath(), reference.page(),
                    reference.passageIds().stream().map(UUID::toString).toList());
        }

        @Override
        public String toString() {
            return "RecordReferenceView[versionId=" + versionId + ", page=" + page + "]";
        }
    }

    @Schema(name = "RecordRelatedArtifact", requiredProperties = {"artifactId", "name", "recordType"},
            description = "An artifact of the campaign related to the record (contract 1.51.0)")
    record RelatedArtifactView(
            @Schema(description = "The artifact's id; its page is in the campaign", format = "uuid") String artifactId,
            @Schema(description = "The artifact's name, as its newest version has it", example = "Cripta") String name,
            @Schema(description = "The key of the artifact's record type", pattern = "^[a-z][a-z0-9_]{0,31}$",
                    example = "encounter") String recordType) {

        static RelatedArtifactView of(RecordRelations.RelatedArtifact artifact) {
            return new RelatedArtifactView(artifact.artifactId().toString(), artifact.name(), artifact.recordType());
        }

        @Override
        public String toString() {
            return "RelatedArtifactView[artifactId=" + artifactId + ", recordType=" + recordType + "]";
        }
    }

    @Schema(name = "Record", requiredProperties = {"id", "name", "recordType", "fields", "references", "version"})
    record RecordView(
            @Schema(description = "The record's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000010")
            String id,
            @Schema(description = "The record's name, as it was extracted or as the GM corrected it", example = "Fire Bolt")
            String name,
            @Schema(description = "The system's record type of the record") RecordTypeOfView recordType,
            @ArraySchema(arraySchema = @Schema(description = "The record's fields, in stored order; empty for a record"
                    + " without fields"), schema = @Schema(implementation = RecordFieldView.class))
            List<RecordFieldView> fields,
            @ArraySchema(arraySchema = @Schema(description = "Where the record was read from: one reference per"
                    + " distinct version, section path and page, in page order"),
                    schema = @Schema(implementation = RecordReferenceView.class))
            List<RecordReferenceView> references,
            @Schema(description = "The record's version (contract 1.40.0): a correction names it and raises it by one",
                    format = "int64", minimum = "0", example = "0") long version,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @ArraySchema(arraySchema = @Schema(description = "Only on getCampaignRecord (contract 1.51.0): the live"
                    + " artifacts of the campaign related to the record, oldest relation first; empty for none. Absent"
                    + " from every other answer"), schema = @Schema(implementation = RelatedArtifactView.class))
            List<RelatedArtifactView> related) {

        static RecordView of(Records.Detail detail) {
            return new RecordView(detail.id().toString(), detail.name(), RecordTypeOfView.of(detail.recordType()),
                    detail.fields().stream().map(field -> new RecordFieldView(field.name(), field.value())).toList(),
                    detail.references().stream().map(RecordReferenceView::of).toList(), detail.version(),
                    detail.related() == null ? null : detail.related().stream().map(RelatedArtifactView::of)
                            .toList());
        }

        @Override
        public String toString() {
            return "RecordView[id=" + id + ", fields=" + fields.size() + ", references=" + references.size()
                    + ", version=" + version + "]";
        }
    }

    @Schema(name = "RecordCorrectionField", requiredProperties = {"name", "value"})
    record RecordCorrectionFieldBody(
            @Schema(description = "The field's name: 1 to 200 code points after trimming", minLength = 1,
                    maxLength = 200, example = "Alcance") String name,
            @Schema(description = "The field's value: 1 to 8,000 code points after trimming", minLength = 1,
                    maxLength = 8000, example = "36 metros") String value) {

        @Override
        public String toString() {
            return "RecordCorrectionFieldBody";
        }
    }

    @Schema(name = "RecordCorrection", requiredProperties = {"version", "name", "recordType", "fields"})
    record RecordCorrectionBody(
            @Schema(description = "The version the correction was made on, as the record was read", format = "int64",
                    minimum = "0", example = "0") Long version,
            @Schema(description = "The record's name: 1 to 200 code points after trimming", minLength = 1,
                    maxLength = 200, example = "Fire Bolt") String name,
            @Schema(description = "The key of one of the system's record types, core or approved (the campaign's"
                    + " system's on a campaign's route)", pattern = "^[a-z][a-z0-9_]{0,31}$", example = "spell")
            String recordType,
            @ArraySchema(arraySchema = @Schema(description = "The record's fields in order, at most 64; each replaces"
                    + " the stored ones, so a field left out is removed"), maxItems = 64,
                    schema = @Schema(implementation = RecordCorrectionFieldBody.class))
            List<RecordCorrectionFieldBody> fields) {

        @Override
        public String toString() {
            return "RecordCorrectionBody[version=" + version + ", recordType=" + recordType + ", fields="
                    + (fields == null ? 0 : fields.size()) + "]";
        }

        /** The correction, or {@code request.invalid} for a body without a version, name, type or fields. */
        Records.Correction correction() {
            if (version == null || version < 0 || name == null || recordType == null || fields == null
                    || fields.stream().anyMatch(field -> field == null || field.name() == null
                    || field.value() == null)) {
                throw invalid();
            }
            return new Records.Correction(version, name, recordType, fields.stream()
                    .map(field -> new Records.Field(field.name(), field.value())).toList());
        }
    }

    /** A text line of a picture page and its box. */
    record LineView(String text, List<Double> box) {
        @Override
        public String toString() {
            return "LineView[box=" + box + "]";
        }
    }

    /** A passage on the page: its boxes, or its range in a text page's text. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SpotView(String id, List<List<Double>> boxes, List<Integer> range) {
    }

    /** A picture's size. */
    record PictureView(int width, int height) {
    }

    /** A page of the record's version: the same shape as {@code MaterialPageView}, so one view renders both. */
    record RecordSourcePageView(int page, int pageTotal, PictureView picture, List<LineView> lines, String text,
                                List<SpotView> passages, String outcome) {

        static RecordSourcePageView of(RecordSources.Page page) {
            return new RecordSourcePageView(page.page(), page.pageTotal(), page.pictureWidth() == null
                    || page.pictureHeight() == null ? null : new PictureView(page.pictureWidth(), page.pictureHeight()),
                    page.lines().stream().map(line -> new LineView(line.text(), line.box())).toList(), page.text(),
                    page.passages().stream().map(spot -> new SpotView(spot.id().toString(), spot.boxes(),
                            spot.range())).toList(), page.outcome());
        }

        @Override
        public String toString() {
            return "RecordSourcePageView[page=" + page + ", pageTotal=" + pageTotal + "]";
        }
    }

    private static final String RECORD_SUMMARY = " Only the records of the system's accepted versions are read: those"
            + " of the ready versions of the materials attached to the system and of those of its campaigns."
            + " request.not_found (404) for a system that is not the account's (another account's, unknown, malformed or"
            + " being deleted), and for a record that is not one of those (another account's, unknown, malformed, of a"
            + " version that needs review or is superseded, or of another system's materials): none is told apart, and"
            + " no name or field is answered then;";

    @GetMapping(path = RECORD_TYPE_RECORDS, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listRecords", summary = "The records of one of the account's record types (contract"
            + " 1.35.0), alphabetical by name (case ignored; accents order only names that are otherwise equal, the"
            + " unaccented first; then by id), each as it was extracted: two records"
            + " with the same name are two rows. Only the records of the accepted versions of the system's materials and"
            + " of its campaigns' materials are listed; a type that is not core has none yet. A page starts at an offset"
            + " and names the total of the list. request.not_found (404) for any other record type id, one of a system"
            + " being deleted included; request.invalid (400) for a limit outside 1 to 50 or an offset outside 0 to"
            + " 100000;" + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "limit", in = ParameterIn.QUERY, description = "At most this many, 1 to 50",
                        schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "50",
                                defaultValue = "20")),
                @Parameter(name = "offset", in = ParameterIn.QUERY, description = "How many to skip, 0 to 100000, for a"
                        + " numbered page", schema = @Schema(type = "integer", format = "int32", minimum = "0",
                                maximum = "100000", defaultValue = "0"))})
    ResponseEntity<RecordPageView> records(
            @Parameter(description = "The record type's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(hidden = true) @RequestParam(value = "limit", required = false) String limit,
            @Parameter(hidden = true) @RequestParam(value = "offset", required = false) String offset) {
        int size = limit(limit);
        int from = offset(offset);
        Records.SummaryPage page = parse(id).flatMap(typeId -> records.records(typeId, from, size))
                .orElseThrow(OntologyController::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new RecordPageView(
                page.items().stream().map(RecordSummaryView::of).toList(), page.total()));
    }

    @GetMapping(path = SYSTEM_RECORD, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getRecord", summary = "One record as one of the account's systems shows it (contract"
            + " 1.35.0): its name, the system's record type of its core key, its fields in stored order, and its source"
            + " references, one per distinct version, section path and page, in page order." + RECORD_SUMMARY
            + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<RecordView> record(
            @Parameter(description = "The system's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("systemId") String systemId,
            @Parameter(description = "The record's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("recordId") String recordId) {
        return found(parse(systemId).flatMap(system -> parse(recordId).flatMap(record -> records.record(system, record)))
                .map(RecordView::of));
    }

    @GetMapping(path = CAMPAIGN_RECORD, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getCampaignRecord", summary = "One record as one of the account's campaigns shows it"
            + " (contract 1.36.0, a search's record result): the same answer as getRecord, its record type the"
            + " campaign's system's type of its core key. Only a record of an accepted version in the campaign's current"
            + " scope is read (its tiers and materials switched on, any tier); its references open the source view on"
            + " the ground of that scope (getScopeSourcePage). request.not_found (404) for a campaign that is not the"
            + " account's (another account's, unknown, malformed or being deleted), and for a record that is not one of"
            + " those (another account's, unknown, malformed, of a version that needs review or is superseded, or of a"
            + " material switched off or outside the scope): none is told apart, and no name or field is answered then."
            + " From contract 1.51.0 it alone also answers related, the campaign's live artifacts related to the"
            + " record;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<RecordView> campaignRecord(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(description = "The record's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("recordId") String recordId) {
        return found(parse(campaignId).flatMap(campaign -> parse(recordId)
                .flatMap(record -> records.inCampaign(campaign, record))).map(RecordView::of));
    }

    private static final String CORRECTION_SUMMARY = " The name, the type and the fields are replaced together: the"
            + " name and each field's name and value are trimmed. The record keeps its id and its source references; the"
            + " next question reads the correction, and its page is indexed again by a job. request.stale_version (409)"
            + " when the record has left that version; record.name_invalid (400) for a name outside 1 to 200 code points;"
            + " record.fields_invalid (400) for more than 64 fields; record.field_name_invalid and"
            + " record.field_value_invalid (400, params min, max and index, the field's position from 0) for a field's"
            + " name outside 1 to 200 or value outside 1 to 8,000 code points; record.type_invalid (400) for a key that"
            + " is not one of the system's record types; request.invalid (400) without a version, a name, a type or the"
            + " fields, or for a malformed body; nothing changes then.";

    @PatchMapping(path = SYSTEM_RECORD, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "correctRecord", summary = "Corrects one record as one of the account's systems shows it"
            + " (contract 1.40.0), on the version it names: the record as stored, as getRecord answers it, its version"
            + " raised by one. The type is a key of the system's record types." + CORRECTION_SUMMARY + RECORD_SUMMARY
            + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<RecordView> correctRecord(
            @Parameter(description = "The system's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("systemId") String systemId,
            @Parameter(description = "The record's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("recordId") String recordId,
            @RequestBody RecordCorrectionBody body) {
        UUID system = parse(systemId).orElseThrow(OntologyController::notFound);
        UUID record = parse(recordId).orElseThrow(OntologyController::notFound);
        if (body == null) {
            throw invalid();
        }
        return found(records.correct(system, record, body.correction()).map(RecordView::of));
    }

    @PatchMapping(path = CAMPAIGN_RECORD, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "correctCampaignRecord", summary = "Corrects one record as one of the account's campaigns"
            + " shows it (contract 1.40.0, a search's record result), on the version it names: the record as stored, as"
            + " getCampaignRecord answers it, its version raised by one. The type is a key of the campaign's system's"
            + " record types." + CORRECTION_SUMMARY + " request.not_found (404) for a campaign that is not the"
            + " account's, and for a record that getCampaignRecord does not answer; none is told apart;"
            + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<RecordView> correctCampaignRecord(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(description = "The record's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("recordId") String recordId,
            @RequestBody RecordCorrectionBody body) {
        UUID campaign = parse(campaignId).orElseThrow(OntologyController::notFound);
        UUID record = parse(recordId).orElseThrow(OntologyController::notFound);
        if (body == null) {
            throw invalid();
        }
        return found(records.correctInCampaign(campaign, record, body.correction()).map(RecordView::of));
    }

    private static final String DELETION_SUMMARY = " The record stays deleted: there is no undo. From then on it leaves"
            + " the type's list, the counts, the record pages, corrections and searches at once, and leaves answers once"
            + " its index point is removed by a job; an answer already stored keeps it as it was cited. A reprocessed"
            + " version that reads it again deletes it too when accepted. No version is sent.";

    @DeleteMapping(path = SYSTEM_RECORD)
    @Operation(operationId = "deleteRecord", summary = "Deletes one record as one of the account's systems shows it"
            + " (contract 1.41.0; 204, no body)." + DELETION_SUMMARY + RECORD_SUMMARY + " nothing changes then;"
            + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "204", description = "The record was deleted"))
    ResponseEntity<Void> deleteRecord(
            @Parameter(description = "The system's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("systemId") String systemId,
            @Parameter(description = "The record's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("recordId") String recordId) {
        UUID system = parse(systemId).orElseThrow(OntologyController::notFound);
        UUID record = parse(recordId).orElseThrow(OntologyController::notFound);
        if (!records.delete(system, record)) {
            throw notFound();
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @DeleteMapping(path = CAMPAIGN_RECORD)
    @Operation(operationId = "deleteCampaignRecord", summary = "Deletes one record as one of the account's campaigns"
            + " shows it (contract 1.41.0, a search's record result; 204, no body)." + DELETION_SUMMARY
            + " request.not_found (404) for a campaign that is not the account's, and for a record that"
            + " getCampaignRecord does not answer; none is told apart, and nothing changes then;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "204", description = "The record was deleted"))
    ResponseEntity<Void> deleteCampaignRecord(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("campaignId") String campaignId,
            @Parameter(description = "The record's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("recordId") String recordId) {
        UUID campaign = parse(campaignId).orElseThrow(OntologyController::notFound);
        UUID record = parse(recordId).orElseThrow(OntologyController::notFound);
        if (!records.deleteInCampaign(campaign, record)) {
            throw notFound();
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping(path = SYSTEM_RECORD_PAGE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getRecordSourcePage", summary = "One page of the version a record was read from, as one"
            + " of the account's systems shows the record (contract 1.35.0): the same shape as MaterialPageView, for the"
            + " source view of a record's reference; any page from 1 to the version's page total." + RECORD_SUMMARY
            + " also for a page outside 1 to the page total or a page with no view (unless it is unreadable);"
            + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "200", description = "The page",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(ref = "#/components/schemas/MaterialPageView"))))
    ResponseEntity<RecordSourcePageView> recordPage(
            @Parameter(description = "The system's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("systemId") String systemId,
            @Parameter(description = "The record's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("recordId") String recordId,
            @Parameter(description = "The page's number, from 1", schema = @Schema(type = "integer", format = "int32",
                    minimum = "1")) @PathVariable("page") String page) {
        RecordSources.Page found = parse(systemId).flatMap(system -> parse(recordId).flatMap(record -> number(page)
                        .flatMap(wanted -> records.page(system, record, wanted))))
                .orElseThrow(OntologyController::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(RecordSourcePageView.of(found));
    }

    // No `produces`: a refusal is problem JSON, which an image's Accept would otherwise turn into a 406.
    @GetMapping(path = SYSTEM_RECORD_PICTURE)
    @Operation(operationId = "getRecordSourcePagePicture", summary = "The picture of one page of the version a record"
            + " was read from, as one of the account's systems shows the record (contract 1.35.0), as WebP, streamed."
            + RECORD_SUMMARY + " also for a page outside 1 to the page total or a page with no picture;"
            + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "200", description = "The page's picture",
                    content = @Content(mediaType = WEBP, schema = @Schema(type = "string", format = "binary"))))
    void recordPicture(
            @Parameter(description = "The system's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("systemId") String systemId,
            @Parameter(description = "The record's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("recordId") String recordId,
            @Parameter(description = "The page's number, from 1", schema = @Schema(type = "integer", format = "int32",
                    minimum = "1")) @PathVariable("page") String page,
            @Parameter(hidden = true) HttpServletResponse response) throws IOException {
        RecordSources.Picture picture = parse(systemId).flatMap(system -> parse(recordId).flatMap(record ->
                        number(page).flatMap(wanted -> records.picture(system, record, wanted))))
                .orElseThrow(OntologyController::notFound);
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(WEBP);
        response.setContentLengthLong(picture.size());
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        response.setHeader("X-Content-Type-Options", "nosniff");
        try (OutputStream out = response.getOutputStream()) {
            picture.writeTo(out);
        }
    }

    /** A page's number from 1; a malformed one is no different from a page that is not one. */
    private static Optional<Integer> number(String text) {
        return text != null && NUMBER.matcher(text).matches() ? Optional.of(Integer.parseInt(text)) : Optional.empty();
    }

    /** A malformed id is no different from an unknown one. */
    static Optional<UUID> parse(String id) {
        return id != null && UUID_TEXT.matcher(id).matches() ? Optional.of(UUID.fromString(id)) : Optional.empty();
    }

    /** A page's size: the default without one, else 1 to 50, or {@code request.invalid}. */
    static int limit(String limit) {
        if (limit == null) {
            return Ontology.DEFAULT_LIMIT;
        }
        if (!LIMIT.matcher(limit).matches() || Integer.parseInt(limit) > Ontology.MAX_LIMIT) {
            throw invalid();
        }
        return Integer.parseInt(limit);
    }

    /** The position a page starts at: 0 without one, else 0 to {@value Ontology#MAX_OFFSET}, or {@code request.invalid}. */
    static int offset(String offset) {
        if (offset == null) {
            return 0;
        }
        if (!OFFSET_TEXT.matcher(offset).matches() || Integer.parseInt(offset) > Ontology.MAX_OFFSET) {
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

    static <T> ResponseEntity<T> found(Optional<T> body) {
        return body.map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
                .orElseThrow(OntologyController::notFound);
    }
}
