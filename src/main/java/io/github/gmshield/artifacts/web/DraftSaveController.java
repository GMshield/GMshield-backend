package io.github.gmshield.artifacts.web;

import io.github.gmshield.artifacts.service.Artifacts;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The GM's "Salvar" on a draft's save confirmation (Story 5.4 part A, contract 1.48.0): a new artifact of the turn's
 * conversation's campaign, made from the draft (201, the artifact as {@code getArtifact} answers it). The owner is the
 * session's account, set by the request edge before this runs. A turn that is not the account's, unknown, malformed or
 * of a conversation being deleted is {@code request.not_found} (404); one that is not offered for saving is
 * {@code conversation.turn_not_saveable} (409), and one saved already {@code conversation.turn_saved} (409). A value
 * outside its limits is {@code request.invalid} (400) naming its field, as a new artifact's. Nothing is stored on a
 * refusal. Every answer is {@code no-store}.
 */
@RestController
@Tag(name = "artifacts")
class DraftSaveController {

    static final String DRAFT_ARTIFACT = "/api/v1/turns/{turnId}/artifact";
    /** A UUID in its canonical text form; anything else is an id that exists nowhere. */
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

    private final Artifacts artifacts;

    DraftSaveController(Artifacts artifacts) {
        this.artifacts = artifacts;
    }

    @Schema(name = "SavedDraft", requiredProperties = {"name", "recordType"},
            description = "What the GM saves a draft as (contract 1.48.0)")
    record SavedDraft(
            @Schema(description = "The artifact's name: 1 to 200 code points after trimming; the card suggests the"
                    + " draft's proposal", minLength = 1, maxLength = 200, example = "Emboscada na Cripta") String name,
            @Schema(description = "The key of one of the campaign's system's record types, core or approved",
                    pattern = "^[a-z][a-z0-9_]{0,31}$", example = "encounter") String recordType,
            @Schema(description = "The artifact's content, as Markdown: the draft's text as the GM left it, at most"
                    + " 20,000 code points after trimming. Absent or null: the draft's text as stored. The turn's own"
                    + " text never changes", types = {"string", "null"}, maxLength = 20000) String content) {

        @Override
        public String toString() {
            return "SavedDraft[recordType=" + recordType + "]";
        }
    }

    @PostMapping(path = DRAFT_ARTIFACT, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "saveDraftAsArtifact", summary = "Saves a draft as an artifact of its conversation's"
            + " campaign (contract 1.48.0; 201). Only a draft that is done and neither stopped nor silent is offered."
            + " In one transaction the artifact's version 1 is written (its name, record type and content, with no"
            + " fields), with its index job, its conversation and the draft's source references copied by value, and"
            + " the turn's saving reads saved from then on; one draft makes at most one artifact. The turn's events are"
            + " unchanged. request.invalid (400) with params.field naming the value outside its limits: recordType (not"
            + " a key of the campaign's system's record types, core or approved, or absent), name (1 to 200 code"
            + " points after trimming) or content (at most 20,000 after trimming); request.invalid (400) without a"
            + " field for a malformed body; conversation.turn_not_saveable (409) for a turn that is not offered for"
            + " saving (an answer, or a draft still running, stopped, failed or silent); conversation.turn_saved (409)"
            + " for a draft saved already (as from another tab); request.not_found (404) for a turn that is not the"
            + " account's (unknown, another account's or malformed) or of a conversation being deleted; nothing is"
            + " stored then. session.required (401) without a session.",
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<ArtifactController.ArtifactView> saveDraft(
            @Parameter(description = "The draft turn's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("turnId") String turnId,
            @RequestBody SavedDraft body) {
        if (turnId == null || !UUID_TEXT.matcher(turnId).matches()) {
            throw new ApiException(ApiProblem.forStatus(404));
        }
        if (body == null) {
            throw new ApiException(ApiProblem.forStatus(400));
        }
        Artifacts.Artifact saved = artifacts.saveDraft(UUID.fromString(turnId), body.name(), body.recordType(),
                body.content()).orElseThrow(() -> new ApiException(ApiProblem.forStatus(404)));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ArtifactController.ArtifactView.of(saved));
    }
}
