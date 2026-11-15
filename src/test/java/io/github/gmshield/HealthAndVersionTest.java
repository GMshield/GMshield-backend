package io.github.gmshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.shared.service.ContractVersions;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Health and the two contract versions (I/O matrix of Story 1.1). */
class HealthAndVersionTest extends HttpTestSupport {

    static final List<String> OTHER_ACTUATOR_PATHS = List.of(
            "/actuator", "/actuator/", "/actuator/info", "/actuator/env", "/actuator/beans", "/actuator/metrics",
            "/actuator/loggers", "/actuator/heapdump", "/actuator/shutdown", "/actuator/modulith",
            "/actuator/health/", "/actuator/health/db", "/actuator/health/ping", "/actuator/health/liveness",
            "/actuator/health/readiness");

    @Test
    void healthAnswersTheStatusOnlyWithoutACredential() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.propertyNames()).containsExactly("status");
        assertThat(body.path("status").asString()).isEqualTo("UP");
    }

    @Test
    void noOtherActuatorPathAnswers() throws Exception {
        for (String path : OTHER_ACTUATOR_PATHS) {
            HttpResponse<String> response = get(path);

            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertThat(json(response).path("code").asString()).as(path).isEqualTo("request.not_found");
        }
    }

    @Test
    void publicVersionIsTheVersionOfThePublicDocument() throws Exception {
        HttpResponse<String> response = get("/api/v1/version");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("version").asString())
                .isEqualTo(ContractVersions.PUBLIC_API)
                .isEqualTo(committedVersion("public", ContractVersions.PUBLIC_API))
                .isEqualTo(json(get("/v3/api-docs/public")).path("info").path("version").asString());
    }

    @Test
    void internalVersionIsTheVersionOfTheInternalDocument() throws Exception {
        HttpResponse<String> response = get("/internal/v1/version");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("version").asString())
                .isEqualTo(ContractVersions.INTERNAL_API)
                .isEqualTo(committedVersion("internal", ContractVersions.INTERNAL_API))
                .isEqualTo(json(get("/v3/api-docs/internal")).path("info").path("version").asString());
    }

    /** In update mode the committed file is about to be rewritten and may be stale: it is not compared. */
    private static String committedVersion(String api, String whenUpdating) throws Exception {
        if (Boolean.getBoolean("gmshield.openapi.update")) {
            return whenUpdating;
        }
        return JSON.readTree(Files.readString(Path.of("openapi", api + ".json"))).path("info").path("version").asString();
    }
}
