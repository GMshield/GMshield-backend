package io.github.gmshield.library.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The owner's systems and campaigns (Story 1.11). The owner is the session's account, set by the request edge
 * ({@code accounts.web}) before any of these runs; nothing here takes an owner from the request. Another owner's id,
 * an unknown id and a malformed id are the same {@code request.not_found} (404), in a path and in {@code ?system=}.
 * Every answer is {@code no-store}.
 *
 * <p>Story 3.1: the list of systems (newest first, by cursor), a new system (201), a system's edit by its version
 * ({@code request.stale_version}, 409, when it is stale), and a system's campaigns. A field outside its limits is its
 * own 400 problem ({@code request.system_name_invalid}, {@code request.system_edition_invalid},
 * {@code request.system_description_invalid}); a name and edition the owner already has is
 * {@code request.system_name_taken} (409). A malformed body, limit or cursor is {@code request.invalid} (400).
 */
@RestController
@Tag(name = "library")
class LibraryController {

    static final String CURRENT_CAMPAIGN = "/api/v1/campaigns/current";
    static final String CAMPAIGNS = "/api/v1/campaigns";
    static final String CAMPAIGN = "/api/v1/campaigns/{id}";
    static final String SYSTEMS = "/api/v1/systems";
    static final String SYSTEM = "/api/v1/systems/{id}";
    /** A UUID in its canonical text form; anything else is an id that exists nowhere. */
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Pattern LIMIT = Pattern.compile("[1-9][0-9]?");
    private static final String SESSION = "X-Session-Token";
    private static final String SESSION_SUMMARY = " session.required (401) without a session.";
    private static final String CURSOR_EXAMPLE = "0190a0b0-0000-7000-8000-000000000001";

    private final Library library;

    LibraryController(Library library) {
        this.library = library;
    }

    @Schema(name = "System", requiredProperties = {"id", "name", "edition", "description", "version"})
    record SystemView(
            @Schema(description = "The system's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000001")
            String id,
            @Schema(description = "The system's name", example = "Sistema sem nome") String name,
            @Schema(description = "The system's edition, null when it has none", types = {"string", "null"},
                    example = "5.2.1") String edition,
            @Schema(description = "The system's description, null when it has none", types = {"string", "null"},
                    example = "As regras básicas, sem suplementos.") String description,
            @Schema(description = "The system's version: an edit names it, and each edit raises it by one",
                    format = "int64", example = "0") long version) {

        static SystemView of(Library.GameSystem system) {
            return new SystemView(system.id().toString(), system.name(), system.edition(), system.description(),
                    system.version());
        }

        @Override
        public String toString() {
            return "SystemView[id=" + id + ", version=" + version + "]";
        }
    }

    @Schema(name = "Campaign", requiredProperties = {"id", "name", "system"})
    record CampaignView(
            @Schema(description = "The campaign's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000002")
            String id,
            @Schema(description = "The campaign's name", example = "Primeira campanha") String name,
            @Schema(description = "The campaign's system") SystemView system) {

        static CampaignView of(Library.Campaign campaign) {
            return new CampaignView(campaign.id().toString(), campaign.name(), SystemView.of(campaign.system()));
        }

        @Override
        public String toString() {
            return "CampaignView[id=" + id + "]";
        }
    }

    @Schema(name = "CampaignSummary", requiredProperties = {"id", "name"})
    record CampaignSummaryView(
            @Schema(description = "The campaign's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000002")
            String id,
            @Schema(description = "The campaign's name", example = "Primeira campanha") String name) {

        static CampaignSummaryView of(Library.Named campaign) {
            return new CampaignSummaryView(campaign.id().toString(), campaign.name());
        }

        @Override
        public String toString() {
            return "CampaignSummaryView[id=" + id + "]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "SystemPage", requiredProperties = "items")
    record SystemPageView(
            @ArraySchema(schema = @Schema(implementation = SystemView.class)) List<SystemView> items,
            @Schema(description = "The cursor of the next page; absent on the last", format = "uuid",
                    example = CURSOR_EXAMPLE) String nextCursor) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "CampaignPage", requiredProperties = "items")
    record CampaignPageView(
            @ArraySchema(schema = @Schema(implementation = CampaignSummaryView.class)) List<CampaignSummaryView> items,
            @Schema(description = "The cursor of the next page; absent on the last", format = "uuid",
                    example = "0190a0b0-0000-7000-8000-000000000002") String nextCursor) {
    }

    @Schema(name = "NewSystem", requiredProperties = "name")
    record NewSystem(
            @Schema(description = "1 to 120 code points after trimming", minLength = 1, maxLength = 120,
                    example = "D&D") String name,
            @Schema(description = "Optional, at most 60 code points after trimming; blank or absent is none",
                    maxLength = 60, example = "5.2.1") String edition,
            @Schema(description = "Optional, at most 2,000 code points after trimming; blank or absent is none",
                    maxLength = 2000, example = "As regras básicas, sem suplementos.") String description) {

        @Override
        public String toString() {
            return "NewSystem[]";
        }
    }

    @Schema(name = "SystemChange", requiredProperties = "version")
    record SystemChangeBody(
            @Schema(description = "The version the edit was made on, as the system was read", format = "int64",
                    minimum = "0", example = "0") Long version,
            @Schema(description = "The new name, 1 to 120 code points after trimming; absent or null: unchanged",
                    minLength = 1, maxLength = 120, example = "D&D") String name,
            @Schema(description = "The new edition, at most 60 code points after trimming; blank removes it; absent or"
                    + " null: unchanged", maxLength = 60, example = "2024") String edition,
            @Schema(description = "The new description, at most 2,000 code points after trimming; blank removes it;"
                    + " absent or null: unchanged", maxLength = 2000, example = "") String description) {

        @Override
        public String toString() {
            return "SystemChangeBody[version=" + version + "]";
        }
    }

    @GetMapping(path = CURRENT_CAMPAIGN, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getCurrentCampaign", summary = "The current campaign with its system; session.required"
            + " (401) without a session, request.not_found (404) when the account has no campaign",
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<CampaignView> currentCampaign() {
        return found(library.currentCampaign().map(CampaignView::of));
    }

    @GetMapping(path = CAMPAIGNS, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listCampaigns", summary = "The campaigns of one of the account's systems, newest first."
            + " request.not_found (404) for a system that is not the account's (unknown, another account's or"
            + " malformed); request.invalid (400) without a system, for a limit outside 1 to 50 or a malformed cursor;"
            + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "system", in = ParameterIn.QUERY, required = true, description = "The system's id",
                        schema = @Schema(type = "string", format = "uuid")),
                @Parameter(name = "limit", in = ParameterIn.QUERY, description = "At most this many, 1 to 50",
                        schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "50",
                                defaultValue = "20")),
                @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "The nextCursor of the previous page",
                        schema = @Schema(type = "string", format = "uuid"))})
    ResponseEntity<CampaignPageView> campaigns(
            @Parameter(hidden = true) @RequestParam(value = "system", required = false) String system,
            @Parameter(hidden = true) @RequestParam(value = "limit", required = false) String limit,
            @Parameter(hidden = true) @RequestParam(value = "cursor", required = false) String cursor) {
        if (system == null) {
            throw invalid();
        }
        int size = limit(limit);
        UUID after = cursor(cursor);
        Library.CampaignPage page = parse(system).flatMap(id -> library.campaignsOf(id, after, size))
                .orElseThrow(LibraryController::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CampaignPageView(
                page.items().stream().map(CampaignSummaryView::of).toList(), text(page.nextCursor())));
    }

    @GetMapping(path = CAMPAIGN, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getCampaign", summary = "One of the account's campaigns, with its system;"
            + " request.not_found (404) for any other id",
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<CampaignView> campaign(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        return found(parse(id).flatMap(library::campaign).map(CampaignView::of));
    }

    @GetMapping(path = SYSTEMS, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listSystems", summary = "The account's game systems, newest first. request.invalid (400)"
            + " for a limit outside 1 to 50 or a malformed cursor;" + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "limit", in = ParameterIn.QUERY, description = "At most this many, 1 to 50",
                        schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "50",
                                defaultValue = "20")),
                @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "The nextCursor of the previous page",
                        schema = @Schema(type = "string", format = "uuid"))})
    ResponseEntity<SystemPageView> systems(
            @Parameter(hidden = true) @RequestParam(value = "limit", required = false) String limit,
            @Parameter(hidden = true) @RequestParam(value = "cursor", required = false) String cursor) {
        int size = limit(limit);
        Library.SystemPage page = library.systems(cursor(cursor), size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SystemPageView(
                page.items().stream().map(SystemView::of).toList(), text(page.nextCursor())));
    }

    @PostMapping(path = SYSTEMS, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createSystem", summary = "A new game system of the account (201). request.system_name_invalid,"
            + " request.system_edition_invalid or request.system_description_invalid (400) for a field outside its"
            + " limits (params min and max, or max), request.system_name_taken (409) when the account has a system with"
            + " the same name and edition (trimmed, whatever the case), request.invalid (400) for a malformed body;"
            + " nothing is stored then;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<SystemView> createSystem(@RequestBody NewSystem body) {
        if (body == null) {
            throw invalid();
        }
        Library.GameSystem created = library.createSystem(
                new Library.SystemFields(body.name(), body.edition(), body.description()));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(SystemView.of(created));
    }

    @GetMapping(path = SYSTEM, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getSystem", summary = "One of the account's game systems; request.not_found (404)"
            + " for any other id",
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<SystemView> system(
            @Parameter(description = "The system's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        return found(parse(id).flatMap(library::system).map(SystemView::of));
    }

    @PatchMapping(path = SYSTEM, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "changeSystem", summary = "Changes one of the account's game systems on the version it"
            + " names: the system as stored, its version raised by one. request.stale_version (409) when the system"
            + " has left that version, request.system_name_taken (409) when the account has another system with the"
            + " new name and edition, the field problems (400) of a creation, request.invalid (400) without a version"
            + " or for a malformed body, request.not_found (404) for any other id; nothing changes then;"
            + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<SystemView> changeSystem(
            @Parameter(description = "The system's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @RequestBody SystemChangeBody body) {
        if (body == null || body.version() == null || body.version() < 0) {
            throw invalid();
        }
        UUID systemId = parse(id).orElseThrow(LibraryController::notFound);
        return found(library.changeSystem(systemId, body.version(),
                new Library.SystemFields(body.name(), body.edition(), body.description())).map(SystemView::of));
    }

    /** A malformed id is no different from an unknown one. */
    private static Optional<UUID> parse(String id) {
        return id != null && UUID_TEXT.matcher(id).matches() ? Optional.of(UUID.fromString(id)) : Optional.empty();
    }

    /** A page's size: the default without one, else 1 to 50, or {@code request.invalid}. */
    private static int limit(String limit) {
        if (limit == null) {
            return Library.DEFAULT_LIMIT;
        }
        if (!LIMIT.matcher(limit).matches() || Integer.parseInt(limit) > Library.MAX_LIMIT) {
            throw invalid();
        }
        return Integer.parseInt(limit);
    }

    /** The cursor of a page: none, or an id, else {@code request.invalid}. */
    private static UUID cursor(String cursor) {
        return cursor == null ? null : parse(cursor).orElseThrow(LibraryController::invalid);
    }

    private static String text(UUID id) {
        return id == null ? null : id.toString();
    }

    private static ApiException invalid() {
        return new ApiException(ApiProblem.forStatus(400));
    }

    private static ApiException notFound() {
        return new ApiException(ApiProblem.forStatus(404));
    }

    private static <T> ResponseEntity<T> found(Optional<T> body) {
        return body.map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
                .orElseThrow(LibraryController::notFound);
    }
}
