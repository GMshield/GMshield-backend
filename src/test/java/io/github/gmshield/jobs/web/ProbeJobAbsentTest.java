package io.github.gmshield.jobs.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.HttpTestSupport;
import org.junit.jupiter.api.Test;

/** Outside test mode no probe job can be created: the route does not exist, and it needs the worker's credential. */
class ProbeJobAbsentTest extends HttpTestSupport {

    @Test
    void theProbeRouteIsNotFoundAndNotInEitherContract() throws Exception {
        String body = "{\"lane\":\"interactive\",\"workMs\":0,\"failTimes\":0}";
        assertThat(send("POST", ProbeJobController.PATH).statusCode()).isEqualTo(401);
        assertThat(sendAsWorker("POST", ProbeJobController.PATH, body).statusCode()).isEqualTo(404);
        for (String api : new String[] {"public", "internal"}) {
            assertThat(getWithout("/v3/api-docs/" + api).body()).doesNotContain("probe").doesNotContain("test-mode");
        }
    }
}
