package io.github.gmshield.artifacts.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.gmshield.artifacts.service.Artifacts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The notes of a Shield (Story 6.2 part A, contract 1.57.0): a new note written in the Shield (201, the artifact as
 * {@code getArtifact} answers it: record type {@code note}, no fields, version 1, {@code shieldId} set) and the Shield's
 * notes a page at a time, newest first. A note is an artifact of the Shield's campaign: it opens, changes, lists,
 * shares, relates and exports through {@link ArtifactController}, and answers cite it under the campaign tier. The
 * owner is the session's account, set by the request edge before any of these runs. Another owner's campaign or
 * Shield, an unknown or malformed id, a Shield of another campaign and a campaign being deleted are the same
 * {@code request.not_found} (404). A name or content outside its limits is {@code request.invalid} (400) naming its
 * field; a malformed body, limit, cursor or offset is {@code request.invalid} (400) without one. Nothing is stored on a
 * refusal. Every answer is {@code no-store}.
 */
@RestController
@Tag(name = "artifacts")
class ShieldNoteController {

    static final String NOTES = "/api/v1/campaigns/{id}/shields/{shieldId}/notes";
    private static final String SESSION = "X-Session-Token";
    private static final String SESSION_SUMMARY = " session.required (401) without a session.";
    private static final String NOT_FOUND = " request.not_found (404) for a campaign or a Shield that is not the"
            + " account's (unknown, another account's, malformed, a Shield of another campaign or of a campaign being"
            + " deleted); none is told apart;";

    private final Artifacts artifacts;

    ShieldNoteController(Artifacts artifacts) {
        this.artifacts = artifacts;
    }

    @Schema(name = "NewNote", requiredProperties = "name", description = "A note written in a Shield (contract 1.57.0)")
    record NewNote(
            @Schema(description = "The note's title: 1 to 200 code points after trimming", minLength = 1,
                    maxLength = 200, example = "Pistas da cripta") String name,
            @Schema(description = "The note's text: at most 20,000 code points after trimming; absent, null or blank"
                    + " for none", types = {"string", "null"}, maxLength = 20000,
                    example = "O sacerdote mente sobre a chave.") String content) {

        @Override
        public String toString() {
            return "NewNote[]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "NotePage", requiredProperties = {"items", "total"},
            description = "A page of a Shield's notes, newest first (contract 1.57.0)")
    record NotePageView(
            @ArraySchema(schema = @Schema(implementation = ArtifactController.SummaryView.class))
            List<ArtifactController.SummaryView> items,
            @Schema(description = "The cursor of the next page; absent on the last", format = "uuid",
                    example = "0190a0b0-0000-7000-8000-000000000030") String nextCursor,
            @Schema(description = "How many live notes the Shield holds, whatever the page", format = "int64",
                    minimum = "0", example = "3") long total) {
    }

    @PostMapping(path = NOTES, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createShieldNote", summary = "A new note written in one Shield of one of the account's"
            + " campaigns (contract 1.57.0; 201): an artifact of the campaign of record type note, with no fields, its"
            + " name and optional content as its version 1, held by the Shield (shieldId), as getArtifact answers it."
            + " It is indexed like any artifact, so answers in the campaign can cite it under the campaign tier."
            + " request.invalid (400) with params.field naming the value outside its limits (and its min and max): name"
            + " (1 to 200 code points after trimming) or content (at most 20,000 after trimming); request.invalid (400)"
            + " without a field for a malformed body;" + NOT_FOUND + " nothing is stored then;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<ArtifactController.ArtifactView> createShieldNote(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(description = "The Shield's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("shieldId") String shieldId,
            @RequestBody NewNote body) {
        UUID campaign = ArtifactController.parse(id).orElseThrow(ArtifactController::notFound);
        UUID shield = ArtifactController.parse(shieldId).orElseThrow(ArtifactController::notFound);
        if (body == null) {
            throw ArtifactController.invalid();
        }
        Artifacts.Artifact created = artifacts.createNote(campaign, shield, body.name(), body.content())
                .orElseThrow(ArtifactController::notFound);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ArtifactController.ArtifactView.of(created));
    }

    @GetMapping(path = NOTES, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listShieldNotes", summary = "The notes one Shield of one of the account's campaigns holds"
            + " (contract 1.57.0), newest first, each as the campaign's artifact list shows it (shieldId set). A page"
            + " starts after a cursor or at an offset (a numbered page), and names the total of the list. A note the GM"
            + " deleted is left out from the moment of the delete." + NOT_FOUND + " request.invalid (400) for a limit"
            + " outside 1 to 50, a malformed cursor, an offset outside 0 to 100000 or a cursor together with an offset"
            + " above 0;" + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "limit", in = ParameterIn.QUERY, description = "At most this many, 1 to 50",
                        schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "50",
                                defaultValue = "20")),
                @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "The nextCursor of the previous page",
                        schema = @Schema(type = "string", format = "uuid")),
                @Parameter(name = "offset", in = ParameterIn.QUERY, description = "How many of the newest to skip, 0 to"
                        + " 100000, for a numbered page; not together with cursor",
                        schema = @Schema(type = "integer", format = "int32", minimum = "0", maximum = "100000",
                                defaultValue = "0"))})
    ResponseEntity<NotePageView> listShieldNotes(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(description = "The Shield's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("shieldId") String shieldId,
            @Parameter(hidden = true) @RequestParam(value = "limit", required = false) String limit,
            @Parameter(hidden = true) @RequestParam(value = "cursor", required = false) String cursor,
            @Parameter(hidden = true) @RequestParam(value = "offset", required = false) String offset) {
        int size = ArtifactController.limit(limit);
        UUID after = cursor == null ? null : ArtifactController.parse(cursor).orElseThrow(ArtifactController::invalid);
        int from = ArtifactController.offset(offset, after);
        UUID campaign = ArtifactController.parse(id).orElseThrow(ArtifactController::notFound);
        UUID shield = ArtifactController.parse(shieldId).orElseThrow(ArtifactController::notFound);
        Artifacts.NotePage page = artifacts.notes(campaign, shield, after, from, size)
                .orElseThrow(ArtifactController::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new NotePageView(
                page.items().stream().map(ArtifactController.SummaryView::of).toList(),
                page.nextCursor() == null ? null : page.nextCursor().toString(), page.total()));
    }
}
