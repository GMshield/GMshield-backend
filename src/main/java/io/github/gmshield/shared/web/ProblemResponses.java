package io.github.gmshield.shared.web;

import io.github.gmshield.shared.service.ApiProblem;
import io.github.gmshield.shared.service.ProblemBody;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Builds the one error answer of both APIs. */
final class ProblemResponses {

    private ProblemResponses() {
    }

    static ResponseEntity<ProblemBody> of(ApiProblem problem, String traceId, HttpHeaders extraHeaders) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.status());
        if (extraHeaders != null) {
            // Only what a client needs to correct its request, for example Allow on a 405.
            for (String name : new String[] {HttpHeaders.ALLOW, HttpHeaders.ACCEPT, HttpHeaders.ACCEPT_PATCH}) {
                if (extraHeaders.containsHeader(name)) {
                    response.header(name, extraHeaders.getValuesAsList(name).toArray(String[]::new));
                }
            }
        }
        return response
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(ProblemBody.of(problem, traceId));
    }
}
