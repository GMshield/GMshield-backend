package io.github.gmshield;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * ActuatorScopeFilter under a configuration that exposes everything: still only
 * {@code /actuator/health} answers. Without the filter, most of the paths below would.
 */
@TestPropertySource(properties = {
    "management.endpoints.web.exposure.include=*",
    "management.endpoints.access.default=read-only",
    "management.endpoints.web.discovery.enabled=true",
    "management.endpoint.health.show-components=always",
    "management.endpoint.health.show-details=always",
    "management.endpoint.health.probes.enabled=true"
})
class ActuatorScopePermissiveTest extends HttpTestSupport {

    @Test
    void healthStillAnswers() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("status").asString()).isEqualTo("UP");
        // Proof that this configuration really is permissive.
        assertThat(json(response).has("components")).isTrue();
    }

    @Test
    void noOtherActuatorPathAnswers() throws Exception {
        for (String path : HealthAndVersionTest.OTHER_ACTUATOR_PATHS) {
            HttpResponse<String> response = get(path);

            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).as(path)
                    .startsWith("application/problem+json");
            assertThat(json(response).path("code").asString()).as(path).isEqualTo("request.not_found");
        }
    }
}
