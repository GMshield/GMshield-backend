package io.github.gmshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.shared.service.StartupSettings;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The decision taken before Spring exists: refuse and name the settings, or start in the right mode. */
class GmshieldApplicationTest {

    private static final Map<String, String> PRODUCTION = Map.of(
            "GMSHIELD_DB_URL", "jdbc:postgresql://db.example:5432/gmshield", "GMSHIELD_DB_USER", "gmshield",
            "GMSHIELD_DB_PASSWORD", "value-never-printed", "GMSHIELD_REDIS_HOST", "redis.example",
            "GMSHIELD_REDIS_USERNAME", "backend", "GMSHIELD_REDIS_PASSWORD", "value-never-printed");

    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(printed, true, StandardCharsets.UTF_8);

    @Test
    void exitStatusOfARefusedStartIs78() {
        assertThat(GmshieldApplication.EXIT_REFUSED).isEqualTo(78);
    }

    @Test
    void missingSettingsPrintOneRefusalLineAndCreateNoApplication() {
        Optional<SpringApplication> application = GmshieldApplication.prepare(Map.of(), out);

        assertThat(application).isEmpty();
        String[] lines = printed.toString(StandardCharsets.UTF_8).split("\\R");
        assertThat(lines).hasSize(1);
        JsonNode line = JsonMapper.builder().build().readTree(lines[0]);
        assertThat(line.path("event").asString()).isEqualTo("startup.refused");
        assertThat(line.path("reason").asString()).isEqualTo("setting.missing");
        assertThat(line.path("settings").valueStream().map(JsonNode::asString))
                .containsExactlyElementsOf(StartupSettings.REQUIRED);
    }

    @Test
    void testModeBesideAProductionSecretIsRefusedWithoutPrintingTheValue() {
        Optional<SpringApplication> application = GmshieldApplication.prepare(
                Map.of("GMSHIELD_MODE", "test", "GMSHIELD_DB_PASSWORD", "value-never-printed"), out);

        assertThat(application).isEmpty();
        assertThat(printed.toString(StandardCharsets.UTF_8))
                .contains("mode.test_with_production_secret", "GMSHIELD_MODE", "GMSHIELD_DB_PASSWORD")
                .doesNotContain("value-never-printed");
    }

    @Test
    void testModeActivatesTheTestModeProfile() {
        Optional<SpringApplication> application = GmshieldApplication.prepare(Map.of("GMSHIELD_MODE", "test"), out);

        assertThat(application).isPresent();
        assertThat(application.get().getAdditionalProfiles()).containsExactly("test-mode");
        assertThat(printed.size()).isZero();
    }

    @Test
    void productionSettingsStartWithRedisTlsAndWithoutTheTestModeProfile() {
        Optional<SpringApplication> application = GmshieldApplication.prepare(PRODUCTION, out);

        assertThat(application).isPresent();
        assertThat(application.get().getAdditionalProfiles()).containsExactly("redis-tls");
        assertThat(printed.size()).isZero();
    }

    @Test
    void theDevelopmentSetupCanSwitchRedisTlsOff() {
        Map<String, String> development = new HashMap<>(PRODUCTION);
        development.put("GMSHIELD_REDIS_TLS", "false");

        Optional<SpringApplication> application = GmshieldApplication.prepare(development, out);

        assertThat(application).isPresent();
        assertThat(application.get().getAdditionalProfiles()).isEmpty();
    }
}
