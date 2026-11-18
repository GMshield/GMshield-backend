package io.github.gmshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.shared.service.StartupSettings;
import io.github.gmshield.shared.service.StartupSettings.SessionTimings;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Custom GMSHIELD_SESSION_* values reach the {@link SessionTimings} the sessions use (Story 1.10). */
@SpringBootTest(properties = {"GMSHIELD_MODE=test", "GMSHIELD_SESSION_RENEW_AFTER=20s", "GMSHIELD_SESSION_OVERLAP=5s",
        "GMSHIELD_SESSION_IDLE_TIMEOUT=2m", "GMSHIELD_SESSION_ABSOLUTE_TIMEOUT=1h"})
@ActiveProfiles({"test-mode", "unit"})
class SessionTimingsSettingsTest {

    private static final Map<String, String> SET = Map.of("GMSHIELD_SESSION_RENEW_AFTER", "20s",
            "GMSHIELD_SESSION_OVERLAP", "5s", "GMSHIELD_SESSION_IDLE_TIMEOUT", "2m", "GMSHIELD_SESSION_ABSOLUTE_TIMEOUT", "1h");

    @Autowired
    private SessionTimings timings;

    @Test
    void customSessionSettingsReachTheTimingsBean() {
        assertThat(timings).isEqualTo(StartupSettings.sessionTimings(SET).orElseThrow());
        assertThat(timings).isEqualTo(new SessionTimings(Duration.ofSeconds(20), Duration.ofSeconds(5),
                Duration.ofMinutes(2), Duration.ofHours(1)));
    }
}
