package io.github.gmshield.knowledge.web;

import io.github.gmshield.knowledge.service.Ontology;
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
 */
@RestController
@Tag(name = "ontology")
class OntologyController {

    static final String SYSTEM_RECORD_TYPES = "/api/v1/systems/{id}/record-types";
    static final String RECORD_TYPE = "/api/v1/record-types/{id}";
    /** A UUID in its canonical text form; anything else is an id that exists nowhere. */
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Pattern LIMIT = Pattern.compile("[1-9][0-9]?");
    /** A page's position: 0, or up to six digits with no leading zero. */
    private static final Pattern OFFSET_TEXT = Pattern.compile("0|[1-9][0-9]{0,5}");
    private static final String SESSION = "X-Session-Token";
    private static final String SESSION_SUMMARY = " session.required (401) without a session.";

    private final Ontology ontology;

    OntologyController(Ontology ontology) {
        this.ontology = ontology;
    }

    @Schema(name = "RecordType", requiredProperties = {"id", "name", "core", "records", "version"})
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
            @Schema(description = "How many records of the system have this type", format = "int64", minimum = "0",
                    example = "0") long records,
            @Schema(description = "The record type's version: a rename names it and raises it by one", format = "int64",
                    example = "0") long version) {

        static RecordTypeView of(Ontology.RecordType type) {
            return new RecordTypeView(type.id().toString(), type.name(), type.core(), type.records(), type.version());
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
            + " removed; request.not_found (404) for any other id; nothing changes then;" + SESSION_SUMMARY,
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

    /** A malformed id is no different from an unknown one. */
    private static Optional<UUID> parse(String id) {
        return id != null && UUID_TEXT.matcher(id).matches() ? Optional.of(UUID.fromString(id)) : Optional.empty();
    }

    /** A page's size: the default without one, else 1 to 50, or {@code request.invalid}. */
    private static int limit(String limit) {
        if (limit == null) {
            return Ontology.DEFAULT_LIMIT;
        }
        if (!LIMIT.matcher(limit).matches() || Integer.parseInt(limit) > Ontology.MAX_LIMIT) {
            throw invalid();
        }
        return Integer.parseInt(limit);
    }

    /** The position a page starts at: 0 without one, else 0 to {@value Ontology#MAX_OFFSET}, or {@code request.invalid}. */
    private static int offset(String offset) {
        if (offset == null) {
            return 0;
        }
        if (!OFFSET_TEXT.matcher(offset).matches() || Integer.parseInt(offset) > Ontology.MAX_OFFSET) {
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

    private static <T> ResponseEntity<T> found(Optional<T> body) {
        return body.map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
                .orElseThrow(OntologyController::notFound);
    }
}
