package io.github.gmshield.shared.service;

import java.util.Map;
import java.util.Objects;

/**
 * One kind of failure as the API reports it. The {@code code} is {@code category.reason}; the
 * frontend maps it to text and falls back to the category. {@code params} carries only values that
 * are safe to show (ids, limits, counts): never content, never anything internal.
 */
public record ApiProblem(int status, String category, String reason, String title, Map<String, Object> params) {

    public static final String CATEGORY_REQUEST = "request";
    public static final String CATEGORY_SERVER = "server";

    public ApiProblem {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(title, "title");
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public static ApiProblem of(int status, String category, String reason, String title) {
        return new ApiProblem(status, category, reason, title, Map.of());
    }

    public String code() {
        return category + "." + reason;
    }

    public ApiProblem withParams(Map<String, Object> newParams) {
        return new ApiProblem(status, category, reason, title, newParams);
    }

    public static ApiProblem unexpected() {
        return of(500, CATEGORY_SERVER, "unexpected", "Unexpected error");
    }

    /** The problem for a failure that is known only by its HTTP status. */
    public static ApiProblem forStatus(int status) {
        return switch (status) {
            case 400 -> of(400, CATEGORY_REQUEST, "invalid", "Invalid request");
            case 404 -> of(404, CATEGORY_REQUEST, "not_found", "Not found");
            case 405 -> of(405, CATEGORY_REQUEST, "method_not_allowed", "Method not allowed");
            case 406 -> of(406, CATEGORY_REQUEST, "not_acceptable", "Not acceptable");
            case 413 -> of(413, CATEGORY_REQUEST, "too_large", "Request too large");
            case 415 -> of(415, CATEGORY_REQUEST, "unsupported_media_type", "Unsupported media type");
            case 503 -> of(503, CATEGORY_SERVER, "unavailable", "Service unavailable");
            default -> status >= 400 && status < 500
                    ? of(status, CATEGORY_REQUEST, "rejected", "Request rejected")
                    : of(status >= 500 && status < 600 ? status : 500, CATEGORY_SERVER, "unexpected", "Unexpected error");
        };
    }
}
