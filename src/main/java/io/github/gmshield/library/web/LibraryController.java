package io.github.gmshield.library.web;

import io.github.gmshield.library.service.Library;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The owner's systems and campaigns (Story 1.11), read only until Epic 3. The owner is the session's account,
 * set by the request edge ({@code accounts.web}) before any of these runs; nothing here takes an owner from the
 * request. Another owner's id, an unknown id and a malformed id are the same {@code request.not_found} (404).
 * Every answer is {@code no-store}.
 */
@RestController
@Tag(name = "library")
class LibraryController {

    static final String CURRENT_CAMPAIGN = "/api/v1/campaigns/current";
    static final String CAMPAIGN = "/api/v1/campaigns/{id}";
    static final String SYSTEM = "/api/v1/systems/{id}";
    /** A UUID in its canonical text form; anything else is an id that exists nowhere. */
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

    private final Library library;

    LibraryController(Library library) {
        this.library = library;
    }

    @Schema(name = "System", requiredProperties = {"id", "name"})
    record SystemView(
            @Schema(description = "The system's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000001")
            String id,
            @Schema(description = "The system's name", example = "Sistema sem nome") String name) {

        static SystemView of(Library.Named system) {
            return new SystemView(system.id().toString(), system.name());
        }

        @Override
        public String toString() {
            return "SystemView[id=" + id + "]";
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

    @GetMapping(path = CURRENT_CAMPAIGN, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getCurrentCampaign", summary = "The current campaign with its system; session.required"
            + " (401) without a session, request.not_found (404) when the account has no campaign",
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<CampaignView> currentCampaign() {
        return found(library.currentCampaign().map(CampaignView::of));
    }

    @GetMapping(path = CAMPAIGN, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getCampaign", summary = "One of the account's campaigns, with its system;"
            + " request.not_found (404) for any other id",
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<CampaignView> campaign(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        return found(parse(id).flatMap(library::campaign).map(CampaignView::of));
    }

    @GetMapping(path = SYSTEM, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getSystem", summary = "One of the account's game systems; request.not_found (404)"
            + " for any other id",
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<SystemView> system(
            @Parameter(description = "The system's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        return found(parse(id).flatMap(library::system).map(SystemView::of));
    }

    /** A malformed id is no different from an unknown one. */
    private static Optional<UUID> parse(String id) {
        return id != null && UUID_TEXT.matcher(id).matches() ? Optional.of(UUID.fromString(id)) : Optional.empty();
    }

    private static <T> ResponseEntity<T> found(Optional<T> body) {
        return body.map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
                .orElseThrow(() -> new ApiException(ApiProblem.forStatus(404)));
    }
}
