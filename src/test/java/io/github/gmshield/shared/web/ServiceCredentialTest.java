package io.github.gmshield.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.HttpTestSupport;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Story 1.10, "No credential": every {@code /api/v1} request without exactly the frontend's service
 * credential is {@code service.unauthenticated} (401) before any controller; the actuator, the internal
 * API and the contract documents keep their own rules.
 */
class ServiceCredentialTest extends HttpTestSupport {

    @Test
    void withTheCredentialThePublicApiAnswers() throws Exception {
        assertThat(get("/api/v1/version").statusCode()).isEqualTo(200);
    }

    @Test
    void withoutOrWithAWrongCredentialEveryPublicPathIsRefused() throws Exception {
        String[][] variants = {
                {},
                {"Authorization", "Bearer wrong-" + CREDENTIAL},
                {"Authorization", "Bearer " + CREDENTIAL.substring(1)},
                {"Authorization", "Bearer " + CREDENTIAL + "x"},
                {"Authorization", "bearer " + CREDENTIAL},
                {"Authorization", "Bearer  " + CREDENTIAL},
                {"Authorization", "Basic " + CREDENTIAL},
                {"Authorization", CREDENTIAL},
                {"Authorization", "Bearer"},
                {"Authorization", "Bearer " + CREDENTIAL, "Authorization", "Bearer " + CREDENTIAL},
        };
        for (String path : new String[] {"/api/v1/version", "/api/v1/me", "/api/v1/nope", "/api/v1"}) {
            for (String[] headers : variants) {
                HttpResponse<String> response = getWithout(path, headers);
                assertUnauthenticated(response, path + " " + String.join(" ", headers));
            }
        }
    }

    @Test
    void aRefusalHappensBeforeRoutingWhateverTheMethodOrADotSegment() throws Exception {
        for (String request : new String[] {
                "DELETE /api/v1/session HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
                "POST /api/v1/session/renewal HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                "GET /api/./v1/version HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
                "GET /internal/../api/v1/version HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"}) {
            String answer = raw(request);
            assertThat(answer).as(request).startsWith("HTTP/1.1 401").contains("\"code\":\"service.unauthenticated\"");
        }
    }

    @Test
    void theActuatorTheInternalApiAndTheContractsKeepTheirRules() throws Exception {
        assertThat(getWithout("/actuator/health").statusCode()).isEqualTo(200);
        assertThat(getWithout("/internal/v1/version").statusCode()).isEqualTo(200);
        assertThat(getWithout("/v3/api-docs/public").statusCode()).isEqualTo(200);
        assertThat(getWithout("/nope").statusCode()).isEqualTo(404);
    }

    @Test
    void theContractNamesTheCredential() throws Exception {
        JsonNode document = json(getWithout("/v3/api-docs/public"));
        assertThat(document.path("components").path("securitySchemes").path("serviceCredential").path("scheme").asString())
                .isEqualTo("bearer");
        assertThat(document.path("security").get(0).has("serviceCredential")).isTrue();
        assertThat(json(getWithout("/v3/api-docs/internal")).has("security")).isFalse();
    }

    @Test
    void theComparisonLooksAtEveryByte() {
        byte[] expected = "Bearer abc".getBytes(StandardCharsets.UTF_8);
        assertThat(ServiceCredentialFilter.sameBytes(expected, "Bearer abc".getBytes(StandardCharsets.UTF_8))).isTrue();
        for (String other : new String[] {"Bearer abd", "Bearer ab", "Bearer abcd", "", "bearer abc"}) {
            assertThat(ServiceCredentialFilter.sameBytes(expected, other.getBytes(StandardCharsets.UTF_8))).as(other).isFalse();
        }
    }

    @Test
    void aCredentialThatCannotBeOneStopsTheStartNamingOnlyTheSetting() {
        assertThatThrownBy(() -> new ServiceCredentialFilter("short"))
                .hasMessage("setting.invalid: GMSHIELD_FRONTEND_CREDENTIAL");
        assertThatThrownBy(() -> new ServiceCredentialFilter(null)).hasMessageNotContaining("null:");
    }

    private static void assertUnauthenticated(HttpResponse<String> response, String what) {
        assertThat(response.statusCode()).as(what).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).as(what).startsWith("application/problem+json");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode problem = json(response);
        assertThat(problem.path("code").asString()).as(what).isEqualTo("service.unauthenticated");
        assertThat(problem.path("category").asString()).isEqualTo("service");
        assertThat(problem.path("traceId").asString()).matches("[0-9a-f]{32}");
        assertThat(response.body()).doesNotContain(CREDENTIAL);
    }
}
