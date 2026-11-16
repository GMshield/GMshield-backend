package io.github.gmshield.accounts.service;

import io.github.gmshield.shared.service.StartupSettings;
import io.github.gmshield.shared.service.StartupSettings.SessionTimings;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The session timings (Story 1.10): {@code GMSHIELD_SESSION_RENEW_AFTER} (15m), {@code GMSHIELD_SESSION_OVERLAP}
 * (60s), {@code GMSHIELD_SESSION_IDLE_TIMEOUT} (24h) and {@code GMSHIELD_SESSION_ABSOLUTE_TIMEOUT} (7d), bound in
 * {@code application.yaml}. {@link StartupSettings} refuses a start before Spring when they are not in the order
 * overlap &lt; renew-after &lt; idle &le; absolute; this check repeats it for values set another way.
 */
@Configuration
class SessionConfiguration {

    @Bean
    SessionTimings sessionTimings(@Value("${gmshield.session.renew-after}") Duration renewAfter,
                                  @Value("${gmshield.session.overlap}") Duration overlap,
                                  @Value("${gmshield.session.idle-timeout}") Duration idleTimeout,
                                  @Value("${gmshield.session.absolute-timeout}") Duration absoluteTimeout) {
        return SessionTimings.ordered(renewAfter, overlap, idleTimeout, absoluteTimeout).orElseThrow(() ->
                new IllegalStateException(StartupSettings.REASON_INVALID + ": " + StartupSettings.SESSION_OVERLAP + ", "
                        + StartupSettings.SESSION_RENEW_AFTER + ", " + StartupSettings.SESSION_IDLE_TIMEOUT + ", "
                        + StartupSettings.SESSION_ABSOLUTE_TIMEOUT));
    }
}
