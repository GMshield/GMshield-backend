package io.github.gmshield.library.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.library.service.SystemDeletion;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

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
 *
 * <p>Story 3.2: the list of the account's campaigns ({@code ?system=} now optional, {@code ?archived=} false by default),
 * a new campaign (201; it becomes the current one), a campaign's rename, archive or restore by its version, and the
 * choice of the current campaign ({@code PUT .../current}). A campaign's name outside its limits is
 * {@code request.campaign_name_invalid} (400), a name another campaign of the system has is
 * {@code request.campaign_name_taken} (409); an archived campaign cannot become the current one
 * ({@code request.not_found}).
 *
 * <p>Story 3.1 part B: what goes with a system ({@code GET .../deletion}), and its deletion ({@code DELETE}, 202 with
 * no body; {@link SystemDeletion}). A system being deleted is {@code request.not_found} on every route, as another
 * owner's is.
 *
 * <p>Story 3.2 part B: what goes with a campaign ({@code GET .../deletion}), and its deletion ({@code DELETE}, 202 with
 * no body; {@link SystemDeletion#deleteCampaign}). A campaign being deleted is {@code request.not_found} on every
 * route, as another owner's is.
 *
 * <p>Story 7.1: a campaign carries its party size and level (each null while not set), and
 * {@code PUT .../party} replaces both on the campaign's version (null clears). A value that is not a whole number in its
 * range is {@code request.party_size_invalid} or {@code request.party_level_invalid} (400, params {@code min} and
 * {@code max}).
 */
@RestController
@Tag(name = "library")
class LibraryController {

    static final String CURRENT_CAMPAIGN = "/api/v1/campaigns/current";
    static final String CAMPAIGNS = "/api/v1/campaigns";
    static final String CAMPAIGN = "/api/v1/campaigns/{id}";
    static final String SYSTEMS = "/api/v1/systems";
    static final String SYSTEM = "/api/v1/systems/{id}";
    static final String SYSTEM_DELETION = "/api/v1/systems/{id}/deletion";
    static final String CAMPAIGN_DELETION = "/api/v1/campaigns/{id}/deletion";
    static final String CAMPAIGN_PARTY = "/api/v1/campaigns/{id}/party";
    /** A UUID in its canonical text form; anything else is an id that exists nowhere. */
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Pattern LIMIT = Pattern.compile("[1-9][0-9]?");
    /** A page's position: 0, or up to six digits with no leading zero. */
    private static final Pattern OFFSET_TEXT = Pattern.compile("0|[1-9][0-9]{0,5}");
    private static final String OFFSET = "How many of the newest to skip, 0 to 100000, for a numbered page; not"
            + " together with cursor";
    private static final String TOTAL = "How many items the list has in all, whatever the page";
    private static final String SESSION = "X-Session-Token";
    private static final String SESSION_SUMMARY = " session.required (401) without a session.";
    private static final String CURSOR_EXAMPLE = "0190a0b0-0000-7000-8000-000000000001";

    private final Library library;
    private final SystemDeletion deletion;

    LibraryController(Library library, SystemDeletion deletion) {
        this.library = library;
        this.deletion = deletion;
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

    @Schema(name = "SystemSummary", requiredProperties = {"id", "name", "edition"})
    record SystemSummaryView(
            @Schema(description = "The system's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000001")
            String id,
            @Schema(description = "The system's name", example = "Sistema sem nome") String name,
            @Schema(description = "The system's edition, null when it has none", types = {"string", "null"},
                    example = "5.2.1") String edition) {

        static SystemSummaryView of(Library.GameSystem system) {
            return new SystemSummaryView(system.id().toString(), system.name(), system.edition());
        }

        @Override
        public String toString() {
            return "SystemSummaryView[id=" + id + "]";
        }
    }

    @Schema(name = "Campaign", requiredProperties = {"id", "name", "system", "archived", "version", "partySize",
        "partyLevel"})
    record CampaignView(
            @Schema(description = "The campaign's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000002")
            String id,
            @Schema(description = "The campaign's name", example = "Primeira campanha") String name,
            @Schema(description = "The campaign's system") SystemView system,
            @Schema(description = "Whether the GM archived it (contract 1.19.0)") boolean archived,
            @Schema(description = "The campaign's version: a rename, an archive, a restore or a change of its party"
                    + " (contract 1.61.0) names it and raises it by one; choosing the campaign does not (contract"
                    + " 1.19.0)", format = "int64", example = "0")
            long version,
            @Schema(description = "How many players the GM's party has, 1 to 20; null while not set (contract 1.61.0)",
                    types = {"integer", "null"}, format = "int32", minimum = "1", maximum = "20", example = "4")
            Integer partySize,
            @Schema(description = "The party's level, 1 to 999; null while not set (contract 1.61.0)",
                    types = {"integer", "null"}, format = "int32", minimum = "1", maximum = "999", example = "5")
            Integer partyLevel) {

        static CampaignView of(Library.Campaign campaign) {
            return new CampaignView(campaign.id().toString(), campaign.name(), SystemView.of(campaign.system()),
                    campaign.archived(), campaign.version(), campaign.partySize(), campaign.partyLevel());
        }

        @Override
        public String toString() {
            return "CampaignView[id=" + id + ", version=" + version + "]";
        }
    }

    @Schema(name = "CampaignSummary", requiredProperties = {"id", "name", "system", "archived"})
    record CampaignSummaryView(
            @Schema(description = "The campaign's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000002")
            String id,
            @Schema(description = "The campaign's name", example = "Primeira campanha") String name,
            @Schema(description = "The campaign's system (contract 1.19.0)") SystemSummaryView system,
            @Schema(description = "Whether the GM archived it (contract 1.19.0)") boolean archived) {

        static CampaignSummaryView of(Library.Campaign campaign) {
            return new CampaignSummaryView(campaign.id().toString(), campaign.name(),
                    SystemSummaryView.of(campaign.system()), campaign.archived());
        }

        @Override
        public String toString() {
            return "CampaignSummaryView[id=" + id + "]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "SystemPage", requiredProperties = {"items", "total"})
    record SystemPageView(
            @ArraySchema(schema = @Schema(implementation = SystemView.class)) List<SystemView> items,
            @Schema(description = "The cursor of the next page; absent on the last", format = "uuid",
                    example = CURSOR_EXAMPLE) String nextCursor,
            @Schema(description = TOTAL, format = "int64", example = "3") long total) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "CampaignPage", requiredProperties = {"items", "total"})
    record CampaignPageView(
            @ArraySchema(schema = @Schema(implementation = CampaignSummaryView.class)) List<CampaignSummaryView> items,
            @Schema(description = "The cursor of the next page; absent on the last", format = "uuid",
                    example = "0190a0b0-0000-7000-8000-000000000002") String nextCursor,
            @Schema(description = TOTAL, format = "int64", example = "4") long total) {
    }

    @Schema(name = "SystemDeletion", requiredProperties = {"campaigns", "conversations", "materials", "artifacts"})
    record SystemDeletionView(
            @Schema(description = "How many campaigns of the system go with it", format = "int64", minimum = "0",
                    example = "2") long campaigns,
            @Schema(description = "How many conversations of those campaigns go with it", format = "int64",
                    minimum = "0", example = "3") long conversations,
            @Schema(description = "How many materials go with it: those of its campaigns, and those attached to it and"
                    + " to no other system", format = "int64", minimum = "0", example = "1") long materials,
            @Schema(description = "How many artifacts of those campaigns go with it (contract 1.42.0)", format = "int64",
                    minimum = "0", example = "4") long artifacts) {

        static SystemDeletionView of(SystemDeletion.Counts counts) {
            return new SystemDeletionView(counts.campaigns(), counts.conversations(), counts.materials(),
                    counts.artifacts());
        }
    }

    @Schema(name = "CampaignDeletion", requiredProperties = {"conversations", "materials", "artifacts"})
    record CampaignDeletionView(
            @Schema(description = "How many conversations of the campaign go with it", format = "int64", minimum = "0",
                    example = "1") long conversations,
            @Schema(description = "How many materials attached to the campaign go with it (those with a version that"
                    + " is not discarded)", format = "int64", minimum = "0", example = "2") long materials,
            @Schema(description = "How many artifacts of the campaign go with it (contract 1.42.0)", format = "int64",
                    minimum = "0", example = "3") long artifacts) {

        static CampaignDeletionView of(SystemDeletion.CampaignCounts counts) {
            return new CampaignDeletionView(counts.conversations(), counts.materials(), counts.artifacts());
        }
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

    @Schema(name = "NewCampaign", requiredProperties = {"name", "systemId"})
    record NewCampaign(
            @Schema(description = "1 to 120 code points after trimming; unique within the system, whatever the case",
                    minLength = 1, maxLength = 120, example = "Mesa de quinta") String name,
            @Schema(description = "The account's system the campaign belongs to; it never changes", format = "uuid",
                    example = "0190a0b0-0000-7000-8000-000000000001") String systemId) {

        @Override
        public String toString() {
            return "NewCampaign[systemId=" + systemId + "]";
        }
    }

    @Schema(name = "CampaignChange", requiredProperties = "version")
    record CampaignChangeBody(
            @Schema(description = "The version the edit was made on, as the campaign was read", format = "int64",
                    minimum = "0", example = "0") Long version,
            @Schema(description = "The new name, 1 to 120 code points after trimming; absent or null: unchanged",
                    minLength = 1, maxLength = 120, example = "Mesa de sexta") String name,
            @Schema(description = "True archives the campaign, false restores it; absent or null: unchanged",
                    example = "true") Boolean archived) {

        @Override
        public String toString() {
            return "CampaignChangeBody[version=" + version + ", archived=" + archived + "]";
        }
    }

    @Schema(name = "CampaignPartyChange", requiredProperties = {"version"})
    record CampaignPartyBody(
            @Schema(description = "The version the change was made on, as the campaign was read", format = "int64",
                    minimum = "0", example = "3") Long version,
            @Schema(description = "How many players, a whole number from 1 to 20; absent or null: not set",
                    implementation = Integer.class, types = {"integer", "null"}, format = "int32", minimum = "1",
                    maximum = "20", example = "4") JsonNode size,
            @Schema(description = "The party's level, a whole number from 1 to 999 (most systems go from 1 to 20;"
                    + " supplements may go beyond); absent or null: not set", implementation = Integer.class,
                    types = {"integer", "null"}, format = "int32", minimum = "1", maximum = "999", example = "5")
            JsonNode level) {

        @Override
        public String toString() {
            return "CampaignPartyBody[version=" + version + "]";
        }
    }

    @Schema(name = "CurrentCampaignChoice", requiredProperties = "campaignId")
    record CurrentCampaignBody(
            @Schema(description = "The account's campaign to make current; not an archived one", format = "uuid",
                    example = "0190a0b0-0000-7000-8000-000000000002") String campaignId) {
    }

    @GetMapping(path = CURRENT_CAMPAIGN, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getCurrentCampaign", summary = "The current campaign with its system: of the account's"
            + " campaigns that are not archived, the one chosen last (PUT), else the oldest; session.required (401)"
            + " without a session, request.not_found (404) when the account has no such campaign",
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<CampaignView> currentCampaign() {
        return found(library.currentCampaign().map(CampaignView::of));
    }

    @PutMapping(path = CURRENT_CAMPAIGN, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "chooseCurrentCampaign", summary = "Makes one of the account's campaigns the current one and"
            + " answers it; its version does not change. request.not_found (404) for an archived campaign or any other"
            + " id (unknown, another account's or malformed), request.invalid (400) without a campaignId or for a"
            + " malformed body; nothing changes then;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<CampaignView> chooseCurrentCampaign(@RequestBody CurrentCampaignBody body) {
        if (body == null || body.campaignId() == null) {
            throw invalid();
        }
        return found(parse(body.campaignId()).flatMap(library::chooseCampaign).map(CampaignView::of));
    }

    @GetMapping(path = CAMPAIGNS, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listCampaigns", summary = "The account's campaigns, of one of its systems when system is"
            + " given, newest first, each with its system; the archived ones only with archived=true, else the others."
            + " A page starts after a cursor or at an offset (a numbered page), and names the total of the list."
            + " request.not_found (404) for a system that is not the account's (unknown, another account's or"
            + " malformed); request.invalid (400) for an archived other than true or false, a limit outside 1 to 50, a"
            + " malformed cursor, an offset outside 0 to 100000 or a cursor together with an offset above 0;"
            + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "system", in = ParameterIn.QUERY, description = "Only this system's campaigns",
                        schema = @Schema(type = "string", format = "uuid")),
                @Parameter(name = "archived", in = ParameterIn.QUERY, description = "true: only the archived campaigns;"
                        + " false (the default): only the others",
                        schema = @Schema(type = "boolean", defaultValue = "false")),
                @Parameter(name = "limit", in = ParameterIn.QUERY, description = "At most this many, 1 to 50",
                        schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "50",
                                defaultValue = "20")),
                @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "The nextCursor of the previous page",
                        schema = @Schema(type = "string", format = "uuid")),
                @Parameter(name = "offset", in = ParameterIn.QUERY, description = OFFSET,
                        schema = @Schema(type = "integer", format = "int32", minimum = "0", maximum = "100000",
                                defaultValue = "0"))})
    ResponseEntity<CampaignPageView> campaigns(
            @Parameter(hidden = true) @RequestParam(value = "system", required = false) String system,
            @Parameter(hidden = true) @RequestParam(value = "archived", required = false) String archived,
            @Parameter(hidden = true) @RequestParam(value = "limit", required = false) String limit,
            @Parameter(hidden = true) @RequestParam(value = "cursor", required = false) String cursor,
            @Parameter(hidden = true) @RequestParam(value = "offset", required = false) String offset) {
        boolean onlyArchived = flag(archived);
        int size = limit(limit);
        UUID after = cursor(cursor);
        int from = offset(offset, after);
        UUID systemId = system == null ? null : parse(system).orElseThrow(LibraryController::notFound);
        Library.CampaignPage page = library.campaigns(systemId, onlyArchived, after, from, size)
                .orElseThrow(LibraryController::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CampaignPageView(
                page.items().stream().map(CampaignSummaryView::of).toList(), text(page.nextCursor()), page.total()));
    }

    @PostMapping(path = CAMPAIGNS, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createCampaign", summary = "A new campaign of one of the account's systems (201), with the"
            + " starting scope; it becomes the current campaign. request.campaign_name_invalid (400) for a name outside"
            + " its limits (params min and max), request.campaign_name_taken (409) when the system has a campaign with"
            + " the same name (trimmed, whatever the case; an archived campaign keeps its name), request.not_found (404) for a system that is not the"
            + " account's (unknown, another account's, malformed or being deleted), request.invalid (400) without a"
            + " systemId or for a malformed body; nothing is stored then;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<CampaignView> createCampaign(@RequestBody NewCampaign body) {
        if (body == null || body.systemId() == null) {
            throw invalid();
        }
        UUID systemId = parse(body.systemId()).orElseThrow(LibraryController::notFound);
        Library.Campaign created = library.createCampaign(systemId, body.name()).orElseThrow(LibraryController::notFound);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(CampaignView.of(created));
    }

    @PatchMapping(path = CAMPAIGN, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "changeCampaign", summary = "Renames, archives or restores one of the account's campaigns on"
            + " the version it names: the campaign as stored, its version raised by one. An archived campaign leaves the"
            + " lists and the current position but keeps everything and still opens by id. request.stale_version (409)"
            + " when the campaign has left that version, request.campaign_name_taken (409) when another campaign of its"
            + " system has the new name (an archived campaign keeps its name), request.campaign_name_invalid (400) for a"
            + " name outside its limits, request.invalid (400) without a version, with neither name nor archived, or for"
            + " a malformed body, request.not_found (404) for any other id; nothing changes then;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<CampaignView> changeCampaign(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @RequestBody CampaignChangeBody body) {
        UUID campaignId = parse(id).orElseThrow(LibraryController::notFound);
        if (body == null || body.version() == null || body.version() < 0 || (body.name() == null && body.archived() == null)) {
            throw invalid();
        }
        return found(library.changeCampaign(campaignId, body.version(), body.name(), body.archived())
                .map(CampaignView::of));
    }

    @PutMapping(path = CAMPAIGN_PARTY, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "changeCampaignParty", summary = "Replaces the party size and level of one of the"
            + " account's campaigns on the version it names (null or absent clears a value): the campaign as stored,"
            + " its version raised by one. A question asked before keeps the values it was asked with; the change"
            + " applies from the next question. request.party_size_invalid (400) for a size that is not a whole number"
            + " from 1 to 20, request.party_level_invalid (400) for a level that is not a whole number from 1 to 999"
            + " (params min and max), request.stale_version (409) when the campaign has left that version,"
            + " request.invalid (400) without a version or for a malformed body, request.not_found (404) for any other"
            + " id; nothing changes then;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<CampaignView> changeCampaignParty(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @RequestBody CampaignPartyBody body) {
        UUID campaignId = parse(id).orElseThrow(LibraryController::notFound);
        if (body == null || body.version() == null || body.version() < 0) {
            throw invalid();
        }
        return found(library.changeParty(campaignId, body.version(), partyNumber(body.size()), partyNumber(body.level()))
                .map(CampaignView::of));
    }

    /**
     * A party value as sent (Story 7.1): absent or null is null; a JSON number is its number, whole or not; anything
     * else (a string, a boolean...) is {@link Double#NaN}, a number that is never whole, so the library refuses it with
     * the field's own problem.
     */
    private static Number partyNumber(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return value.isNumber() ? value.numberValue() : Double.valueOf(Double.NaN);
    }

    @GetMapping(path = CAMPAIGN, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getCampaign", summary = "One of the account's campaigns, with its system, archived or"
            + " not; request.not_found (404) for any other id",
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<CampaignView> campaign(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        return found(parse(id).flatMap(library::campaign).map(CampaignView::of));
    }

    @GetMapping(path = CAMPAIGN_DELETION, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getCampaignDeletion", summary = "What goes with one of the account's campaigns if it is"
            + " deleted: how many conversations and materials (those attached to it). request.not_found (404) for any"
            + " other id, a campaign being deleted included;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<CampaignDeletionView> campaignDeletion(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        return found(parse(id).flatMap(deletion::previewCampaign).map(CampaignDeletionView::of));
    }

    @DeleteMapping(path = CAMPAIGN)
    @Operation(operationId = "deleteCampaign", summary = "Deletes one of the account's campaigns, archived, current or"
            + " last (202, no body): from now on it is not found, the current campaign falls back to another (or there"
            + " is none), its name is free in its system, and its conversation and the materials attached to it are"
            + " removed in the background; nothing of its system or of the other campaigns changes. Cannot be undone."
            + " request.not_found (404) for any other id, a campaign already being deleted included;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "202", description = "The deletion started"))
    ResponseEntity<Void> deleteCampaign(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        if (!parse(id).map(deletion::deleteCampaign).orElse(false)) {
            throw notFound();
        }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping(path = SYSTEMS, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listSystems", summary = "The account's game systems, newest first. A page starts after a"
            + " cursor or at an offset (a numbered page), and names the total of the list. request.invalid (400)"
            + " for a limit outside 1 to 50, a malformed cursor, an offset outside 0 to 100000 or a cursor together"
            + " with an offset above 0;" + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "limit", in = ParameterIn.QUERY, description = "At most this many, 1 to 50",
                        schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "50",
                                defaultValue = "20")),
                @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "The nextCursor of the previous page",
                        schema = @Schema(type = "string", format = "uuid")),
                @Parameter(name = "offset", in = ParameterIn.QUERY, description = OFFSET,
                        schema = @Schema(type = "integer", format = "int32", minimum = "0", maximum = "100000",
                                defaultValue = "0"))})
    ResponseEntity<SystemPageView> systems(
            @Parameter(hidden = true) @RequestParam(value = "limit", required = false) String limit,
            @Parameter(hidden = true) @RequestParam(value = "cursor", required = false) String cursor,
            @Parameter(hidden = true) @RequestParam(value = "offset", required = false) String offset) {
        int size = limit(limit);
        UUID after = cursor(cursor);
        Library.SystemPage page = library.systems(after, offset(offset, after), size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SystemPageView(
                page.items().stream().map(SystemView::of).toList(), text(page.nextCursor()), page.total()));
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
        UUID systemId = parse(id).orElseThrow(LibraryController::notFound);
        if (body == null || body.version() == null || body.version() < 0) {
            throw invalid();
        }
        return found(library.changeSystem(systemId, body.version(),
                new Library.SystemFields(body.name(), body.edition(), body.description())).map(SystemView::of));
    }

    @GetMapping(path = SYSTEM_DELETION, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getSystemDeletion", summary = "What goes with one of the account's game systems if it is"
            + " deleted: how many campaigns, conversations and materials (those of its campaigns, and those attached to"
            + " it and to no other system). request.not_found (404) for any other id, a system being deleted"
            + " included;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<SystemDeletionView> systemDeletion(
            @Parameter(description = "The system's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        return found(parse(id).flatMap(deletion::preview).map(SystemDeletionView::of));
    }

    @DeleteMapping(path = SYSTEM)
    @Operation(operationId = "deleteSystem", summary = "Deletes one of the account's game systems (202, no body): from"
            + " now on it and its campaigns are not found, and its campaigns, their conversations and the materials"
            + " attached only to it are removed in the background; a material also attached to another system stays"
            + " and only loses this attachment. Cannot be undone. request.not_found (404) for any other id, a system"
            + " already being deleted included;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "202", description = "The deletion started"))
    ResponseEntity<Void> deleteSystem(
            @Parameter(description = "The system's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        if (!parse(id).map(deletion::delete).orElse(false)) {
            throw notFound();
        }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    /** A malformed id is no different from an unknown one. */
    static Optional<UUID> parse(String id) {
        return id != null && UUID_TEXT.matcher(id).matches() ? Optional.of(UUID.fromString(id)) : Optional.empty();
    }

    /** A yes-or-no query parameter: false without one, else true or false, or {@code request.invalid}. */
    private static boolean flag(String value) {
        if (value == null || "false".equals(value)) {
            return false;
        }
        if ("true".equals(value)) {
            return true;
        }
        throw invalid();
    }

    /** A page's size: the default without one, else 1 to 50, or {@code request.invalid}. */
    static int limit(String limit) {
        if (limit == null) {
            return Library.DEFAULT_LIMIT;
        }
        if (!LIMIT.matcher(limit).matches() || Integer.parseInt(limit) > Library.MAX_LIMIT) {
            throw invalid();
        }
        return Integer.parseInt(limit);
    }

    /** The cursor of a page: none, or an id, else {@code request.invalid}. */
    static UUID cursor(String cursor) {
        return cursor == null ? null : parse(cursor).orElseThrow(LibraryController::invalid);
    }

    /**
     * The position a page starts at: 0 without one, else 0 to {@value Library#MAX_OFFSET}; {@code request.invalid} for
     * anything else, and for a position above 0 together with a cursor (a page starts at one or the other).
     */
    static int offset(String offset, UUID cursor) {
        if (offset == null) {
            return 0;
        }
        if (!OFFSET_TEXT.matcher(offset).matches() || Integer.parseInt(offset) > Library.MAX_OFFSET
                || (cursor != null && Integer.parseInt(offset) > 0)) {
            throw invalid();
        }
        return Integer.parseInt(offset);
    }

    static String text(UUID id) {
        return id == null ? null : id.toString();
    }

    static ApiException invalid() {
        return new ApiException(ApiProblem.forStatus(400));
    }

    static ApiException notFound() {
        return new ApiException(ApiProblem.forStatus(404));
    }

    static <T> ResponseEntity<T> found(Optional<T> body) {
        return body.map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
                .orElseThrow(LibraryController::notFound);
    }
}
