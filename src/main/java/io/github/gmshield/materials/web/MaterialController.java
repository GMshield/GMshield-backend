package io.github.gmshield.materials.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.gmshield.materials.service.MaterialActions;
import io.github.gmshield.materials.service.MaterialBelongings;
import io.github.gmshield.materials.service.MaterialDeletions;
import io.github.gmshield.materials.service.Materials;
import io.github.gmshield.materials.service.PageProblems;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * The owner's materials (Story 1.14): a list by the state of each material's current version, and one material. The
 * owner is the session's account, set by the request edge ({@code accounts.web}) before any of these runs; nothing
 * here takes an owner from the request. Another owner's id, an unknown id and a malformed id are the same
 * {@code request.not_found} (404). Every answer is {@code no-store}. From Story 1.19, one page of a material on a
 * ground ({@code ?ground=review}: the owner reviewing its current version), as JSON, and its picture, streamed as
 * WebP; a missing or unknown ground is {@code page.ground_unknown} (400). From Story 1.20, the quality report of a
 * material's current version, and the GM's four actions on it ({@code accept}, {@code reprocess}, {@code retry},
 * {@code discard}), each answering the material; an action its state does not allow is
 * {@code material.action_unavailable} (409). From Story 3.1 the list takes {@code ?system=}: only the materials of that
 * system, attached to it or to one of its campaigns; a system that is not the owner's is {@code request.not_found}.
 * From Story 3.3 a material carries its version, size and where it belongs; the list takes every state when none is
 * given; and {@code PATCH} changes where a material belongs ({@link MaterialBelongings}). From Story 3.4 a material
 * has versions: everything here reads its current version (the newest one that is not discarded), a ready material may
 * be reprocessed as a new version, and {@code serving} names the ready version that keeps serving meanwhile. From
 * Story 3.4 part B a material names its superseded versions ({@code superseded}); {@code DELETE} deletes a material
 * and {@code DELETE .../versions/{versionId}} removes a superseded version, each a recorded deletion that answers 202
 * and runs in the background ({@link MaterialDeletions}); {@code GET .../deletion} counts what a material's deletion
 * takes.
 */
@RestController
@Tag(name = "materials")
class MaterialController {

    static final String MATERIALS = "/api/v1/materials";
    static final String MATERIAL = "/api/v1/materials/{id}";
    static final String PAGE = "/api/v1/materials/{id}/pages/{page}";
    static final String PICTURE = "/api/v1/materials/{id}/pages/{page}/picture";
    static final String REPORT = "/api/v1/materials/{id}/report";
    static final String ACCEPT = "/api/v1/materials/{id}/accept";
    static final String REPROCESS = "/api/v1/materials/{id}/reprocess";
    static final String RETRY = "/api/v1/materials/{id}/retry";
    static final String DISCARD = "/api/v1/materials/{id}/discard";
    static final String DELETION = "/api/v1/materials/{id}/deletion";
    static final String VERSION = "/api/v1/materials/{id}/versions/{versionId}";
    static final String WEBP = "image/webp";
    /** A page number as text: 1 to 9,999,999, never a sign or a leading zero; anything else is a page nowhere. */
    private static final Pattern PAGE_NUMBER = Pattern.compile("[1-9][0-9]{0,6}");
    /** A UUID in its canonical text form; anything else is an id that exists nowhere. */
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Pattern LIMIT = Pattern.compile("[1-9][0-9]?");
    /** A page's position: 0, or up to six digits with no leading zero. */
    private static final Pattern OFFSET = Pattern.compile("0|[1-9][0-9]{0,5}");
    private static final String SESSION_SUMMARY = " session.required (401) without a session.";

    private final Materials materials;
    private final MaterialActions actions;
    private final MaterialBelongings belongings;
    private final MaterialDeletions deletions;

    MaterialController(Materials materials, MaterialActions actions, MaterialBelongings belongings,
                       MaterialDeletions deletions) {
        this.materials = materials;
        this.actions = actions;
        this.belongings = belongings;
        this.deletions = deletions;
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "MaterialSetImage", requiredProperties = {"position", "uploadId", "state", "offset", "length",
        "expiresAt"})
    record SetImageView(
            @Schema(description = "The image's place in its set, from 1", format = "int32", example = "2") int position,
            @Schema(description = "The image's upload: it resumes at /api/v1/uploads/{uploadId}", format = "uuid",
                    example = "0190a0b0-0000-7000-8000-000000000006") String uploadId,
            @Schema(description = "The image's file name, while it is not complete", example = "foto-2.jpg")
            String name,
            @Schema(description = "The image's upload state", example = "failed",
                    allowableValues = {"open", "complete", "failed"}) String state,
            @Schema(description = "Bytes verified and stored", format = "int64", example = "0") long offset,
            @Schema(description = "The image's size in bytes", format = "int64", example = "3145728") long length,
            @Schema(description = "When the image's upload expires", format = "date-time",
                    example = "2026-11-17T06:00:00Z") String expiresAt,
            @Schema(description = "Why the image failed (category.reason), only when it failed",
                    example = "upload.file_checksum_mismatch",
                    allowableValues = {"upload.file_checksum_mismatch", "upload.format_refused", "upload.expired"})
            String failure) {

        static SetImageView of(Materials.SetImage image) {
            return new SetImageView(image.position(), image.uploadId().toString(), image.name(), image.state(),
                    image.offset(), image.length(), image.expiresAt().toString(), image.failure());
        }

        @Override
        public String toString() {
            return "SetImageView[position=" + position + ", state=" + state + "]";
        }
    }

    @Schema(name = "MaterialImageSet", requiredProperties = {"id", "images"})
    record ImageSetView(
            @Schema(description = "The set's id: /api/v1/image-sets/{id}", format = "uuid",
                    example = "0190a0b0-0000-7000-8000-000000000004") String id,
            @ArraySchema(arraySchema = @Schema(description = "The set's images, in their order"),
                    schema = @Schema(implementation = SetImageView.class)) List<SetImageView> images) {

        static ImageSetView of(Materials.ImageSet set) {
            return new ImageSetView(set.id().toString(), set.images().stream().map(SetImageView::of).toList());
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
            @Schema(description = "The layer of the pages being read now, by the step whose job is offered or running:"
                    + " \"text\" or \"image\"; null when none is, when those pages have no layer yet, or when their"
                    + " layers differ", types = {"string", "null"}, example = "text") String reading) {

        static PagesView of(Materials.Pages pages) {
            return new PagesView(pages.done(), pages.total(), CountView.of(pages.text()), CountView.of(pages.image()),
                    pages.readDirectly(), pages.readByOcr(), pages.unreadable(), pages.reading());
        }
    }

    @Schema(name = "MaterialPlace", requiredProperties = {"id", "name"})
    record PlaceView(
            @Schema(description = "The system's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000001")
            String id,
            @Schema(description = "The system's name", example = "D&D 5e") String name) {

        static PlaceView of(Materials.Place place) {
            return new PlaceView(place.id().toString(), place.name());
        }

        @Override
        public String toString() {
            return "PlaceView[id=" + id + "]";
        }
    }

    @Schema(name = "MaterialCampaign", requiredProperties = {"id", "name", "system"})
    record CampaignPlaceView(
            @Schema(description = "The campaign's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000002")
            String id,
            @Schema(description = "The campaign's name", example = "Primeira campanha") String name,
            @Schema(description = "The campaign's system") PlaceView system) {

        static CampaignPlaceView of(Materials.CampaignPlace campaign) {
            return new CampaignPlaceView(campaign.id().toString(), campaign.name(), PlaceView.of(campaign.system()));
        }

        @Override
        public String toString() {
            return "CampaignPlaceView[id=" + id + "]";
        }
    }

    @Schema(name = "MaterialBelonging", requiredProperties = {"campaign", "systems"}, description = "Where the"
            + " material belongs (contract 1.21.0): one campaign, or one or more systems; both empty for a material"
            + " uploaded with no place. Only places that are not being deleted are named")
    record BelongingView(
            @Schema(description = "The campaign it belongs to; null when it belongs to systems or to nothing",
                    types = {"object", "null"}, implementation = CampaignPlaceView.class) CampaignPlaceView campaign,
            @ArraySchema(arraySchema = @Schema(description = "The systems it belongs to, oldest attachment first; empty"
                    + " when it belongs to a campaign or to nothing"),
                    schema = @Schema(implementation = PlaceView.class)) List<PlaceView> systems) {

        static BelongingView of(Materials.Belonging belonging) {
            return new BelongingView(belonging.campaign() == null ? null : CampaignPlaceView.of(belonging.campaign()),
                    belonging.systems().stream().map(PlaceView::of).toList());
        }
    }

    @Schema(name = "MaterialServing", requiredProperties = {"pages", "size"}, description = "The material's ready"
            + " version, which keeps serving every scope while a newer version is processed (contract 1.23.0)")
    record ServingView(
            @Schema(description = "Its page total; null when not known", types = {"integer", "null"}, format = "int32",
                    example = "320") Integer pages,
            @Schema(description = "Its size in bytes; null when not known", types = {"integer", "null"},
                    format = "int64", example = "134217728") Long size) {

        static ServingView of(Materials.Serving serving) {
            return serving == null ? null : new ServingView(serving.pages(), serving.size());
        }
    }

    @Schema(name = "MaterialSuperseded", requiredProperties = {"id", "createdAt", "pages", "size"},
            description = "A superseded version of the material, which the GM may remove (contract 1.24.0)")
    record SupersededView(
            @Schema(description = "The version's id: DELETE /api/v1/materials/{id}/versions/{versionId} removes it",
                    format = "uuid", example = "0190a0b0-0000-7000-8000-00000000000a") String id,
            @Schema(description = "When the version was uploaded", format = "date-time",
                    example = "2026-11-10T14:00:00Z") String createdAt,
            @Schema(description = "Its page total; null when not known", types = {"integer", "null"}, format = "int32",
                    example = "320") Integer pages,
            @Schema(description = "Its size in bytes; null when not known", types = {"integer", "null"},
                    format = "int64", example = "134217728") Long size) {

        static SupersededView of(Materials.Superseded version) {
            return new SupersededView(version.id().toString(), version.createdAt().toString(), version.pages(),
                    version.size());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "Material", requiredProperties = {"id", "name", "state", "actions", "version", "belonging",
        "serving", "superseded"})
    record MaterialView(
            @Schema(description = "The material's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000003")
            String id,
            @Schema(description = "The material's name", example = "Livro do Jogador.pdf") String name,
            @Schema(description = "The state of the material's current version: its newest version that is not"
                    + " discarded, or its newest one when every version is discarded (contract 1.23.0)",
                    example = "waiting",
                    allowableValues = {"uploading", "waiting", "processing", "needs_review", "ready", "superseded",
                        "failed", "discarded"}) String state,
            @Schema(description = "What the material is, when the GM said", example = "rulebook",
                    allowableValues = {"rulebook", "supplement", "homebrew", "campaign_notes", "other"}) String kind,
            @Schema(description = "The unfinished upload, only while the state is uploading") UploadView upload,
            @Schema(description = "Pages done of the total, once the total is known") PagesView pages,
            @Schema(description = "Why the version failed (category.reason), only when the state is failed",
                    example = "ingestion.password_required") String failure,
            @ArraySchema(arraySchema = @Schema(description = "The actions the current version allows now, in this"
                    + " order: accept and discard while it needs review, reprocess too when a page is unreadable;"
                    + " retry (when it can start again) and discard while it is failed; reprocess while it is ready"
                    + " (contract 1.23.0: a new version, read from its cleaned original); none otherwise"),
                    schema = @Schema(type = "string", allowableValues = {"accept", "reprocess", "retry", "discard"}))
            List<String> actions,
            @Schema(description = "The image set still uploading (Story 1.21), only while the state is uploading;"
                    + " upload is then absent") ImageSetView imageSet,
            @Schema(description = "The material's version: a change of where it belongs names it and raises it by one"
                    + " (contract 1.21.0)", format = "int64", example = "0") long version,
            @Schema(description = "The current version's size in bytes, once known (contract 1.21.0)", format = "int64",
                    example = "134217728") Long size,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(description = "Where the material belongs (contract 1.21.0)") BelongingView belonging,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(description = "The ready version that keeps serving while the current version is another one"
                    + " (a replacement or a reprocess on its way); null otherwise (contract 1.23.0)",
                    types = {"object", "null"}, implementation = ServingView.class) ServingView serving,
            @ArraySchema(arraySchema = @Schema(description = "The material's superseded versions, newest first; a"
                    + " version whose removal is recorded is left out; empty when there is none (contract 1.24.0)"),
                    schema = @Schema(implementation = SupersededView.class)) List<SupersededView> superseded) {

        static MaterialView of(Materials.Material material) {
            return new MaterialView(material.id().toString(), material.name(), material.state(), material.kind(),
                    material.upload() == null ? null : UploadView.of(material.upload()),
                    material.pages() == null ? null : PagesView.of(material.pages()),
                    material.failure(), material.actions(),
                    material.imageSet() == null ? null : ImageSetView.of(material.imageSet()), material.version(),
                    material.size(), BelongingView.of(material.belonging()), ServingView.of(material.serving()),
                    material.superseded().stream().map(SupersededView::of).toList());
        }

        @Override
        public String toString() {
            return "MaterialView[id=" + id + ", state=" + state + "]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "MaterialPage", requiredProperties = {"items", "total"})
    record PageView(
            @ArraySchema(schema = @Schema(implementation = MaterialView.class)) List<MaterialView> items,
            @Schema(description = "The cursor of the next page; absent on the last", format = "uuid",
                    example = "0190a0b0-0000-7000-8000-000000000003") String nextCursor,
            @Schema(description = "How many materials the list has in all for the same states and system, whatever the"
                    + " page; it counts a material whose every place is being deleted until that deletion is done",
                    format = "int64", example = "61") long total) {
    }

    @GetMapping(path = MATERIALS, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listMaterials", summary = "The account's materials whose current version is in one of the"
            + " given states (every material without a state), newest first, with the upload's progress on those still"
            + " uploading; with system, only those attached to that system or to one of its campaigns. A material whose"
            + " every place is being deleted is left out, so a page may hold fewer than the limit. A page starts after a"
            + " cursor or at an offset (a numbered page), and names the total of the list. request.invalid (400)"
            + " for an unknown state, a limit outside 1 to 50, a malformed cursor, an offset outside 0 to 100000 or a"
            + " cursor together with an offset above 0; request.not_found (404) for a system"
            + " that is not the account's (unknown, another account's or malformed);" + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "state", in = ParameterIn.QUERY,
                        description = "A state of the current version; repeat it for several; absent: every state"
                                + " (contract 1.21.0)",
                        array = @ArraySchema(minItems = 1, schema = @Schema(type = "string",
                                allowableValues = {"uploading", "waiting", "processing", "needs_review", "ready",
                                    "superseded", "failed", "discarded"}))),
                @Parameter(name = "limit", in = ParameterIn.QUERY, description = "At most this many, 1 to 50",
                        schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "50",
                                defaultValue = "20")),
                @Parameter(name = "cursor", in = ParameterIn.QUERY,
                        description = "The nextCursor of the previous page", schema = @Schema(type = "string",
                                format = "uuid")),
                @Parameter(name = "system", in = ParameterIn.QUERY, description = "Only the materials of this system"
                        + " (Story 3.1): attached to it or to one of its campaigns", schema = @Schema(type = "string",
                                format = "uuid")),
                @Parameter(name = "offset", in = ParameterIn.QUERY, description = "How many of the newest to skip, 0 to"
                        + " 100000, for a numbered page; not together with cursor",
                        schema = @Schema(type = "integer", format = "int32", minimum = "0", maximum = "100000",
                                defaultValue = "0"))})
    ResponseEntity<PageView> list(@Parameter(hidden = true) @RequestParam(value = "state", required = false) List<String> states,
                                  @Parameter(hidden = true) @RequestParam(value = "limit", required = false) String limit,
                                  @Parameter(hidden = true) @RequestParam(value = "cursor", required = false) String cursor,
                                  @Parameter(hidden = true) @RequestParam(value = "system", required = false) String system,
                                  @Parameter(hidden = true) @RequestParam(value = "offset", required = false) String offset) {
        if (states != null && (states.isEmpty() || !Materials.STATES.containsAll(states))) {
            throw invalid();
        }
        if (limit != null && (!LIMIT.matcher(limit).matches() || Integer.parseInt(limit) > Materials.MAX_LIMIT)) {
            throw invalid();
        }
        int size = limit == null ? Materials.DEFAULT_LIMIT : Integer.parseInt(limit);
        UUID after = cursor == null ? null : parse(cursor).orElseThrow(MaterialController::invalid);
        // A page starts after a cursor or at a position, never both.
        if (offset != null && (!OFFSET.matcher(offset).matches() || Integer.parseInt(offset) > Materials.MAX_OFFSET
                || (after != null && Integer.parseInt(offset) > 0))) {
            throw invalid();
        }
        int from = offset == null ? 0 : Integer.parseInt(offset);
        Materials.Page page = system == null ? materials.list(states, after, from, size)
                : parse(system).flatMap(id -> materials.listOfSystem(states, id, after, from, size))
                        .orElseThrow(() -> new ApiException(ApiProblem.forStatus(404)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new PageView(page.items().stream().map(MaterialView::of).toList(),
                        page.nextCursor() == null ? null : page.nextCursor().toString(), page.total()));
    }

    @GetMapping(path = MATERIAL, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getMaterial", summary = "One of the account's materials, with the state of its current"
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

    @Schema(name = "MaterialBelongingChange", description = "Exactly one of campaign and systems")
    record BelongingBody(
            @Schema(description = "One of the account's campaigns (archived or not)", format = "uuid",
                    example = "0190a0b0-0000-7000-8000-000000000002") String campaign,
            @ArraySchema(minItems = 1, maxItems = 100, arraySchema = @Schema(description = "One to 100 of the account's"
                    + " systems, at most 100 ids as sent; a repeated id is stored once"),
                    schema = @Schema(type = "string", format = "uuid")) List<String> systems) {

        @Override
        public String toString() {
            return "BelongingBody[]";
        }
    }

    @Schema(name = "MaterialChange", requiredProperties = {"version", "belonging"})
    record MaterialChangeBody(
            @Schema(description = "The version the change was made on, as the material was read", format = "int64",
                    minimum = "0", example = "0") Long version,
            @Schema(description = "Where the material belongs from now on") BelongingBody belonging) {

        @Override
        public String toString() {
            return "MaterialChangeBody[version=" + version + "]";
        }
    }

    @PatchMapping(path = MATERIAL, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "changeMaterial", summary = "Changes where one of the account's materials belongs, on the"
            + " version it names (contract 1.21.0): one campaign, or one to 100 systems. It applies from the next"
            + " question; nothing is processed again. Answers the material as stored, its version raised by one."
            + " request.stale_version (409) when the material has left that version; material.belonging_invalid (400)"
            + " for both campaign and systems, neither, an empty or over-long list, or a campaign or system that is not"
            + " the account's (unknown, another account's, malformed or being deleted); request.invalid (400) without a"
            + " version or for a malformed body; request.not_found (404) for any other material id, one whose every"
            + " place is being deleted included; nothing changes then;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION_HEADER, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<MaterialView> change(
            @Parameter(description = "The material's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @RequestBody MaterialChangeBody body) {
        UUID materialId = parse(id).orElseThrow(() -> new ApiException(ApiProblem.forStatus(404)));
        if (body == null || body.version() == null || body.version() < 0) {
            throw invalid();
        }
        BelongingBody belonging = body.belonging() == null ? new BelongingBody(null, null) : body.belonging();
        return belongings.change(materialId, body.version(), belonging.campaign(), belonging.systems())
                .map(MaterialView::of)
                .map(view -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view))
                .orElseThrow(() -> new ApiException(ApiProblem.forStatus(404)));
    }

    @Schema(name = "PagePicture", requiredProperties = {"width", "height"})
    record PictureView(
            @Schema(description = "The picture's width in pixels", format = "int32", example = "1545") int width,
            @Schema(description = "The picture's height in pixels", format = "int32", example = "2000") int height) {
    }

    @Schema(name = "PageLine", requiredProperties = {"text", "box"})
    record LineView(
            @Schema(description = "The line's text, as read", example = "Plate | 1,500 gp | 18") String text,
            @ArraySchema(minItems = 4, maxItems = 4, arraySchema = @Schema(description = "The line's box, [left, top,"
                    + " right, bottom] as fractions of the picture from its top-left corner"),
                    schema = @Schema(type = "number", format = "double", minimum = "0", maximum = "1"))
            List<Double> box) {

        static LineView of(Materials.Line line) {
            return new LineView(line.text(), line.box());
        }

        @Override
        public String toString() {
            return "LineView[box=" + box + "]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "PagePassage", requiredProperties = "id", description = "A passage on the page: its boxes on a"
            + " picture page, or its range in a text page's text")
    record PassageView(
            @Schema(description = "The passage's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000009")
            String id,
            @ArraySchema(arraySchema = @Schema(description = "The passage's boxes, each [left, top, right, bottom] as"
                    + " fractions of the picture from its top-left corner"),
                    schema = @Schema(type = "array", description = "[left, top, right, bottom]"))
            List<List<Double>> boxes,
            @ArraySchema(minItems = 2, maxItems = 2, arraySchema = @Schema(description = "The passage's range in the"
                    + " page's text, [start, end) in code points"), schema = @Schema(type = "integer", format = "int32"))
            List<Integer> range) {

        static PassageView of(Materials.PagePassage passage) {
            return new PassageView(passage.id().toString(), passage.boxes(), passage.range());
        }
    }

    @Schema(name = "MaterialPageView", requiredProperties = {"page", "pageTotal", "picture", "lines", "text",
        "passages", "outcome"})
    record PageContentView(
            @Schema(description = "The page's number", format = "int32", example = "13") int page,
            @Schema(description = "The version's page total", format = "int32", example = "364") int pageTotal,
            @Schema(description = "The page's picture size (a PDF page); null for a text page, which has no picture",
                    types = {"object", "null"}, implementation = PictureView.class) PictureView picture,
            @ArraySchema(arraySchema = @Schema(description = "The picture's text lines in reading order; empty for a"
                    + " text page"), schema = @Schema(implementation = LineView.class)) List<LineView> lines,
            @Schema(description = "A text page's text; null for a picture page", types = {"string", "null"})
            String text,
            @ArraySchema(arraySchema = @Schema(description = "The page's passages, in reading order"),
                    schema = @Schema(implementation = PassageView.class)) List<PassageView> passages,
            @Schema(description = "How the page was read: read_directly, read_by_ocr or unreadable; null when it has"
                    + " no outcome. An unreadable page whose reading crashed has no picture, lines, text or passages",
                    types = {"string", "null"}, example = "read_directly") String outcome) {

        static PageContentView of(Materials.VersionPage page) {
            return new PageContentView(page.page(), page.pageTotal(), page.picture() == null ? null
                    : new PictureView(page.picture().width(), page.picture().height()),
                    page.lines().stream().map(LineView::of).toList(), page.text(),
                    page.passages().stream().map(PassageView::of).toList(), page.outcome());
        }

        @Override
        public String toString() {
            return "PageContentView[page=" + page + ", pageTotal=" + pageTotal + "]";
        }
    }

    private static final String PAGE_SUMMARY = " page.ground_unknown (400) without a ground or for another one;"
            + " request.not_found (404) for another owner's material or an unknown one, a version the ground does not"
            + " serve, a page outside 1 to the page total or a page with no view (unless the page is unreadable);"
            + SESSION_SUMMARY;

    @GetMapping(path = PAGE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getMaterialPage", summary = "One page of one of the account's materials on a ground:"
            + " review serves its current version while it needs review or is ready. The page's picture size and"
            + " text lines (a PDF page) or its text (a text page), and its passages' places." + PAGE_SUMMARY,
            parameters = {
                @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "ground", in = ParameterIn.QUERY, required = true,
                        description = "Why the page is read: review (the owner reviewing a version)",
                        schema = @Schema(type = "string", allowableValues = "review"))})
    ResponseEntity<PageContentView> page(
            @Parameter(description = "The material's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(description = "The page's number, from 1", schema = @Schema(type = "integer", format = "int32",
                    minimum = "1")) @PathVariable("page") String page,
            @Parameter(hidden = true) @RequestParam(value = "ground", required = false) String ground) {
        Materials.Ground served = Materials.Ground.of(ground).orElseThrow(PageProblems::groundUnknown);
        return parse(id).flatMap(material -> pageNumber(page).flatMap(number -> materials.page(material, number,
                        served))).map(PageContentView::of)
                .map(view -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view))
                .orElseThrow(() -> new ApiException(ApiProblem.forStatus(404)));
    }

    // No `produces`: a refusal is problem JSON, which an image's Accept would otherwise turn into a 406.
    @GetMapping(path = PICTURE)
    @Operation(operationId = "getMaterialPagePicture", summary = "The picture of one page of one of the account's"
            + " materials on a ground, as WebP, streamed. request.not_found (404) also for a page with no picture (a"
            + " text page, or an unreadable page with no view)." + PAGE_SUMMARY,
            parameters = {
                @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "ground", in = ParameterIn.QUERY, required = true,
                        description = "Why the page is read: review (the owner reviewing a version)",
                        schema = @Schema(type = "string", allowableValues = "review"))},
            responses = @ApiResponse(responseCode = "200", description = "The page's picture",
                    content = @Content(mediaType = WEBP, schema = @Schema(type = "string", format = "binary"))))
    void picture(@Parameter(description = "The material's id", schema = @Schema(type = "string", format = "uuid"))
                 @PathVariable("id") String id,
                 @Parameter(description = "The page's number, from 1", schema = @Schema(type = "integer",
                         format = "int32", minimum = "1")) @PathVariable("page") String page,
                 @Parameter(hidden = true) @RequestParam(value = "ground", required = false) String ground,
                 @Parameter(hidden = true) HttpServletResponse response) throws IOException {
        Materials.Ground served = Materials.Ground.of(ground).orElseThrow(PageProblems::groundUnknown);
        Materials.Picture picture = parse(id).flatMap(material -> pageNumber(page)
                        .flatMap(number -> materials.picture(material, number, served)))
                .orElseThrow(() -> new ApiException(ApiProblem.forStatus(404)));
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(WEBP);
        response.setContentLengthLong(picture.size());
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        response.setHeader("X-Content-Type-Options", "nosniff");
        try (OutputStream out = response.getOutputStream()) {
            picture.writeTo(out);
        }
    }

    @Schema(name = "ReportSection", requiredProperties = {"level", "title", "page"})
    record SectionView(
            @Schema(description = "The heading's level, from 0 (the outermost)", format = "int32", example = "1")
            int level,
            @Schema(description = "The heading's title, as read", example = "Equipment") String title,
            @Schema(description = "The page the section starts on", format = "int32", example = "92") int page) {

        @Override
        public String toString() {
            return "SectionView[level=" + level + ", page=" + page + "]";
        }
    }

    @Schema(name = "ReportPageRun", requiredProperties = {"firstPage", "lastPage"})
    record RunView(
            @Schema(description = "The run's first page", format = "int32", example = "40") int firstPage,
            @Schema(description = "The run's last page (inclusive)", format = "int32", example = "41") int lastPage) {
    }

    @Schema(name = "ReportWarning", requiredProperties = {"code", "count"})
    record WarningView(
            @Schema(description = "What the backend found: report.pages_unreadable (pages that could not be read),"
                    + " report.pages_unopened (of those, pages whose reading crashed, with no view) or"
                    + " report.outline_empty (no section was found; count 0)", example = "report.pages_unreadable",
                    allowableValues = {"report.pages_unreadable", "report.pages_unopened", "report.outline_empty"})
            String code,
            @Schema(description = "How many", format = "int32", example = "12") int count) {
    }

    @Schema(name = "MaterialReport", requiredProperties = {"outline", "unreadable", "warnings"})
    record ReportView(
            @ArraySchema(arraySchema = @Schema(description = "The sections in reading order"),
                    schema = @Schema(implementation = SectionView.class)) List<SectionView> outline,
            @ArraySchema(arraySchema = @Schema(description = "The unreadable pages, as runs of consecutive pages in"
                    + " page order"), schema = @Schema(implementation = RunView.class)) List<RunView> unreadable,
            @ArraySchema(arraySchema = @Schema(description = "Facts about the version, each with a count"),
                    schema = @Schema(implementation = WarningView.class)) List<WarningView> warnings) {

        static ReportView of(Materials.Report report) {
            return new ReportView(report.outline().stream()
                    .map(section -> new SectionView(section.level(), section.title(), section.page())).toList(),
                    report.unreadable().stream().map(run -> new RunView(run.firstPage(), run.lastPage())).toList(),
                    report.warnings().stream().map(warning -> new WarningView(warning.code(), warning.count()))
                            .toList());
        }

        @Override
        public String toString() {
            return "ReportView[sections=" + outline.size() + ", unreadable=" + unreadable.size() + "]";
        }
    }

    @GetMapping(path = REPORT, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getMaterialReport", summary = "The quality report of one of the account's materials:"
            + " its current version's outline, unreadable pages and warnings, while that version needs review or is"
            + " ready (the counts are the material's pages). request.not_found (404) for another owner's material,"
            + " an unknown or malformed id, or a version in any other state;" + SESSION_SUMMARY,
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<ReportView> report(
            @Parameter(description = "The material's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        return parse(id).flatMap(materials::report).map(ReportView::of)
                .map(view -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view))
                .orElseThrow(() -> new ApiException(ApiProblem.forStatus(404)));
    }

    private static final String ACTION_SUMMARY = " Takes no body and answers the material."
            + " material.action_unavailable (409) when the current version's state does not allow it now (see the"
            + " material's actions); request.not_found (404) for another owner's material or an unknown or malformed"
            + " id, and nothing changes;" + SESSION_SUMMARY;
    private static final String SESSION_HEADER = "X-Session-Token";

    @PostMapping(path = ACCEPT, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "acceptMaterial", summary = "Accepts the current version of one of the account's materials:"
            + " needs_review becomes ready, and a ready version of it before becomes superseded." + ACTION_SUMMARY,
            parameters = @Parameter(name = SESSION_HEADER, in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<MaterialView> accept(
            @Parameter(description = "The material's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        return act(id, Materials.ACCEPT);
    }

    @PostMapping(path = REPROCESS, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "reprocessMaterial", summary = "Reads again the unreadable pages of the current version of"
            + " one of the account's materials, which needs review: it becomes processing, and needs review again"
            + " once they are read. ingestion.too_many_steps (409) when the new steps would pass the version's step"
            + " cap. From contract 1.23.0, on a ready material: a new version, waiting, read from a copy of the ready"
            + " version's cleaned original, which keeps serving unchanged until the new one is accepted;"
            + " material.version_in_progress (409) while another version is on its way; request.not_found (404) also"
            + " for a material whose every place is being deleted." + ACTION_SUMMARY,
            parameters = @Parameter(name = SESSION_HEADER, in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<MaterialView> reprocess(
            @Parameter(description = "The material's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        return act(id, Materials.REPROCESS);
    }

    @PostMapping(path = RETRY, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "retryMaterial", summary = "Starts again the failed current version of one of the account's"
            + " materials, from its cleaned original or its uploaded file: it becomes processing."
            + " ingestion.too_many_steps (409) when a new step would pass the version's step cap." + ACTION_SUMMARY,
            parameters = @Parameter(name = SESSION_HEADER, in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<MaterialView> retry(
            @Parameter(description = "The material's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        return act(id, Materials.RETRY);
    }

    @PostMapping(path = DISCARD, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "discardMaterial", summary = "Discards the current version of one of the account's"
            + " materials, which needs review or failed: it becomes discarded, and its pages, passages, outline and"
            + " files are removed (a removal that resumes after a restart); a ready version of it before stays the"
            + " material's (contract 1.23.0)." + ACTION_SUMMARY,
            parameters = @Parameter(name = SESSION_HEADER, in = ParameterIn.HEADER,
                    description = "The session token", schema = @Schema(type = "string")))
    ResponseEntity<MaterialView> discard(
            @Parameter(description = "The material's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        return act(id, Materials.DISCARD);
    }

    @Schema(name = "MaterialDeletion", requiredProperties = {"versions", "pages"}, description = "What goes with a"
            + " material if it is deleted (contract 1.24.0)")
    record DeletionView(
            @Schema(description = "Its versions that are not discarded", format = "int64", example = "2") long versions,
            @Schema(description = "The sum of those versions' page totals (a total not known counts 0)",
                    format = "int64", example = "640") long pages) {

        static DeletionView of(MaterialDeletions.Count count) {
            return new DeletionView(count.versions(), count.pages());
        }
    }

    @GetMapping(path = DELETION, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getMaterialDeletion", summary = "What goes with one of the account's materials if it is"
            + " deleted (contract 1.24.0): how many of its versions are not discarded, and their pages."
            + " request.not_found (404) for another owner's material, an unknown or malformed id, one whose every"
            + " place is being deleted, or one whose deletion is recorded already;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION_HEADER, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")))
    ResponseEntity<DeletionView> deletion(
            @Parameter(description = "The material's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        return parse(id).flatMap(deletions::count).map(DeletionView::of)
                .map(view -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view))
                .orElseThrow(() -> new ApiException(ApiProblem.forStatus(404)));
    }

    @DeleteMapping(path = MATERIAL)
    @Operation(operationId = "deleteMaterial", summary = "Deletes one of the account's materials with every version"
            + " (contract 1.24.0; 202, no body): from now on it is gone from every list and count, and its passages from"
            + " every scope; its pages, passages, files, index points and rows are removed in the background. A"
            + " reference an answer stored reads as a removed source. A material already being deleted is answered the"
            + " same, and nothing changes. Cannot be undone. request.not_found (404) for another owner's material, an"
            + " unknown or malformed id, or one whose every place is being deleted;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION_HEADER, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "202", description = "The deletion is recorded"))
    ResponseEntity<Void> delete(
            @Parameter(description = "The material's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id) {
        if (!parse(id).map(deletions::delete).orElse(false)) {
            throw new ApiException(ApiProblem.forStatus(404));
        }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @DeleteMapping(path = VERSION)
    @Operation(operationId = "removeMaterialVersion", summary = "Removes a superseded version of one of the account's"
            + " materials (contract 1.24.0; 202, no body): from now on it is gone from the material's superseded"
            + " versions, and its pages, passages, files, index points and row are removed in the background; the"
            + " material and its other versions stay. A reference an answer stored to it reads as a removed source and"
            + " its page is not found. A version whose removal is recorded already is answered the same."
            + " material.version_not_removable (409) for a version in any other state (ready, in progress or"
            + " discarded); request.not_found (404) for another owner's material or version, a version of another"
            + " material, an unknown or malformed id, or a material whose every place is being deleted or whose deletion"
            + " is recorded already; nothing changes then;" + SESSION_SUMMARY,
            parameters = @Parameter(name = SESSION_HEADER, in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "202", description = "The removal is recorded"))
    ResponseEntity<Void> removeVersion(
            @Parameter(description = "The material's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(description = "The version's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("versionId") String versionId) {
        Optional<UUID> material = parse(id);
        Optional<UUID> version = parse(versionId);
        if (material.isEmpty() || version.isEmpty() || !deletions.removeVersion(material.get(), version.get())) {
            throw new ApiException(ApiProblem.forStatus(404));
        }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    private ResponseEntity<MaterialView> act(String id, String action) {
        return parse(id).flatMap(material -> actions.act(material, action)).map(MaterialView::of)
                .map(view -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view))
                .orElseThrow(() -> new ApiException(ApiProblem.forStatus(404)));
    }

    private static Optional<Integer> pageNumber(String page) {
        return page != null && PAGE_NUMBER.matcher(page).matches() ? Optional.of(Integer.parseInt(page))
                : Optional.empty();
    }

    /** A malformed id is no different from an unknown one. */
    private static Optional<UUID> parse(String id) {
        return id != null && UUID_TEXT.matcher(id).matches() ? Optional.of(UUID.fromString(id)) : Optional.empty();
    }

    private static ApiException invalid() {
        return new ApiException(ApiProblem.forStatus(400));
    }
}
