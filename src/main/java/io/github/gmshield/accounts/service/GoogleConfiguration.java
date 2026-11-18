package io.github.gmshield.accounts.service;

import io.github.gmshield.shared.service.StartupSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Which Google the sign-in talks to. Outside test mode Google itself, with the client of the settings
 * {@code GMSHIELD_GOOGLE_CLIENT_ID} and {@code GMSHIELD_GOOGLE_CLIENT_SECRET} and the redirect URI
 * {@code <GMSHIELD_PUBLIC_URL>/auth/google/callback}, all checked by {@link StartupSettings}. In test mode
 * the stand-in, only when the pipeline really started test mode.
 */
@Configuration
class GoogleConfiguration {

    static final String CALLBACK_PATH = "/auth/google/callback";

    @Bean
    @Profile("!" + StartupSettings.TEST_MODE_PROFILE)
    GoogleSignIn google(@Value("${gmshield.google.client-id}") String clientId,
                        @Value("${gmshield.google.client-secret}") String clientSecret,
                        @Value("${gmshield.public-url}") String publicUrl) {
        String origin = StartupSettings.publicUrl(publicUrl);
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank() || origin == null) {
            // Names only, never a value (StartupSettings refuses this before Spring starts).
            throw new IllegalStateException(StartupSettings.REASON_INVALID + ": " + StartupSettings.GOOGLE_CLIENT_ID
                    + ", " + StartupSettings.GOOGLE_CLIENT_SECRET + " or " + StartupSettings.PUBLIC_URL);
        }
        return new GoogleOidcClient(clientId.strip(), clientSecret.strip(), origin + CALLBACK_PATH);
    }

    @Bean
    @Profile(StartupSettings.TEST_MODE_PROFILE)
    GoogleSignIn googleStandIn(Environment environment) {
        if (!StartupSettings.TEST_MODE.equals(environment.getProperty(StartupSettings.MODE))) {
            throw new IllegalStateException("the test-mode profile needs " + StartupSettings.MODE + "=test");
        }
        return new GoogleStandIn();
    }
}
