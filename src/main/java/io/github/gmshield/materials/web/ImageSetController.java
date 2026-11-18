package io.github.gmshield.materials.web;

import io.github.gmshield.materials.service.ImageSets;
import io.github.gmshield.materials.service.UploadProblems;
import io.github.gmshield.materials.service.Uploads;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Photos as the pages of one material (Story 1.21, FR-13): an ordered set of images. The GM's "Enviar" creates the set
 * with its order already confirmed; each image then goes up through the tus routes ({@link UploadController}) at the
 * address its upload id gives. A failed image is replaced (a new upload at its place) or left out (the places after
 * it move up). The owner is the session's account, set by the request edge ({@code accounts.web}). Another owner's
 * set, an unknown one, a malformed id and a set that no longer uploads are the same {@code request.not_found} (404).
 * Every answer is {@code no-store}.
 */
@RestController
@Tag(name = "image-sets")
class ImageSetController {

    static final String IMAGE_SETS = "/api/v1/image-sets";
    static final String IMAGE = "/api/v1/image-sets/{id}/images/{position}";
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Pattern POSITION = Pattern.compile("[1-9][0-9]{0,2}");
    private static final String SESSION_SUMMARY = " session.required (401) without a session.";
    private static final String SET_TOO_LARGE = " upload.set_too_large (413, params.maxImages and params.maxBytes)"
            + " past 500 images or 2 GiB in all;";

    private final ImageSets sets;

    ImageSetController(ImageSets sets) {
        this.sets = sets;
    }

    @Schema(name = "ImageSetImage", requiredProperties = {"name", "length"})
    record ImageRequest(
            @Schema(description = "The image's file name", example = "foto-1.jpg") String name,
            @Schema(description = "The image's size in bytes, 1 to 2147483648", format = "int64", example = "3145728")
            Long length) {

        ImageSets.Image image() {
            if (length == null) {
                throw UploadProblems.invalid();
            }
            return new ImageSets.Image(name, length);
        }

        @Override
        public String toString() {
            return "ImageRequest[length=" + length + "]";
        }
    }

    @Schema(name = "ImageSetRequest", requiredProperties = {"name", "images"})
    record CreateRequest(
            @Schema(description = "The material's name", example = "Ficha do personagem") String name,
            @Schema(description = "What the material is", allowableValues = {"rulebook", "supplement", "homebrew",
                "campaign_notes", "other"}) String kind,
            @Schema(description = "Its language, or detect", allowableValues = {"pt-BR", "en", "detect"}) String language,
            @Schema(description = "One of the account's campaigns it belongs to (never with system)", format = "uuid")
            String campaign,
            @Schema(description = "One of the account's systems it belongs to (never with campaign)", format = "uuid")
            String system,
            @ArraySchema(arraySchema = @Schema(description = "The images, in the order of their pages: 1 to 500"),
                    schema = @Schema(implementation = ImageRequest.class)) List<ImageRequest> images) {

        @Override
        public String toString() {
            return "CreateRequest[images=" + (images == null ? 0 : images.size()) + "]";
        }
    }

    @Schema(name = "ImageSetMember", requiredProperties = {"position", "uploadId"})
    record MemberView(
            @Schema(description = "The image's place, from 1", format = "int32", example = "1") int position,
            @Schema(description = "Its upload: send the file through tus at /api/v1/uploads/{uploadId}", format = "uuid",
                    example = "0190a0b0-0000-7000-8000-000000000006") String uploadId) {
    }

    @Schema(name = "ImageSet", requiredProperties = {"id", "materialId", "expiresAt", "images"})
    record CreatedView(
            @Schema(description = "The set's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000004")
            String id,
            @Schema(description = "The material's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000003")
            String materialId,
            @Schema(description = "When the images' uploads expire", format = "date-time",
                    example = "2026-11-17T06:00:00Z") String expiresAt,
            @ArraySchema(arraySchema = @Schema(description = "The images, in their order"),
                    schema = @Schema(implementation = MemberView.class)) List<MemberView> images) {
    }

    @Schema(name = "ImageSetReplacement", requiredProperties = {"position", "uploadId", "expiresAt"})
    record ReplacedView(
            @Schema(description = "The image's place", format = "int32", example = "2") int position,
            @Schema(description = "The new upload: send the file through tus at /api/v1/uploads/{uploadId}",
                    format = "uuid", example = "0190a0b0-0000-7000-8000-000000000007") String uploadId,
            @Schema(description = "When the new upload expires", format = "date-time",
                    example = "2026-11-17T06:00:00Z") String expiresAt) {
    }

    @PostMapping(path = IMAGE_SETS, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createImageSet", summary = "An ordered set of images that become the pages of one"
            + " material, its order confirmed: the material, its version in uploading and one tus upload per image,"
            + " each expiring after 6 hours. The first ingestion step starts by itself once every image is complete."
            + SET_TOO_LARGE + " upload.metadata_invalid (400) for a name (the set's or an image's) or a kind, language,"
            + " campaign or system that is not valid (it never says which); request.invalid (400) for a body without"
            + " images or an image without a size of at least 1 byte; request.unsupported_media_type (415) for a body"
            + " that is not JSON;" + SESSION_SUMMARY,
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "201", description = "Created",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CreatedView.class))))
    ResponseEntity<CreatedView> create(@RequestBody CreateRequest body) {
        if (body == null || body.images() == null) {
            throw UploadProblems.invalid();
        }
        List<ImageSets.Image> images = body.images().stream().map(image -> {
            if (image == null) {
                throw UploadProblems.invalid();
            }
            return image.image();
        }).toList();
        ImageSets.Created created = sets.create(body.name(), new Uploads.Details(body.kind(), body.language(),
                body.campaign(), body.system()), images);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(new CreatedView(
                created.setId().toString(), created.materialId().toString(), created.expiresAt().toString(),
                created.images().stream().map(member -> new MemberView(member.position(),
                        member.uploadId().toString())).toList()));
    }

    @PostMapping(path = IMAGE, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "replaceImageSetImage", summary = "Replaces the failed image at a place of a set still"
            + " uploading: a new tus upload at that place, checked again, expiring after 6 hours."
            + " upload.image_not_failed (409) when the image there has not failed;" + SET_TOO_LARGE
            + " upload.metadata_invalid (400) for a name that is not valid; request.invalid (400) for a size under"
            + " 1 byte; request.not_found (404) for another account's set, an unknown one, a set no longer uploading or"
            + " a place it does not have;" + SESSION_SUMMARY,
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "201", description = "Created",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ReplacedView.class))))
    ResponseEntity<ReplacedView> replace(
            @Parameter(description = "The set's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(description = "The image's place", schema = @Schema(type = "integer", format = "int32",
                    minimum = "1", maximum = "500")) @PathVariable("position") String position,
            @RequestBody ImageRequest body) {
        UUID set = parse(id);
        int place = position(position);
        if (body == null) {
            throw UploadProblems.invalid();
        }
        ImageSets.Replaced replaced = sets.replace(set, place, body.image());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(new ReplacedView(
                replaced.position(), replaced.uploadId().toString(), replaced.expiresAt().toString()));
    }

    @DeleteMapping(path = IMAGE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "dropImageSetImage", summary = "Leaves out the failed image at a place of a set still"
            + " uploading: the images after it move up by one place, and the set starts when every image left is"
            + " complete; leaving out the last one discards the version. upload.image_not_failed (409) when the image"
            + " there has not failed; request.not_found (404) for another account's set, an unknown one, a set no"
            + " longer uploading or a place it does not have;" + SESSION_SUMMARY,
            parameters = @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER, description = "The session token",
                    schema = @Schema(type = "string")),
            responses = @ApiResponse(responseCode = "200", description = "The set as it is now",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UploadController.CompletionView.class))))
    ResponseEntity<UploadController.CompletionView> drop(
            @Parameter(description = "The set's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @Parameter(description = "The image's place", schema = @Schema(type = "integer", format = "int32",
                    minimum = "1", maximum = "500")) @PathVariable("position") String position) {
        Uploads.Completed dropped = sets.drop(parse(id), position(position));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new UploadController.CompletionView(
                dropped.materialId().toString(), dropped.versionId().toString(), dropped.state()));
    }

    /** A malformed id is no different from an unknown one. */
    private static UUID parse(String id) {
        return Optional.ofNullable(id).filter(text -> UUID_TEXT.matcher(text).matches()).map(UUID::fromString)
                .orElseThrow(UploadProblems::notFound);
    }

    /** A place is 1 to 500; anything else is a place nowhere. */
    private static int position(String position) {
        if (position == null || !POSITION.matcher(position).matches() || Integer.parseInt(position) > Uploads.MAX_IMAGES) {
            throw UploadProblems.notFound();
        }
        return Integer.parseInt(position);
    }
}
