package io.github.gmshield.scope.web;

import io.github.gmshield.scope.service.CampaignScopes;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A campaign's scope settings (Story 3.5): read them with the materials of every tier, and change them on the version
 * read. The owner is the session's account, set by the request edge ({@code accounts.web}); another owner's campaign,
 * an unknown one, a malformed id and a campaign being deleted are the same {@code request.not_found} (404). Every
 * answer is {@code no-store}. A change applies from the next question.
 */
@RestController
@Tag(name = "scope")
class ScopeController {

    static final String SCOPE = "/api/v1/campaigns/{id}/scope";
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final String SESSION = "X-Session-Token";

    private final CampaignScopes scopes;

    ScopeController(CampaignScopes scopes) {
        this.scopes = scopes;
    }

    @Schema(name = "ScopeMaterial", requiredProperties = {"id", "name", "tier", "on"})
    record MaterialView(
            @Schema(description = "The material's id", format = "uuid") String id,
            @Schema(description = "The material's name") String name,
            @Schema(description = "The tier it falls in", allowableValues = {"campaign", "system", "other"})
            String tier,
            @Schema(description = "Whether it is switched on (a material is on unless switched off)") boolean on) {

        static MaterialView of(CampaignScopes.Material material) {
            return new MaterialView(material.id().toString(), material.name(), material.tier().word(),
                    material.on());
        }

        @Override
        public String toString() {
            return "MaterialView[id=" + id + ", tier=" + tier + ", on=" + on + "]";
        }
    }

    @Schema(name = "Scope", requiredProperties = {"version", "campaignTier", "systemTier", "otherTier",
            "modelKnowledge", "materials"})
    record ScopeView(
            @Schema(description = "The settings' version: a change names it, and each change raises it by one; 0 for"
                    + " a campaign whose settings never changed", format = "int64", example = "0") long version,
            @Schema(description = "Whether the campaign's own materials are used") boolean campaignTier,
            @Schema(description = "Whether the materials of the campaign's system are used") boolean systemTier,
            @Schema(description = "Whether the account's other materials (of its other campaigns, archived ones"
                    + " included, and of its other systems) are used") boolean otherTier,
            @Schema(description = "Whether the model may answer from its own knowledge, each such part under the"
                    + " model tier label and with no reference; a change applies from the next question")
            boolean modelKnowledge,
            @ArraySchema(arraySchema = @Schema(description = "The materials of every tier, whatever the tier's switch,"
                    + " each with a ready version: in tier order (campaign, system, other), then newest first"),
                    schema = @Schema(implementation = MaterialView.class)) List<MaterialView> materials) {

        static ScopeView of(CampaignScopes.View view) {
            return new ScopeView(view.version(), view.settings().campaignTier(), view.settings().systemTier(),
                    view.settings().otherTier(), view.settings().modelKnowledge(),
                    view.materials().stream().map(MaterialView::of).toList());
        }

        @Override
        public String toString() {
            return "ScopeView[version=" + version + ", materials=" + materials.size() + "]";
        }
    }

    @Schema(name = "ScopeChange", requiredProperties = {"version", "campaignTier", "systemTier", "otherTier",
            "modelKnowledge", "materialsOff"})
    record ScopeChangeBody(
            @Schema(description = "The version the change was made on, as the settings were read", format = "int64",
                    minimum = "0", example = "0") Long version,
            @Schema(description = "Whether the campaign's own materials are used") Boolean campaignTier,
            @Schema(description = "Whether the materials of the campaign's system are used") Boolean systemTier,
            @Schema(description = "Whether the account's other materials are used") Boolean otherTier,
            @Schema(description = "Whether the model may answer from its own knowledge") Boolean modelKnowledge,
            @ArraySchema(maxItems = CampaignScopes.MAX_MATERIALS_OFF,
                    arraySchema = @Schema(description = "The materials switched off, 0 to 1000 ids of materials in"
                            + " one of the campaign's tiers; every other material is on"),
                    schema = @Schema(type = "string", format = "uuid")) List<UUID> materialsOff) {

        @Override
        public String toString() {
            return "ScopeChangeBody[version=" + version + ", materialsOff="
                    + (materialsOff == null ? 0 : materialsOff.size()) + "]";
        }
    }

    @GetMapping(path = SCOPE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getCampaignScope", summary = "One of the account's campaigns' scope settings: the three"
            + " tier switches, model knowledge, their version (0 when never changed: campaign and system on, other and"
            + " model knowledge off) and the materials of every tier, each with whether it is on. request.not_found"
            + " (404) for any other id; session.required (401) without a session",
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<ScopeView> scope(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        return found(parse(id).flatMap(scopes::view).map(ScopeView::of));
    }

    @PutMapping(path = SCOPE, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "changeCampaignScope", summary = "Changes one of the account's campaigns' scope settings on"
            + " the version it names, from the next question on: the switches replaced, the materials off exactly those"
            + " listed. Answers the settings as stored, their version raised by one. request.stale_version (409) when"
            + " the settings have left that version, scope.material_unknown (400) for a material id that is not the"
            + " account's or in none of the campaign's tiers, request.invalid (400) for a missing field, a null or"
            + " malformed id, more than 1000 ids or a malformed body, request.not_found (404) for any other campaign"
            + " id; nothing changes then; session.required (401) without a session",
            parameters = @Parameter(name = SESSION, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<ScopeView> changeScope(
            @Parameter(description = "The campaign's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @RequestBody ScopeChangeBody body) {
        UUID campaignId = parse(id).orElseThrow(ScopeController::notFound);
        if (body == null || body.version() == null || body.version() < 0 || body.campaignTier() == null
                || body.systemTier() == null || body.otherTier() == null || body.modelKnowledge() == null
                || body.materialsOff() == null || body.materialsOff().size() > CampaignScopes.MAX_MATERIALS_OFF
                || body.materialsOff().stream().anyMatch(Objects::isNull)) {
            throw new ApiException(ApiProblem.forStatus(400));
        }
        return found(scopes.change(campaignId, new CampaignScopes.Change(body.version(), body.campaignTier(),
                body.systemTier(), body.otherTier(), body.modelKnowledge(), body.materialsOff()))
                .map(ScopeView::of));
    }

    /** A malformed id is no different from an unknown one. */
    private static Optional<UUID> parse(String id) {
        return id != null && UUID_TEXT.matcher(id).matches() ? Optional.of(UUID.fromString(id)) : Optional.empty();
    }

    private static ApiException notFound() {
        return new ApiException(ApiProblem.forStatus(404));
    }

    private static <T> ResponseEntity<T> found(Optional<T> body) {
        return body.map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
                .orElseThrow(ScopeController::notFound);
    }
}
