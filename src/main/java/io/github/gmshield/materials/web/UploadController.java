package io.github.gmshield.materials.web;

import io.github.gmshield.materials.service.UploadProblems;
import io.github.gmshield.materials.service.Uploads;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The resumable upload (Story 1.13, AD-16): tus 1.0.0 creation, offset and part requests, written by hand rather than
 * through a tus library, and a final call that checks the whole file. The owner is the session's account, set by the
 * request edge ({@code accounts.web}); {@link UploadProtocolFilter} has already checked {@code Tus-Resumable} and set
 * it on the answer. Another owner's id, an unknown id and a malformed id are the same {@code request.not_found} (404).
 * Every answer is {@code no-store}.
 */
@RestController
@Tag(name = "uploads")
class UploadController {

    static final String UPLOADS = "/api/v1/uploads";
    static final String UPLOAD = "/api/v1/uploads/{id}";
    static final String COMPLETION = "/api/v1/uploads/{id}/completion";
    static final String OFFSET_OCTET_STREAM = "application/offset+octet-stream";
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Pattern DIGITS = Pattern.compile("0|[1-9][0-9]{0,18}");
    private static final Pattern HEX_SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern METADATA_KEY = Pattern.compile("[A-Za-z0-9._~!#$%&'*+^`|-]+");
    /** HTTP-date (RFC 9110), always in GMT with a two-digit day. */
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);
    private static final String SESSION_SUMMARY = " session.required (401) without a session.";

    private final Uploads uploads;

    UploadController(Uploads uploads) {
        this.uploads = uploads;
    }

    @Schema(name = "UploadCompletionRequest", requiredProperties = "sha256")
    record CompletionRequest(
            @Schema(description = "The whole file's SHA-256, in hexadecimal", pattern = "^[0-9a-fA-F]{64}$",
                    example = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") String sha256) {
    }

    @Schema(name = "UploadCompletion", requiredProperties = {"materialId", "versionId", "state"})
    record CompletionView(
            @Schema(description = "The material's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000003")
            String materialId,
            @Schema(description = "The version's id", format = "uuid", example = "0190a0b0-0000-7000-8000-000000000004")
            String versionId,
            @Schema(description = "The version's state", example = "waiting") String state) {
    }

    @PostMapping(path = UPLOADS)
    @Operation(operationId = "createUpload", summary = "tus 1.0.0 creation: a material, its version in uploading and an"
            + " upload that expires after 6 hours. upload.too_large (413) outside 1 byte to 2 GiB;"
            + " upload.metadata_invalid (400) without a valid filename; upload.tus_version (412) without"
            + " Tus-Resumable: 1.0.0;" + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "Tus-Resumable", in = ParameterIn.HEADER, required = true,
                        description = "The tus version", schema = @Schema(type = "string", allowableValues = "1.0.0")),
                @Parameter(name = "Upload-Length", in = ParameterIn.HEADER, required = true,
                        description = "The file's size in bytes, 1 to 2147483648",
                        schema = @Schema(type = "integer", format = "int64", minimum = "1", maximum = "2147483648")),
                @Parameter(name = "Upload-Metadata", in = ParameterIn.HEADER, required = true,
                        description = "tus metadata: comma-separated keys, each with its value in Base64; filename"
                                + " is required", schema = @Schema(type = "string", example = "filename cnVsZXMucGRm"))},
            responses = @ApiResponse(responseCode = "201", description = "Created",
                    headers = {
                        @Header(name = "Location", required = true, description = "The upload's path, relative",
                                schema = @Schema(type = "string", example = "/api/v1/uploads/0190a0b0-0000-7000-8000-000000000005")),
                        @Header(name = "Tus-Resumable", required = true, schema = @Schema(type = "string", example = "1.0.0")),
                        @Header(name = "Upload-Expires", required = true, description = "When the upload expires (HTTP-date)",
                                schema = @Schema(type = "string", example = "Tue, 17 Nov 2026 06:00:00 GMT"))}))
    ResponseEntity<Void> create(@Parameter(hidden = true) @RequestHeader(value = "Upload-Length", required = false) String length,
                                @Parameter(hidden = true) @RequestHeader(value = "Upload-Metadata", required = false) String metadata) {
        long size = number(length);
        String name = filename(metadata);
        Uploads.Created created = uploads.create(size, name);
        return ResponseEntity.status(201)
                .header(HttpHeaders.LOCATION, UPLOADS + "/" + created.uploadId())
                .header("Upload-Expires", HTTP_DATE.format(created.expiresAt()))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @RequestMapping(path = UPLOAD, method = RequestMethod.HEAD)
    @Operation(operationId = "getUploadOffset", summary = "tus 1.0.0 offset: how many bytes are verified and stored."
            + " request.not_found (404) for any other id; upload.expired (410) after 6 hours;" + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "Tus-Resumable", in = ParameterIn.HEADER, required = true,
                        description = "The tus version", schema = @Schema(type = "string", allowableValues = "1.0.0"))},
            responses = @ApiResponse(responseCode = "200", description = "OK",
                    headers = {
                        @Header(name = "Upload-Offset", required = true, description = "Bytes verified and stored",
                                schema = @Schema(type = "integer", format = "int64", example = "67108864")),
                        @Header(name = "Upload-Length", required = true, description = "The file's size in bytes",
                                schema = @Schema(type = "integer", format = "int64", example = "2147483648")),
                        @Header(name = "Upload-Expires", required = true, description = "When the upload expires (HTTP-date)",
                                schema = @Schema(type = "string", example = "Tue, 17 Nov 2026 06:00:00 GMT")),
                        @Header(name = "Tus-Resumable", required = true, schema = @Schema(type = "string", example = "1.0.0")),
                        @Header(name = "Cache-Control", required = true, schema = @Schema(type = "string", example = "no-store"))}))
    ResponseEntity<Void> head(@Parameter(description = "The upload's id", schema = @Schema(type = "string", format = "uuid"))
                              @PathVariable("id") String id) {
        Uploads.Progress progress = uploads.head(parse(id));
        return ResponseEntity.ok()
                .header("Upload-Offset", Long.toString(progress.offset()))
                .header("Upload-Length", Long.toString(progress.length()))
                .header("Upload-Expires", HTTP_DATE.format(progress.expiresAt()))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @PatchMapping(path = UPLOAD, consumes = OFFSET_OCTET_STREAM)
    @Operation(operationId = "patchUpload", summary = "tus 1.0.0 part, at most 90 MiB, with its SHA-256. The first part"
            + " decides the format from content: upload.format_refused (415, params.accepted) leaves nothing behind."
            + " upload.part_checksum_mismatch (460) and a cut body leave the offset unchanged; upload.offset_conflict"
            + " (409) when Upload-Offset is not the stored offset; upload.busy (423) while another part is written;"
            + " upload.too_large (413) over 90 MiB or past Upload-Length; upload.expired (410) after 6 hours;"
            + " request.not_found (404) for any other id;" + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "Tus-Resumable", in = ParameterIn.HEADER, required = true,
                        description = "The tus version", schema = @Schema(type = "string", allowableValues = "1.0.0")),
                @Parameter(name = "Upload-Offset", in = ParameterIn.HEADER, required = true,
                        description = "Where this part starts: the stored offset",
                        schema = @Schema(type = "integer", format = "int64", minimum = "0")),
                @Parameter(name = "Upload-Checksum", in = ParameterIn.HEADER, required = true,
                        description = "sha256 and the part's SHA-256 in Base64",
                        schema = @Schema(type = "string", example = "sha256 47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="))},
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = OFFSET_OCTET_STREAM, schema = @Schema(type = "string", format = "binary"))),
            responses = @ApiResponse(responseCode = "204", description = "The part is verified and stored",
                    headers = {
                        @Header(name = "Upload-Offset", required = true, description = "The new offset",
                                schema = @Schema(type = "integer", format = "int64", example = "67108864")),
                        @Header(name = "Upload-Expires", required = true, description = "When the upload expires (HTTP-date)",
                                schema = @Schema(type = "string", example = "Tue, 17 Nov 2026 06:00:00 GMT")),
                        @Header(name = "Tus-Resumable", required = true, schema = @Schema(type = "string", example = "1.0.0"))}))
    ResponseEntity<Void> patch(@Parameter(description = "The upload's id", schema = @Schema(type = "string", format = "uuid"))
                               @PathVariable("id") String id,
                               @Parameter(hidden = true) @RequestHeader(value = "Upload-Offset", required = false) String offset,
                               @Parameter(hidden = true) @RequestHeader(value = "Upload-Checksum", required = false) String checksum,
                               @Parameter(hidden = true) HttpServletRequest request) throws IOException {
        UUID upload = parse(id);
        long start = number(offset);
        byte[] expected = partChecksum(checksum);
        Uploads.Progress progress = uploads.patch(upload, start, request.getContentLengthLong(), expected,
                request.getInputStream());
        return ResponseEntity.noContent()
                .header("Upload-Offset", Long.toString(progress.offset()))
                .header("Upload-Expires", HTTP_DATE.format(progress.expiresAt()))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @PostMapping(path = COMPLETION, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "completeUpload", summary = "Checks the whole file's SHA-256 once every byte is stored: the"
            + " version becomes waiting. upload.incomplete (409) before the last byte; upload.file_checksum_mismatch"
            + " (422) discards the version and its pieces; upload.busy (423) while a part is written; upload.expired"
            + " (410) after 6 hours; request.not_found (404) for any other id;" + SESSION_SUMMARY,
            parameters = {
                @Parameter(name = "X-Session-Token", in = ParameterIn.HEADER, description = "The session token",
                        schema = @Schema(type = "string")),
                @Parameter(name = "Tus-Resumable", in = ParameterIn.HEADER, required = true,
                        description = "The tus version", schema = @Schema(type = "string", allowableValues = "1.0.0"))},
            responses = @ApiResponse(responseCode = "201", description = "Verified",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CompletionView.class))))
    ResponseEntity<CompletionView> complete(
            @Parameter(description = "The upload's id", schema = @Schema(type = "string", format = "uuid"))
            @PathVariable("id") String id,
            @RequestBody CompletionRequest body) {
        UUID upload = parse(id);
        if (body == null || body.sha256() == null || !HEX_SHA256.matcher(body.sha256()).matches()) {
            throw UploadProblems.invalid();
        }
        Uploads.Completed completed = uploads.complete(upload, HexFormat.of().parseHex(body.sha256()));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(new CompletionView(completed.materialId().toString(), completed.versionId().toString(),
                        completed.state()));
    }

    /** A malformed id is no different from an unknown one. */
    private static UUID parse(String id) {
        return Optional.ofNullable(id).filter(text -> UUID_TEXT.matcher(text).matches()).map(UUID::fromString)
                .orElseThrow(UploadProblems::notFound);
    }

    /** A non-negative decimal header value. */
    private static long number(String value) {
        if (value == null || !DIGITS.matcher(value.strip()).matches()) {
            throw UploadProblems.invalid();
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException tooBig) {
            throw UploadProblems.invalid();
        }
    }

    /** {@code sha256 <Base64 of 32 bytes>}: tus's checksum extension, with SHA-256 only. */
    private static byte[] partChecksum(String value) {
        if (value == null) {
            throw UploadProblems.invalid();
        }
        String[] parts = value.strip().split(" ", -1);
        if (parts.length != 2 || !parts[0].equals("sha256")) {
            throw UploadProblems.invalid();
        }
        try {
            byte[] digest = Base64.getDecoder().decode(parts[1]);
            if (digest.length != io.github.gmshield.vault.service.Sha256.BYTES) {
                throw UploadProblems.invalid();
            }
            return digest;
        } catch (IllegalArgumentException notBase64) {
            throw UploadProblems.invalid();
        }
    }

    /**
     * The {@code filename} of tus's {@code Upload-Metadata}: comma-separated pairs of a key and its value in Base64
     * (a key may stand alone). Keys are unique. Other keys are ignored here (Story 1.14 adds them).
     */
    static String filename(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            throw UploadProblems.metadataInvalid();
        }
        Set<String> keys = new HashSet<>();
        String filename = null;
        for (String pair : metadata.split(",", -1)) {
            String[] parts = pair.strip().split(" ", -1);
            if (parts.length > 2 || !METADATA_KEY.matcher(parts[0]).matches() || !keys.add(parts[0])) {
                throw UploadProblems.metadataInvalid();
            }
            if (parts[0].equals("filename")) {
                if (parts.length != 2) {
                    throw UploadProblems.metadataInvalid();
                }
                filename = utf8(parts[1]);
            }
        }
        if (filename == null) {
            throw UploadProblems.metadataInvalid();
        }
        return filename;
    }

    private static String utf8(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            throw UploadProblems.metadataInvalid();
        }
    }

    /** For tests: the HTTP-date of an instant, as the answers write it. */
    static String httpDate(Instant instant) {
        return HTTP_DATE.format(instant);
    }
}
