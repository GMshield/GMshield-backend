package io.github.gmshield.vault.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.HttpTestSupport;
import org.junit.jupiter.api.Test;

/** Outside test mode the seed route does not exist, and like all of /internal/v1 it needs the worker's credential. */
class Nfr1ProbeAbsentTest extends HttpTestSupport {

    @Test
    void seedAndReadBackAreNotFound() throws Exception {
        assertThat(send("POST", Nfr1ProbeController.PATH).statusCode()).isEqualTo(401);
        assertThat(sendAsWorker("POST", Nfr1ProbeController.PATH, "{}").statusCode()).isEqualTo(404);
        assertThat(getAsWorker(Nfr1ProbeController.PATH + "/0195a3c0-0000-7000-8000-000000000000").statusCode())
                .isEqualTo(404);
    }
}
