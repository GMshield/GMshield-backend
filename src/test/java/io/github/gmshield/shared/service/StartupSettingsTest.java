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
    /** 64 hex characters, as the pipeline writes the frontend's service credential. */
    private static final String CREDENTIAL_VALUE = "c0ffee".repeat(10) + "beef";
    /** 64 hex characters, as the pipeline writes the worker's credential. */
    private static final String WORKER_CREDENTIAL_VALUE = "facade".repeat(10) + "d00d";

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

    @Test
    void outsideTestModeTheFrontendCredentialIsRequiredAndNamed() {
        Map<String, String> environment = production();
        environment.remove("GMSHIELD_FRONTEND_CREDENTIAL");

        StartupSettings settings = StartupSettings.check(environment);

        assertThat(settings.refusedSettings()).containsExactly("GMSHIELD_FRONTEND_CREDENTIAL");
        assertRefusalLine(settings, "setting.missing");
    }

    @Test
    void aFrontendCredentialThatIsShortOrCannotBeAHeaderIsInvalidAndNeverPrinted() {
        for (String bad : new String[] {"short", "a".repeat(31), "has space " + "a".repeat(32), "a".repeat(513),
                "line\nbreak" + "a".repeat(32), "ação" + "a".repeat(32)}) {
            Map<String, String> environment = production();
            environment.put("GMSHIELD_FRONTEND_CREDENTIAL", bad);

            StartupSettings settings = StartupSettings.check(environment);

            assertThat(settings.refusedSettings()).as(bad).containsExactly("GMSHIELD_FRONTEND_CREDENTIAL");
            assertThat(settings.refusalLine()).doesNotContain(bad);
            assertRefusalLine(settings, "setting.invalid");
        }
        assertThat(StartupSettings.frontendCredential(CREDENTIAL_VALUE)).isTrue();
        assertThat(StartupSettings.frontendCredential(StartupSettings.TEST_MODE_FRONTEND_CREDENTIAL)).isTrue();
    }

    @Test
    void thePublicTestModeCredentialIsRefusedOutsideTestMode() {
        Map<String, String> environment = production();
        environment.put("GMSHIELD_FRONTEND_CREDENTIAL", StartupSettings.TEST_MODE_FRONTEND_CREDENTIAL);

        StartupSettings settings = StartupSettings.check(environment);

        assertThat(settings.refusedSettings()).containsExactly("GMSHIELD_FRONTEND_CREDENTIAL");
        assertThat(settings.refusalLine()).doesNotContain(StartupSettings.TEST_MODE_FRONTEND_CREDENTIAL);
        assertRefusalLine(settings, "setting.invalid");
    }

    @Test
    void theFrontendCredentialIsAProductionSecretThatTestModeRefuses() {
        assertThat(StartupSettings.SECRETS).contains("GMSHIELD_FRONTEND_CREDENTIAL");
        StartupSettings settings = StartupSettings.check(Map.of("GMSHIELD_MODE", "test",
                "GMSHIELD_FRONTEND_CREDENTIAL", CREDENTIAL_VALUE));

        assertThat(settings.refusedSettings()).containsExactly("GMSHIELD_MODE", "GMSHIELD_FRONTEND_CREDENTIAL");
        assertRefusalLine(settings, "mode.test_with_production_secret");
    }

    @Test
    void outsideTestModeTheWorkerCredentialIsRequiredAndNamed() {
        Map<String, String> environment = production();
        environment.remove("GMSHIELD_WORKER_CREDENTIAL");

        StartupSettings settings = StartupSettings.check(environment);

        assertThat(settings.refusedSettings()).containsExactly("GMSHIELD_WORKER_CREDENTIAL");
        assertRefusalLine(settings, "setting.missing");
    }

    @Test
    void aWorkerCredentialThatCannotBeOneIsTheTestModeValueOrTheFrontendsIsInvalidAndNeverPrinted() {
        for (String bad : new String[] {"short", "a".repeat(31), "has space " + "a".repeat(32), "a".repeat(513),
                StartupSettings.TEST_MODE_WORKER_CREDENTIAL, CREDENTIAL_VALUE}) {
            Map<String, String> environment = production();
            environment.put("GMSHIELD_WORKER_CREDENTIAL", bad);

            StartupSettings settings = StartupSettings.check(environment);

            assertThat(settings.refusedSettings()).as(bad).containsExactly("GMSHIELD_WORKER_CREDENTIAL");
            assertThat(settings.refusalLine()).doesNotContain(bad);
            assertRefusalLine(settings, "setting.invalid");
        }
    }

    @Test
    void theWorkerCredentialIsAProductionSecretThatTestModeRefuses() {
        assertThat(StartupSettings.SECRETS).contains("GMSHIELD_WORKER_CREDENTIAL");
        StartupSettings settings = StartupSettings.check(Map.of("GMSHIELD_MODE", "test",
                "GMSHIELD_WORKER_CREDENTIAL", WORKER_CREDENTIAL_VALUE));

        assertThat(settings.refusedSettings()).containsExactly("GMSHIELD_MODE", "GMSHIELD_WORKER_CREDENTIAL");
        assertRefusalLine(settings, "mode.test_with_production_secret");
    }

    @Test
    void sessionTimingsDefaultTo15Minutes60Seconds24HoursAnd7Days() {
        StartupSettings.SessionTimings timings = StartupSettings.sessionTimings(Map.of()).orElseThrow();

        assertThat(timings.renewAfter()).isEqualTo(java.time.Duration.ofMinutes(15));
        assertThat(timings.overlap()).isEqualTo(java.time.Duration.ofSeconds(60));
        assertThat(timings.idleTimeout()).isEqualTo(java.time.Duration.ofHours(24));
        assertThat(timings.absoluteTimeout()).isEqualTo(java.time.Duration.ofDays(7));
    }

    @Test
    void sessionTimingsInOrderAreAllowedInBothModes() {
        Map<String, String> shortened = Map.of("GMSHIELD_SESSION_RENEW_AFTER", "20s", "GMSHIELD_SESSION_OVERLAP", "5s",
                "GMSHIELD_SESSION_IDLE_TIMEOUT", "2m", "GMSHIELD_SESSION_ABSOLUTE_TIMEOUT", "PT1H");
        Map<String, String> environment = production();
        environment.putAll(shortened);
        Map<String, String> testMode = new HashMap<>(shortened);
        testMode.put("GMSHIELD_MODE", "test");

        assertThat(StartupSettings.check(environment).refused()).isFalse();
        assertThat(StartupSettings.check(testMode).refused()).isFalse();
        assertThat(StartupSettings.sessionTimings(shortened).orElseThrow().idleTimeout()).isEqualTo(java.time.Duration.ofMinutes(2));
        // idle == absolute is allowed.
        Map<String, String> equal = Map.of("GMSHIELD_SESSION_IDLE_TIMEOUT", "7d");
        assertThat(StartupSettings.sessionTimings(equal)).isPresent();
    }

    @Test
    void sessionTimingsOutOfOrderAreInvalidAndAllFourNamed() {
        String[][] cases = {
                {"GMSHIELD_SESSION_RENEW_AFTER", "24h"},          // renew-after = idle
                {"GMSHIELD_SESSION_RENEW_AFTER", "2d"},           // renew-after > idle
                {"GMSHIELD_SESSION_OVERLAP", "15m"},              // overlap = renew-after
                {"GMSHIELD_SESSION_IDLE_TIMEOUT", "8d"},          // idle > absolute
                {"GMSHIELD_SESSION_ABSOLUTE_TIMEOUT", "1h"},      // absolute < idle
        };
        for (String[] bad : cases) {
            for (boolean testMode : new boolean[] {false, true}) {
                Map<String, String> environment = testMode ? new HashMap<>(Map.of("GMSHIELD_MODE", "test")) : production();
                environment.put(bad[0], bad[1]);

                StartupSettings settings = StartupSettings.check(environment);

                assertThat(settings.refusedSettings()).as(bad[0] + "=" + bad[1]).containsExactly(
                        "GMSHIELD_SESSION_OVERLAP", "GMSHIELD_SESSION_RENEW_AFTER", "GMSHIELD_SESSION_IDLE_TIMEOUT",
                        "GMSHIELD_SESSION_ABSOLUTE_TIMEOUT");
                assertRefusalLine(settings, "setting.invalid");
            }
        }
    }

    @Test
    void anUnreadableOrNonPositiveSessionTimingIsInvalidAndNamedAlone() {
        for (String bad : new String[] {"soon", "15", "-5m", "0s", "PT0S", "15 minutes"}) {
            Map<String, String> environment = production();
            environment.put("GMSHIELD_SESSION_IDLE_TIMEOUT", bad);

            StartupSettings settings = StartupSettings.check(environment);

            // "15" is 15 ms in Spring's simple form: positive, but then idle < renew-after.
            if (bad.equals("15")) {
                assertThat(settings.refusalReason()).isEqualTo("setting.invalid");
                continue;
            }
            assertThat(settings.refusedSettings()).as(bad).containsExactly("GMSHIELD_SESSION_IDLE_TIMEOUT");
            assertRefusalLine(settings, "setting.invalid");
        }
    }

    private static void assertRefusalLine(StartupSettings settings, String reason) {
        String line = settings.refusalLine();
        assertThat(line).doesNotContain(SECRET_VALUE).doesNotContain(CREDENTIAL_VALUE).doesNotContain("\n");
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
        environment.put("GMSHIELD_FRONTEND_CREDENTIAL", CREDENTIAL_VALUE);
        environment.put("GMSHIELD_WORKER_CREDENTIAL", WORKER_CREDENTIAL_VALUE);
        return environment;
    }
}
