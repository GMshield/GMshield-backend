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
    /** 32 bytes of 5 and of 6, in Base64. */
    private static final String MASTER_KEY = "BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU=";
    private static final String INDEX_KEY = "BgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgY=";

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
    void outsideTestModeRedisTlsIsOnUnlessSwitchedOff() {
        assertThat(StartupSettings.check(production()).redisTls()).isTrue();
        assertThat(StartupSettings.check(production()).profiles()).containsExactly("redis-tls");

        Map<String, String> environment = production();
        environment.put("GMSHIELD_REDIS_TLS", "true");
        assertThat(StartupSettings.check(environment).profiles()).containsExactly("redis-tls");

        environment.put("GMSHIELD_REDIS_TLS", "false");
        assertThat(StartupSettings.check(environment).redisTls()).isFalse();
        assertThat(StartupSettings.check(environment).profiles()).isEmpty();
    }

    @Test
    void anUnclearRedisTlsValueIsRefused() {
        Map<String, String> environment = production();
        environment.put("GMSHIELD_REDIS_TLS", "no");

        StartupSettings settings = StartupSettings.check(environment);

        assertThat(settings.refused()).isTrue();
        assertThat(settings.refusedSettings()).containsExactly("GMSHIELD_REDIS_TLS");
        assertRefusalLine(settings, "setting.invalid");
    }

    @Test
    void testModeUsesPlainConnections() {
        StartupSettings settings = StartupSettings.check(Map.of("GMSHIELD_MODE", "test"));

        assertThat(settings.redisTls()).isFalse();
        assertThat(settings.profiles()).containsExactly("test-mode");
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

    @Test
    void outsideTestModeMissingVaultKeysAreRefusedAndNamed() {
        Map<String, String> environment = production();
        environment.remove("GMSHIELD_VAULT_MASTER_KEY");
        environment.remove("GMSHIELD_VAULT_INDEX_KEY");

        StartupSettings settings = StartupSettings.check(environment);

        assertThat(settings.refused()).isTrue();
        assertThat(settings.refusedSettings()).containsExactly("GMSHIELD_VAULT_MASTER_KEY", "GMSHIELD_VAULT_INDEX_KEY");
        assertRefusalLine(settings, "setting.missing");
    }

    @Test
    void aVaultKeyThatIsNot32BytesOfBase64IsInvalidAndNeverPrinted() {
        String notBase64 = "not base64 at all: " + SECRET_VALUE;
        String shortKey = "c2hvcnQta2V5LXZhbHVl";                       // 15 bytes
        String longKey = "BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUF";   // 33 bytes
        for (String bad : new String[] {notBase64, shortKey, longKey}) {
            Map<String, String> environment = production();
            environment.put("GMSHIELD_VAULT_INDEX_KEY", bad);

            StartupSettings settings = StartupSettings.check(environment);

            assertThat(settings.refused()).isTrue();
            assertThat(settings.refusedSettings()).containsExactly("GMSHIELD_VAULT_INDEX_KEY");
            assertRefusalLine(settings, "setting.invalid");
            assertThat(settings.refusalLine()).doesNotContain(bad);
        }
    }

    @Test
    void theSameKeyForBothVaultKeysIsInvalid() {
        Map<String, String> environment = production();
        environment.put("GMSHIELD_VAULT_INDEX_KEY", MASTER_KEY);

        StartupSettings settings = StartupSettings.check(environment);

        assertThat(settings.refusedSettings()).containsExactly("GMSHIELD_VAULT_MASTER_KEY", "GMSHIELD_VAULT_INDEX_KEY");
        assertRefusalLine(settings, "setting.invalid");
    }

    @Test
    void vaultKeysAreProductionSecretsThatTestModeRefuses() {
        assertThat(StartupSettings.SECRETS).contains("GMSHIELD_VAULT_MASTER_KEY", "GMSHIELD_VAULT_INDEX_KEY");
        StartupSettings settings = StartupSettings.check(Map.of("GMSHIELD_MODE", "test", "GMSHIELD_VAULT_MASTER_KEY", MASTER_KEY));

        assertThat(settings.refusedSettings()).containsExactly("GMSHIELD_MODE", "GMSHIELD_VAULT_MASTER_KEY");
        assertThat(settings.refusalLine()).doesNotContain(MASTER_KEY);
    }

    @Test
    void vaultKeyDecodesExactly32Bytes() {
        assertThat(StartupSettings.vaultKey(MASTER_KEY)).hasSize(32).containsOnly(5);
        assertThat(StartupSettings.vaultKey(" " + MASTER_KEY + "\n")).hasSize(32);
        assertThat(StartupSettings.vaultKey(null)).isNull();
        assertThat(StartupSettings.vaultKey("")).isNull();
    }

    @Test
    void outsideTestModeTheGoogleSettingsAreRequiredAndNamed() {
        Map<String, String> environment = production();
        environment.remove("GMSHIELD_GOOGLE_CLIENT_ID");
        environment.remove("GMSHIELD_GOOGLE_CLIENT_SECRET");
        environment.remove("GMSHIELD_PUBLIC_URL");

        StartupSettings settings = StartupSettings.check(environment);

        assertThat(settings.refused()).isTrue();
        assertThat(settings.refusedSettings())
                .containsExactly("GMSHIELD_GOOGLE_CLIENT_ID", "GMSHIELD_GOOGLE_CLIENT_SECRET", "GMSHIELD_PUBLIC_URL");
        assertRefusalLine(settings, "setting.missing");
    }

    @Test
    void theGoogleClientIsAProductionSecretThatTestModeRefuses() {
        for (String secret : new String[] {"GMSHIELD_GOOGLE_CLIENT_ID", "GMSHIELD_GOOGLE_CLIENT_SECRET"}) {
            assertThat(StartupSettings.SECRETS).contains(secret);
            StartupSettings settings = StartupSettings.check(Map.of("GMSHIELD_MODE", "test", secret, SECRET_VALUE));

            assertThat(settings.refusedSettings()).containsExactly("GMSHIELD_MODE", secret);
            assertRefusalLine(settings, "mode.test_with_production_secret");
        }
        // The public URL is a plain setting: the pipeline's staging copy has it, and test mode ignores it.
        assertThat(StartupSettings.check(Map.of("GMSHIELD_MODE", "test", "GMSHIELD_PUBLIC_URL", "http://localhost:3000"))
                .refused()).isFalse();
    }

    @Test
    void thePublicUrlMustBeAnHttpsOriginOrLocalhost() {
        for (String good : new String[] {"http://localhost:3000", "http://127.0.0.1:3000", "http://[::1]:3000",
                "https://app.example", "https://app.example/"}) {
            Map<String, String> environment = production();
            environment.put("GMSHIELD_PUBLIC_URL", good);
            assertThat(StartupSettings.check(environment).refused()).as(good).isFalse();
        }
        assertThat(StartupSettings.publicUrl(" https://app.example/ ")).isEqualTo("https://app.example");
        for (String bad : new String[] {"http://app.example", "http://192.168.15.8:3000", "localhost:3000", "ftp://app.example", "https://app.example/path",
                "https://user@app.example", "https://app.example?x=1", "https://app.example#x", "https://", "not a url"}) {
            Map<String, String> environment = production();
            environment.put("GMSHIELD_PUBLIC_URL", bad);

            StartupSettings settings = StartupSettings.check(environment);

            assertThat(settings.refusedSettings()).as(bad).containsExactly("GMSHIELD_PUBLIC_URL");
            assertRefusalLine(settings, "setting.invalid");
        }
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
        environment.put("GMSHIELD_REDIS_USERNAME", "backend");
        environment.put("GMSHIELD_REDIS_PASSWORD", SECRET_VALUE);
        environment.put("GMSHIELD_VAULT_MASTER_KEY", MASTER_KEY);
        environment.put("GMSHIELD_VAULT_INDEX_KEY", INDEX_KEY);
        environment.put("GMSHIELD_GOOGLE_CLIENT_ID", "client-id.apps.example");
        environment.put("GMSHIELD_GOOGLE_CLIENT_SECRET", SECRET_VALUE);
        environment.put("GMSHIELD_PUBLIC_URL", "http://localhost:3000");
        return environment;
    }
}
