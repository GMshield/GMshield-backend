package io.github.gmshield.materials.service;

import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import java.util.Map;

/** The answers of the upload routes (Story 1.13). Params carry limits and format codes only, never content. */
public final class UploadProblems {

    public static final String CATEGORY = "upload";
    /** tus "Checksum Mismatch": not a standard HTTP status, so it is always sent by its number. */
    public static final int CHECKSUM_MISMATCH_STATUS = 460;
    public static final ApiProblem TUS_VERSION =
            ApiProblem.of(412, CATEGORY, "tus_version", "Unsupported tus version");
    static final ApiProblem TOO_LARGE = ApiProblem.of(413, CATEGORY, "too_large", "Upload too large");
    static final ApiProblem FORMAT_REFUSED = ApiProblem.of(415, CATEGORY, "format_refused", "Format not accepted")
            .withParams(Map.of("accepted", Format.CODES));
    static final ApiProblem BUSY = ApiProblem.of(423, CATEGORY, "busy", "Upload busy");
    static final ApiProblem PART_CHECKSUM_MISMATCH =
            ApiProblem.of(CHECKSUM_MISMATCH_STATUS, CATEGORY, "part_checksum_mismatch", "Part checksum mismatch");
    static final ApiProblem OFFSET_CONFLICT = ApiProblem.of(409, CATEGORY, "offset_conflict", "Upload offset conflict");
    static final ApiProblem INCOMPLETE = ApiProblem.of(409, CATEGORY, "incomplete", "Upload incomplete");
    static final ApiProblem FILE_CHECKSUM_MISMATCH =
            ApiProblem.of(422, CATEGORY, "file_checksum_mismatch", "File checksum mismatch");
    static final ApiProblem EXPIRED = ApiProblem.of(410, CATEGORY, "expired", "Upload expired");
    static final ApiProblem METADATA_INVALID = ApiProblem.of(400, CATEGORY, "metadata_invalid", "Upload metadata invalid");
    /** Story 1.21: an image set past 500 images or 2 GiB in all. */
    static final ApiProblem SET_TOO_LARGE = ApiProblem.of(413, CATEGORY, "set_too_large", "Image set too large")
            .withParams(Map.of("maxImages", Uploads.MAX_IMAGES, "maxBytes", Uploads.MAX_LENGTH));
    /** Story 1.21: a member of an image set must be an image. */
    static final ApiProblem IMAGE_FORMAT_REFUSED = ApiProblem.of(415, CATEGORY, "format_refused", "Format not accepted")
            .withParams(Map.of("accepted", Format.IMAGE_CODES));
    /** Story 1.21: only a failed image of a set is replaced or left out. */
    static final ApiProblem IMAGE_NOT_FAILED = ApiProblem.of(409, CATEGORY, "image_not_failed", "Image not failed");
    /** The reasons a member of an image set failed, as its row records them (Story 1.21). */
    public static final String EXPIRED_CODE = "upload.expired";
    public static final String FORMAT_REFUSED_CODE = "upload.format_refused";
    public static final String FILE_CHECKSUM_MISMATCH_CODE = "upload.file_checksum_mismatch";

    private UploadProblems() {
    }

    static ApiException tooLarge(long limit) {
        return new ApiException(TOO_LARGE.withParams(Map.of("limit", limit)));
    }

    static ApiException formatRefused() {
        return new ApiException(FORMAT_REFUSED);
    }

    static ApiException busy() {
        return new ApiException(BUSY);
    }

    static ApiException partChecksumMismatch() {
        return new ApiException(PART_CHECKSUM_MISMATCH);
    }

    static ApiException offsetConflict() {
        return new ApiException(OFFSET_CONFLICT);
    }

    static ApiException incomplete() {
        return new ApiException(INCOMPLETE);
    }

    static ApiException fileChecksumMismatch() {
        return new ApiException(FILE_CHECKSUM_MISMATCH);
    }

    static ApiException expired() {
        return new ApiException(EXPIRED);
    }

    static ApiException setTooLarge() {
        return new ApiException(SET_TOO_LARGE);
    }

    static ApiException imageFormatRefused() {
        return new ApiException(IMAGE_FORMAT_REFUSED);
    }

    static ApiException imageNotFailed() {
        return new ApiException(IMAGE_NOT_FAILED);
    }

    public static ApiException metadataInvalid() {
        return new ApiException(METADATA_INVALID);
    }

    public static ApiException notFound() {
        return new ApiException(ApiProblem.forStatus(404));
    }

    public static ApiException invalid() {
        return new ApiException(ApiProblem.forStatus(400));
    }
}
