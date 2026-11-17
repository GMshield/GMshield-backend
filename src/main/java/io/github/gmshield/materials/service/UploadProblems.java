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
