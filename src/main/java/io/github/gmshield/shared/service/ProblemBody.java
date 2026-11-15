package io.github.gmshield.shared.service;

import java.util.Map;

/**
 * The single error shape of both APIs: an RFC 9457 problem detail ({@code application/problem+json})
 * extended with {@code category}, {@code code}, {@code params} and {@code traceId}. It has no
 * {@code detail} and no {@code instance}: nothing internal has a field to leak through.
 */
public record ProblemBody(
        String type, String title, int status, String category, String code, Map<String, Object> params,
        String traceId) {

    public static final String MEDIA_TYPE = "application/problem+json";
    public static final String TYPE_PREFIX = "urn:gmshield:problem:";

    public static ProblemBody of(ApiProblem problem, String traceId) {
        return new ProblemBody(
                TYPE_PREFIX + problem.code(), problem.title(), problem.status(), problem.category(), problem.code(),
                problem.params(), traceId);
    }
}
