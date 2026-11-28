package io.github.gmshield.library.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.gmshield.library.service.Rolls;
import io.github.gmshield.shared.service.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stored rolls of a Shield (Story 6.4 part A, contract 1.59.0, {@link Rolls}): a roll the browser made, kept once by
 * its key (201 when stored, 200 with the stored roll for a key the owner already holds in that Shield,
 * {@code request.roll_key_taken} (409) for a key held in another of the owner's Shields), the Shield's rolls newest first
 * a page at a time, and their clearing (204). A roll the browser could not have made (its formula, results, label or
 * time) is {@code request.roll_invalid} (400); a malformed body (not an object, a key missing or not a UUID), limit or
 * cursor is {@code request.invalid} (400). Another owner's campaign or Shield, an unknown or malformed id, a Shield of
 * another campaign, a deleting Shield and a campaign being deleted are the same {@code request.not_found} (404). Nothing
 * is stored or deleted on a refusal. Every answer is {@code no-store}.
 */
@RestController
@Tag(name = "library")
class RollController {

    static final String ROLLS = "/api/v1/campaigns/{id}/shields/{shieldId}/rolls";
    private static final String SESSION = "X-Session-Token";
    private static final String SESSION_SUMMARY = " session.required (401) without a session.";
    private static final String NOT_FOUND = " request.not_found (404) for a campaign or a Shield that is not the"
            + " account's (unknown, another account's, malformed, a Shield of another campaign, a deleting Shield or one"
            + " of a campaign being deleted); none is told apart;";
    private static final String ROLL_EXAMPLE = "0190a0b0-0000-7000-8000-000000000040";
    private static final String KEY_EXAMPLE = "3b241101-e2bb-4255-8caf-4136c566a962";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Rolls rolls;

    RollController(Rolls rolls) {
        this.rolls = rolls;
    }

    @Schema(name = "NewRoll", requiredProperties = {"key", "formula", "results", "rolledAt"},
            description = "A roll the browser made (contract 1.59.0)")
    record NewRoll(
            @Schema(description = "The key the browser made for the roll (crypto.randomUUID()); a roll sent again with"
                    + " the same key is found, not stored twice", format = "uuid", example = KEY_EXAMPLE) String key,
            @Schema(description = "The formula, under the frontend's dice grammar (src/dice.ts): dice groups [N]dS with"
                    + " S in 4, 6, 8, 10, 12, 20, 100 and modifiers 0 to 1000 joined by + or -, at most 100 dice",
                    example = "2d6+3") String formula,
            @ArraySchema(schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100"),
                    arraySchema = @Schema(description = "Each die's result, in the formula's order: one per die, each"
                            + " from 1 to its die's sides", example = "[4, 5]")) List<Integer> results,
            @Schema(description = "An optional label: at most 80 code points after trimming; absent, null or blank for"
                    + " none", types = {"string", "null"}, maxLength = 80, example = "Iniciativa") String label,
            @Schema(description = "When the browser made the roll: from 1970-01-01T00:00:00Z to at most 5 minutes ahead"
                    + " of the server's clock",
                    format = "date-time", example = "2026-11-27T12:00:00Z") String rolledAt) {

        @Override
        public String toString() {
            return "NewRoll[]";
        }
    }

    @Schema(name = "Roll", requiredProperties = {"id", "key", "formula", "results", "total", "label", "rolledAt"},
            description = "A stored roll (contract 1.59.0)")
    record RollView(
            @Schema(description = "The roll's id", format = "uuid", example = ROLL_EXAMPLE) String id,
            @Schema(description = "The key the browser made for it", format = "uuid", example = KEY_EXAMPLE) String key,
            @Schema(description = "The normalised formula (no white space, the count written)", example = "2d6+3")
            String formula,
            @ArraySchema(schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100"),
                    arraySchema = @Schema(description = "Each die's result, in the formula's order", example = "[4, 5]"))
            List<Integer> results,
            @Schema(description = "The total: the results and the modifiers, each with its sign", format = "int64",
                    example = "12") long total,
            @Schema(description = "The label, trimmed; null for none", types = {"string", "null"},
                    example = "Iniciativa") String label,
            @Schema(description = "When the browser made the roll", format = "date-time",
                    example = "2026-11-27T12:00:00Z") String rolledAt) {

        static RollView of(Rolls.Roll roll) {
            return new RollView(roll.id().toString(), roll.key().toString(), roll.formula(), roll.results(),
                    roll.total(), roll.label(), roll.rolledAt().toString());
        }

        @Override
        public String toString() {
            return "RollView[id=" + id + "]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "RollPage", requiredProperties = {"items", "total"},
            description = "A page of a Shield's stored rolls, newest first (contract 1.59.0)")
    record RollPageView(
            @ArraySchema(schema = @Schema(implementation = RollView.class)) List<RollView> items,
            @Schema(description = "The cursor of the next page; absent on the last", format = "uuid",
                    example = ROLL_EXAMPLE) String nextCursor,
            @Schema(description = "How many rolls the Shield holds, whatever the page", format = "int64",
                    minimum = "0", example = "3") long total) {
    }

    @PostMapping(path = ROLLS, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "keepRoll", summary = "Keeps a roll the browser made in one Shield of one of the account's"
            + " campaigns (contract 1.59.0): 201 with the stored roll (the formula normalised, the total computed), or"
            + " 200 with the roll already stored under that key in this Shield, creating nothing. The backend never"
            + " rolls: it stores the roll only when its formula reads under the frontend's dice grammar, its results are"
            + " its dice in order (one per die, each within its die's range), its label is at most 80 code points after"
            + " trimming and its time is from 1970-01-01T00:00:00Z to at most 5 minutes ahead; request.roll_invalid (400)"
            + " otherwise."
            + " request.roll_key_taken (409) when the key is held in another of the account's Shields; request.invalid"
            + " (400) for a malformed body (not an object, a key missing or not a UUID);" + NOT_FOUND
            + " nothing is stored then;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = NewRoll.class))),
            responses = {
                @ApiResponse(responseCode = "201", description = "The roll, stored now",
                        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = RollView.class))),
                @ApiResponse(responseCode = "200", description = "The roll already stored under this key",
                        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                schema = @Schema(implementation = RollView.class)))})
    ResponseEntity<RollView> keepRoll(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(description = "The Shield's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("shieldId") String shieldId,
            @RequestBody(required = false) String body) {
        UUID campaign = LibraryController.parse(id).orElseThrow(LibraryController::notFound);
        UUID shield = LibraryController.parse(shieldId).orElseThrow(LibraryController::notFound);
        JsonNode read = object(body);
        JsonNode keyNode = read.get("key");
        if (keyNode == null || !keyNode.isString()) {
            throw LibraryController.invalid();
        }
        UUID key = LibraryController.parse(keyNode.asString()).orElseThrow(LibraryController::invalid);
        Rolls.Kept kept = rolls.keep(campaign, shield, key, text(read, "formula"), results(read.get("results")),
                text(read, "label"), time(read.get("rolledAt"))).orElseThrow(LibraryController::notFound);
        return ResponseEntity.status(kept.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore()).body(RollView.of(kept.roll()));
    }

    @GetMapping(path = ROLLS, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listRolls", summary = "The stored rolls of one Shield of one of the account's campaigns"
            + " (contract 1.59.0), newest first (by rolledAt, then by id), a page at a time after a cursor, naming the"
            + " total. A cursor whose roll is gone (cleared) gives an empty page." + NOT_FOUND + " request.invalid"
            + " (400) for a limit outside 1 to 50 or a malformed cursor;" + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "limit", in = ParameterIn.QUERY, description = "At most this many, 1 to 50",
                        schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "50",
                                defaultValue = "20")),
                @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "The nextCursor of the previous page",
                        schema = @Schema(type = "string", format = "uuid"))})
    ResponseEntity<RollPageView> listRolls(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(description = "The Shield's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("shieldId") String shieldId,
            @Parameter(hidden = true) @RequestParam(value = "limit", required = false) String limit,
            @Parameter(hidden = true) @RequestParam(value = "cursor", required = false) String cursor) {
        int size = LibraryController.limit(limit);
        UUID after = LibraryController.cursor(cursor);
        UUID campaign = LibraryController.parse(id).orElseThrow(LibraryController::notFound);
        UUID shield = LibraryController.parse(shieldId).orElseThrow(LibraryController::notFound);
        Rolls.RollPage page = rolls.rolls(campaign, shield, after, size).orElseThrow(LibraryController::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new RollPageView(
                page.items().stream().map(RollView::of).toList(), LibraryController.text(page.nextCursor()),
                page.total()));
    }

    @DeleteMapping(path = ROLLS)
    @Operation(operationId = "clearRolls", summary = "Clears the stored rolls of one Shield of one of the account's"
            + " campaigns (contract 1.59.0; 204, no body, also when it held none). Cannot be undone." + NOT_FOUND
            + " nothing is deleted then;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "204", description = "The rolls are cleared"))
    ResponseEntity<Void> clearRolls(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(description = "The Shield's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("shieldId") String shieldId) {
        UUID campaign = LibraryController.parse(id).orElseThrow(LibraryController::notFound);
        UUID shield = LibraryController.parse(shieldId).orElseThrow(LibraryController::notFound);
        if (!rolls.clear(campaign, shield)) {
            throw LibraryController.notFound();
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** The body as a JSON object, else {@code request.invalid}. */
    private static JsonNode object(String body) {
        JsonNode read;
        try {
            read = body == null || body.isBlank() ? null : JSON.readTree(body);
        } catch (JacksonException malformed) {
            throw LibraryController.invalid();
        }
        if (read == null || !read.isObject()) {
            throw LibraryController.invalid();
        }
        return read;
    }

    /** A text field: null when absent or null; {@code request.roll_invalid} for any other kind of value. */
    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw rollInvalid();
        }
        return value.asString();
    }

    /** The results: an array of whole numbers; null when absent, {@code request.roll_invalid} for anything else. */
    private static List<Integer> results(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isArray()) {
            throw rollInvalid();
        }
        List<Integer> results = new ArrayList<>(value.size());
        for (JsonNode result : value) {
            if (!result.isIntegralNumber() || !result.canConvertToInt()) {
                throw rollInvalid();
            }
            results.add(result.intValue());
        }
        return results;
    }

    /** The roll's time: an ISO-8601 instant; null when absent, {@code request.roll_invalid} for anything else. */
    private static Instant time(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw rollInvalid();
        }
        try {
            return Instant.parse(value.asString());
        } catch (DateTimeParseException unreadable) {
            throw rollInvalid();
        }
    }

    private static ApiException rollInvalid() {
        return Rolls.invalid();
    }
}
