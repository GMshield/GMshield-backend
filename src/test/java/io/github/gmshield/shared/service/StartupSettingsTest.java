package io.github.gmshield.shared.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Mode and settings at start (I/O matrix of Story 1.1: missing setting, mixed mode). */
class StartupSettingsTest {

    private static final String SECRET_VALUE = "s3cr3t-value-never-printed";

    @Test
    void testModeNeedsNoSetting() {
        StartupSettings settings = StartupSettings.check(Map.of("GMSHIELD_MODE", "test"));

        assertThat(settings.refused()).isFalse();
        assertThat(settings.testMode()).isTrue();
    }

    @Test
    void outsideTestModeEverySettingPresentIsAllowed() {
        StartupSettings settings = StartupSettings.check(production());

        assertThat(settings.refused()).isFalse();
        assertThat(settings.testMode()).isFalse();
    }

    @Test
    void outsideTestModeAMissingDatabasePasswordIsRefusedAndNamed() {
        Map<String, String> environment = production();
        environment.remove("GMSHIELD_DB_PASSWORD");

        StartupSettings settings = StartupSettings.check(environment);

        assertThat(settings.refused()).isTrue();
        assertThat(settings.refusedSettings()).containsExactly("GMSHIELD_DB_PASSWORD");
        assertRefusalLine(settings, "setting.missing");
    }

    @Test
    void withoutAnySettingEveryRequiredOneIsNamed() {
        StartupSettings settings = StartupSettings.check(Map.of());

        assertThat(settings.refusedSettings()).containsExactlyElementsOf(StartupSettings.REQUIRED);
    }

    @Test
    void aBlankSettingIsMissing() {
        Map<String, String> environment = production();
        environment.put("GMSHIELD_REDIS_PASSWORD", " ");

        assertThat(StartupSettings.check(environment).refusedSettings()).containsExactly("GMSHIELD_REDIS_PASSWORD");
    }

    @Test
    void testModeWithAProductionSecretIsRefusedNamingTheConflict() {
        for (String secret : StartupSettings.SECRETS) {
            StartupSettings settings = StartupSettings.check(Map.of("GMSHIELD_MODE", "test", secret, SECRET_VALUE));

            assertThat(settings.refused()).isTrue();
            assertThat(settings.refusedSettings()).containsExactly("GMSHIELD_MODE", secret);
            assertRefusalLine(settings, "mode.test_with_production_secret");
        }
    }

    @Test
    void anUnknownModeIsRefused() {
        Map<String, String> environment = production();
        environment.put("GMSHIELD_MODE", "Test");

        StartupSettings settings = StartupSettings.check(environment);

        assertThat(settings.refused()).isTrue();
        assertThat(settings.refusedSettings()).containsExactly("GMSHIELD_MODE");
        assertRefusalLine(settings, "mode.unknown");
    }

    private static void assertRefusalLine(StartupSettings settings, String reason) {
        String line = settings.refusalLine();
        assertThat(line).doesNotContain(SECRET_VALUE).doesNotContain("\n");
        JsonNode parsed = JsonMapper.builder().build().readTree(line);
        assertThat(parsed.path("event").asString()).isEqualTo("startup.refused");
        assertThat(parsed.path("unit").asString()).isEqualTo("application");
        assertThat(parsed.path("traceId").asString()).isEqualTo(TraceContext.NONE);
        assertThat(parsed.path("reason").asString()).isEqualTo(reason);
        assertThat(parsed.path("settings").valueStream().map(JsonNode::asString))
                .containsExactlyElementsOf(settings.refusedSettings());
    }

    private static Map<String, String> production() {
        Map<String, String> environment = new HashMap<>();
        environment.put("GMSHIELD_DB_URL", "jdbc:postgresql://db.example:5432/gmshield");
        environment.put("GMSHIELD_DB_USER", "gmshield");
        environment.put("GMSHIELD_DB_PASSWORD", SECRET_VALUE);
        environment.put("GMSHIELD_REDIS_HOST", "redis.example");
        environment.put("GMSHIELD_REDIS_PASSWORD", SECRET_VALUE);
        return environment;
    }
}
