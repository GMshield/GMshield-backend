package io.github.gmshield.vault.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.HttpTestSupport;
import org.junit.jupiter.api.Test;

/** Outside test mode the seed route does not exist. */
class Nfr1ProbeAbsentTest extends HttpTestSupport {

    @Test
    void seedAndReadBackAreNotFound() throws Exception {
        assertThat(send("POST", Nfr1ProbeController.PATH).statusCode()).isEqualTo(404);
        assertThat(get(Nfr1ProbeController.PATH + "/0195a3c0-0000-7000-8000-000000000000").statusCode()).isEqualTo(404);
    }
}
