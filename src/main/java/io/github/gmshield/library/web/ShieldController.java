package io.github.gmshield.library.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.gmshield.library.service.Shields;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
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
 * The Shields of the owner's campaigns (Story 6.1, {@link Shields}): the list (oldest first, by cursor or offset), a new
 * Shield (201), one Shield, and its rename by its version ({@code request.stale_version}, 409, when it is stale). The
 * first Shield of a campaign has a null name until the GM renames it. A name outside its limits is
 * {@code request.shield_name_invalid} (400), a name another Shield of the campaign has is
 * {@code request.shield_name_taken} (409); a malformed body, limit, cursor or offset is {@code request.invalid} (400).
 * Another owner's campaign or Shield, an unknown or malformed id, a Shield of another campaign and a campaign being
 * deleted are the same {@code request.not_found} (404). Every answer is {@code no-store}.
 *
 * <p>Part B: what goes with a Shield if it is deleted (its notes and rolls, none yet, and whether it is the campaign's
 * last), and its deletion (204), which refuses the campaign's only Shield with {@code request.last_shield} (409).
 */
@RestController
@Tag(name = "library")
class ShieldController {

    static final String SHIELDS = "/api/v1/campaigns/{id}/shields";
    static final String SHIELD = "/api/v1/campaigns/{id}/shields/{shieldId}";
    static final String SHIELD_DELETION = "/api/v1/campaigns/{id}/shields/{shieldId}/deletion";
    private static final String SESSION = "X-Session-Token";
    private static final String SESSION_SUMMARY = " session.required (401) without a session.";
    private static final String SHIELD_EXAMPLE = "0190a0b0-0000-7000-8000-000000000003";
    private static final String NAME_RULE = "1 to 120 code points after trimming; unique within the campaign, whatever the"
            + " case";

    private final Shields shields;

    ShieldController(Shields shields) {
        this.shields = shields;
    }

    @Schema(name = "Shield", requiredProperties = {"id", "name", "version"})
    record ShieldView(
            @Schema(description = "The Shield's id", format = "uuid", example = SHIELD_EXAMPLE) String id,
            @Schema(description = "The Shield's name; null for the campaign's first Shield until the GM renames it (the"
                    + " frontend shows it as \"Escudo do Mestre\" or \"GM's Shield\")", types = {"string", "null"},
                    example = "Combate") String name,
            @Schema(description = "The Shield's version: a rename names it and raises it by one", format = "int64",
                    example = "0") long version) {

        static ShieldView of(Shields.Shield shield) {
            return new ShieldView(shield.id().toString(), shield.name(), shield.version());
        }

        @Override
        public String toString() {
            return "ShieldView[id=" + id + ", version=" + version + "]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "ShieldPage", requiredProperties = {"items", "total"})
    record ShieldPageView(
            @ArraySchema(schema = @Schema(implementation = ShieldView.class)) List<ShieldView> items,
            @Schema(description = "The cursor of the next page; absent on the last", format = "uuid",
                    example = SHIELD_EXAMPLE) String nextCursor,
            @Schema(description = "How many Shields the campaign has in all, whatever the page", format = "int64",
                    example = "2") long total) {
    }

    @Schema(name = "NewShield", requiredProperties = "name")
    record NewShield(
            @Schema(description = NAME_RULE, minLength = 1, maxLength = 120, example = "Combate") String name) {

        @Override
        public String toString() {
            return "NewShield[]";
        }
    }

    @Schema(name = "ShieldRename", requiredProperties = {"version", "name"})
    record ShieldRenameBody(
            @Schema(description = "The version the rename was made on, as the Shield was read", format = "int64",
                    minimum = "0", example = "0") Long version,
            @Schema(description = "The new name, " + NAME_RULE, minLength = 1, maxLength = 120, example = "Exploração")
            String name) {

        @Override
        public String toString() {
            return "ShieldRenameBody[version=" + version + "]";
        }
    }

    @Schema(name = "ShieldDeletion", requiredProperties = {"notes", "rolls", "last"})
    record ShieldDeletionView(
            @Schema(description = "How many notes of the Shield go with it (0 until notes exist)", format = "int64",
                    minimum = "0", example = "0") long notes,
            @Schema(description = "How many stored rolls of the Shield go with it (0 until rolls are stored)",
                    format = "int64", minimum = "0", example = "0") long rolls,
            @Schema(description = "Whether it is the campaign's only Shield, which cannot be deleted", example = "false")
            boolean last) {

        static ShieldDeletionView of(Shields.ShieldDeletion deletion) {
            return new ShieldDeletionView(deletion.notes(), deletion.rolls(), deletion.last());
        }
    }

    @GetMapping(path = SHIELDS, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listShields", summary = "The Shields of one of the account's campaigns, oldest first; the"
            + " first one has a null name until it is renamed. A page starts after a cursor or at an offset (a numbered"
            + " page), and names the total of the list. request.not_found (404) for a campaign that is not the"
            + " account's (unknown, another account's, malformed or being deleted); request.invalid (400) for a limit"
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
                @Parameter(name = "offset", in = ParameterIn.QUERY, description = "How many of the oldest to skip, 0 to"
                        + " 100000, for a numbered page; not together with cursor",
                        schema = @Schema(type = "integer", format = "int32", minimum = "0", maximum = "100000",
                                defaultValue = "0"))})
    ResponseEntity<ShieldPageView> shields(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(hidden = true) @RequestParam(value = "limit", required = false) String limit,
            @Parameter(hidden = true) @RequestParam(value = "cursor", required = false) String cursor,
            @Parameter(hidden = true) @RequestParam(value = "offset", required = false) String offset) {
        int size = LibraryController.limit(limit);
        UUID after = LibraryController.cursor(cursor);
        int from = LibraryController.offset(offset, after);
        UUID campaignId = LibraryController.parse(id).orElseThrow(LibraryController::notFound);
        Shields.ShieldPage page = shields.shields(campaignId, after, from, size)
                .orElseThrow(LibraryController::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ShieldPageView(
                page.items().stream().map(ShieldView::of).toList(), LibraryController.text(page.nextCursor()),
                page.total()));
    }

    @PostMapping(path = SHIELDS, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createShield", summary = "A new Shield of one of the account's campaigns (201)."
            + " request.shield_name_invalid (400) for a name outside its limits (params min and max),"
            + " request.shield_name_taken (409) when the campaign has a Shield with the same name (trimmed, whatever the"
            + " case), request.not_found (404) for a campaign that is not the account's (unknown, another account's,"
            + " malformed or being deleted), request.invalid (400) for a malformed body; nothing is stored then;"
            + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<ShieldView> createShield(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @RequestBody NewShield body) {
        UUID campaignId = LibraryController.parse(id).orElseThrow(LibraryController::notFound);
        if (body == null) {
            throw LibraryController.invalid();
        }
        Shields.Shield created = shields.createShield(campaignId, body.name()).orElseThrow(LibraryController::notFound);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(ShieldView.of(created));
    }

    @GetMapping(path = SHIELD, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getShield", summary = "One Shield of one of the account's campaigns; its name is null"
            + " while it is the campaign's unnamed first Shield. request.not_found (404) for any other id (unknown,"
            + " another account's, malformed, a Shield of another campaign or of a campaign being deleted);"
            + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<ShieldView> shield(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(description = "The Shield's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("shieldId") String shieldId) {
        return LibraryController.found(LibraryController.parse(id).flatMap(campaignId -> LibraryController.parse(shieldId)
                .flatMap(shield -> shields.shield(campaignId, shield))).map(ShieldView::of));
    }

    @PatchMapping(path = SHIELD, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "renameShield", summary = "Renames one Shield of one of the account's campaigns on the"
            + " version it names: the Shield as stored, its version raised by one. request.stale_version (409) when the"
            + " Shield has left that version, request.shield_name_taken (409) when another Shield of the campaign has the"
            + " new name, request.shield_name_invalid (400) for a name outside its limits, request.invalid (400) without"
            + " a version or for a malformed body, request.not_found (404) for any other id (unknown, another account's,"
            + " malformed, a Shield of another campaign or of a campaign being deleted); nothing changes then;"
            + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<ShieldView> renameShield(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(description = "The Shield's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("shieldId") String shieldId,
            @RequestBody ShieldRenameBody body) {
        UUID campaignId = LibraryController.parse(id).orElseThrow(LibraryController::notFound);
        UUID shield = LibraryController.parse(shieldId).orElseThrow(LibraryController::notFound);
        if (body == null || body.version() == null || body.version() < 0) {
            throw LibraryController.invalid();
        }
        return LibraryController.found(shields.renameShield(campaignId, shield, body.version(), body.name())
                .map(ShieldView::of));
    }

    @GetMapping(path = SHIELD_DELETION, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getShieldDeletion", summary = "What goes with one Shield of one of the account's campaigns"
            + " if it is deleted: how many notes and stored rolls (0 until they exist), and whether it is the campaign's"
            + " only Shield (last), which cannot be deleted. request.not_found (404) for any other id (unknown, another"
            + " account's, malformed, already deleted, a Shield of another campaign or of a campaign being deleted);"
            + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<ShieldDeletionView> shieldDeletion(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(description = "The Shield's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("shieldId") String shieldId) {
        return LibraryController.found(LibraryController.parse(id).flatMap(campaignId -> LibraryController.parse(shieldId)
                .flatMap(shield -> shields.shieldDeletion(campaignId, shield))).map(ShieldDeletionView::of));
    }

    @DeleteMapping(path = SHIELD)
    @Operation(operationId = "deleteShield", summary = "Deletes one Shield of one of the account's campaigns (204, no"
            + " body): from now on it is not found, and nothing else of the campaign changes. Cannot be undone."
            + " request.last_shield (409) when it is the campaign's only Shield, request.not_found (404) for any other id"
            + " (unknown, another account's, malformed, already deleted, a Shield of another campaign or of a campaign"
            + " being deleted); nothing changes then;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "204", description = "The Shield was deleted"))
    ResponseEntity<Void> deleteShield(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(description = "The Shield's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("shieldId") String shieldId) {
        UUID campaignId = LibraryController.parse(id).orElseThrow(LibraryController::notFound);
        UUID shield = LibraryController.parse(shieldId).orElseThrow(LibraryController::notFound);
        if (!shields.deleteShield(campaignId, shield)) {
            throw LibraryController.notFound();
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
