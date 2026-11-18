package io.github.gmshield.accounts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Test mode's Google: its authorization URL is the frontend's callback, and its codes name the identity. */
class GoogleStandInTest {

    private final GoogleStandIn standIn = new GoogleStandIn();

    @Test
    void authorizationUrlIsTheFrontendCallbackWithTheRealStateAndTheDefaultCode() {
        assertThat(standIn.authorizationUrl("st-ate_1", "nonce", "challenge"))
                .isEqualTo("/auth/google/callback?state=st-ate_1&code=stand-in.1.gm%40example.test");
    }

    @Test
    void anyStandInCodeGivesItsIdentity() {
        GoogleIdentity identity = standIn.exchange("stand-in.42.renata.mestre@example.test", "verifier", "nonce");

        assertThat(identity.subject()).isEqualTo("42");
        assertThat(identity.email()).isEqualTo("renata.mestre@example.test");
        assertThat(standIn.exchange(GoogleStandIn.DEFAULT_CODE, "v", "n")).isEqualTo(new GoogleIdentity("1", "gm@example.test"));
    }

    @Test
    void anyOtherCodeIsRefused() {
        for (String code : new String[] {"", "stand-in.", "stand-in.1.", "stand-in.1.no-at", "stand-in..a@b", "4/0AQ-real-code",
                "stand-in.1.a@b@c", "stand-in.a b.x@y"}) {
            assertThatThrownBy(() -> standIn.exchange(code, "v", "n")).as(code).hasMessage("request.sign_in_refused");
        }
        assertThatThrownBy(() -> standIn.exchange(null, "v", "n")).hasMessage("request.sign_in_refused");
    }

    @Test
    void theStandInNeedsARealTestModeAndGoogleNeedsItsSettings() {
        GoogleConfiguration configuration = new GoogleConfiguration();

        assertThatThrownBy(() -> configuration.googleStandIn(new MockEnvironment()))
                .hasMessage("the test-mode profile needs GMSHIELD_MODE=test");
        assertThat(configuration.googleStandIn(new MockEnvironment().withProperty("GMSHIELD_MODE", "test")))
                .isInstanceOf(GoogleStandIn.class);
        assertThatThrownBy(() -> configuration.google("id", "secret-never-shown", "not a url"))
                .hasMessageContaining("GMSHIELD_PUBLIC_URL").hasMessageNotContaining("secret-never-shown");
        assertThat(configuration.google("id", "secret", "https://app.example/").authorizationUrl("s", "n", "c"))
                .contains("redirect_uri=https%3A%2F%2Fapp.example%2Fauth%2Fgoogle%2Fcallback");
    }
}
