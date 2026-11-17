package io.github.gmshield.materials.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.gmshield.materials.service.Materials;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The owner's materials (Story 1.14): a list by the state of each material's newest version, and one material. The
 * owner is the session's account, set by the request edge ({@code accounts.web}) before any of these runs; nothing
 * here takes an owner from the request. Another owner's id, an unknown id and a malformed id are the same
 * {@code request.not_found} (404). Every answer is {@code no-store}.
 */
@RestController
@Tag(name = "materials")
class MaterialController {

    static final String MATERIALS = "/api/v1/materials";
    static final String MATERIAL = "/api/v1/materials/{id}";
    /** A UUID in its canonical text form; anything else is an id that exists nowhere. */
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Pattern LIMIT = Pattern.compile("[1-9][0-9]?");
    private static final String SESSION_SUMMARY = " session.required (401) without a session.";

    private final Materials materials;

    MaterialController(Materials materials) {
        this.materials = materials;
    }

    @Schema(name = "MaterialUpload", requiredProperties = {"id", "offset", "length", "expiresAt"})
    record UploadView(
            @Schema(description = "The upload's id: it resumes at /api/v1/uploads/{id}", format = "uuid",
                    example = "0190a0b0-0000-7000-8000-000000000005") String id,
            @Schema(description = "Bytes verified and stored", format = "int64", example = "67108864") long offset,
            @Schema(description = "The file's size in bytes", format = "int64", example = "134217728") long length,
            @Schema(description = "When the unfinished upload expires", format = "date-time",
                    example = "2026-11-17T06:00:00Z") String expiresAt) {

        static UploadView of(Materials.Upload upload) {
            return new UploadView(upload.id().toString(), upload.offset(), upload.length(), upload.expiresAt().toString());
        }
    }

    @Schema(name = "MaterialPageCount", requiredProperties = {"done", "total"})
    record CountView(
            @Schema(description = "Pages of this kind that have an outcome", format = "int32", example = "40") int done,
            @Schema(description = "All the pages of this kind", format = "int32", example = "364") int total) {

        static CountView of(Materials.Count count) {
            return new CountView(count.done(), count.total());
        }
    }

    @Schema(name = "MaterialPages", requiredProperties = {"done", "total", "text", "image", "readDirectly", "readByOcr",
        "unreadable", "reading"})
    record PagesView(
            @Schema(description = "Pages read so far (0 until the reading stages run)", format = "int32", example = "0")
            int done,
            @Schema(description = "The version's page total", format = "int32", example = "320") int total,
            @Schema(description = "The pages found to have a text layer, and those of them with an outcome (0 of 0"
                    + " until the pages are sorted, and for a text upload)") CountView text,
            @Schema(description = "The pages found to be images only, read by OCR, and those of them with an outcome")
            CountView image,
            @Schema(description = "Pages read from their text layer", format = "int32", example = "40")
            int readDirectly,
            @Schema(description = "Pages read by OCR", format = "int32", example = "0") int readByOcr,
            @Schema(description = "Pages that could not be read", format = "int32", example = "0") int unreadable,
            @Schema(description = "The layer of the pages being read now, by the step whose job is offered or running;"
                    + " null when none is, when those pages have no layer yet, or when their layers differ",
                    types = {"string", "null"}, allowableValues = {"text", "image"}, example = "text") String reading) {

        static PagesView of(Materials.Pages pages) {
            return new PagesView(pages.done(), pages.total(), CountView.of(pages.text()), CountView.of(pages.image()),
                    pages.readDirectly(), pages.readByOcr(), pages.unreadable(), pages.reading());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "Material", requiredProperties = {"id", "name", "state"})
    record MaterialView(
            @Schema(description = "The material's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000003")
            String id,
            @Schema(description = "The material's name", example = "Livro do Jogador.pdf") String name,
            @Schema(description = "The state of the material's newest version", example = "waiting",
                    allowableValues = {"uploading", "waiting", "processing", "needs_review", "ready", "superseded",
                        "failed", "discarded"}) String state,
            @Schema(description = "What the material is, when the GM said", example = "rulebook",
                    allowableValues = {"rulebook", "supplement", "homebrew", "campaign_notes", "other"}) String kind,
            @Schema(description = "The unfinished upload, only while the state is uploading") UploadView upload,
            @Schema(description = "Pages done of the total, once the total is known") PagesView pages,
            @Schema(description = "Why the version failed (category.reason), only when the state is failed",
                    example = "ingestion.password_required") String failure) {

        static MaterialView of(Materials.Material material) {
            return new MaterialView(material.id().toString(), material.name(), material.state(), material.kind(),
                    material.upload() == null ? null : UploadView.of(material.upload()),
                    material.pages() == null ? null : PagesView.of(material.pages()),
                    material.failure());
        }

        @Override
        public String toString() {
            return "MaterialView[id=" + id + ", state=" + state + "]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "MaterialPage", requiredProperties = "items")
    record PageView(
            @ArraySchema(schema = @Schema(implementation = MaterialView.class)) List<MaterialView> items,
            @Schema(description = "The cursor of the next page; absent on the last", format = "uuid",
                    example = "0190a0b0-0000-7000-8000-000000000003") String nextCursor) {
    }

    @GetMapping(path = MATERIALS, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listMaterials", summary = "The account's materials whose newest version is in one of the"
            + " given states, newest first, with the upload's progress on those still uploading. request.invalid (400)"
            + " without a state, for an unknown state, a limit outside 1 to 50 or a malformed cursor;" + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "state", in = ParameterIn.QUERY, required = true,
                        description = "A state of the newest version; repeat it for several",
                        array = @ArraySchema(minItems = 1, schema = @Schema(type = "string",
                                allowableValues = {"uploading", "waiting", "processing", "needs_review", "ready",
                                    "superseded", "failed", "discarded"}))),
                @Parameter(name = "limit", in = ParameterIn.QUERY, description = "At most this many, 1 to 50",
                        schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "50",
                                defaultValue = "20")),
                @Parameter(name = "cursor", in = ParameterIn.QUERY,
                        description = "The nextCursor of the previous page", schema = @Schema(type = "string",
                                format = "uuid"))})
    ResponseEntity<PageView> list(@Parameter(hidden = true) @RequestParam(value = "state", required = false) List<String> states,
                                  @Parameter(hidden = true) @RequestParam(value = "limit", required = false) String limit,
                                  @Parameter(hidden = true) @RequestParam(value = "cursor", required = false) String cursor) {
        if (states == null || states.isEmpty() || !Materials.STATES.containsAll(states)) {
            throw invalid();
        }
        int size = Materials.DEFAULT_LIMIT;
        if (limit != null) {
            if (!LIMIT.matcher(limit).matches() || Integer.parseInt(limit) > Materials.MAX_LIMIT) {
                throw invalid();
            }
            size = Integer.parseInt(limit);
        }
        UUID after = null;
        if (cursor != null) {
            after = parse(cursor).orElseThrow(MaterialController::invalid);
        }
        Materials.Page page = materials.list(states, after, size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new PageView(page.items().stream().map(MaterialView::of).toList(),
                        page.nextCursor() == null ? null : page.nextCursor().toString()));
    }

    @GetMapping(path = MATERIAL, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getMaterial", summary = "One of the account's materials, with the state of its newest"
            + " version. request.not_found (404) for any other id;" + SESSION_SUMMARY,
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<MaterialView> material(
            @Parameter(description = "The material's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        return parse(id).flatMap(materials::find).map(MaterialView::of)
                .map(view -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view))
                .orElseThrow(() -> new ApiException(ApiProblem.forStatus(404)));
    }

    /** A malformed id is no different from an unknown one. */
    private static Optional<UUID> parse(String id) {
        return id != null && UUID_TEXT.matcher(id).matches() ? Optional.of(UUID.fromString(id)) : Optional.empty();
    }

    private static ApiException invalid() {
        return new ApiException(ApiProblem.forStatus(400));
    }
}
