package io.github.gmshield.shared.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Decides, before anything starts, whether the mode and the settings allow a start.
 *
 * <ul>
 *   <li>{@code GMSHIELD_MODE=test} (set only by the pipeline): built-in throwaway connection
 *       settings. Refused when a production secret is present.</li>
 *   <li>No mode: every required setting must be present. Redis is reached over TLS verified against
 *       the private authority (the {@code redis-tls} profile) unless {@code GMSHIELD_REDIS_TLS=false},
 *       which only the development setup sets.</li>
 * </ul>
 *
 * A refusal names settings and never prints a value.
 */
public record StartupSettings(boolean testMode, boolean redisTls, String refusalReason, List<String> refusedSettings) {

    public static final String MODE = "GMSHIELD_MODE";
    public static final String TEST_MODE = "test";
    public static final String TEST_MODE_PROFILE = "test-mode";
    /** Outside test mode, unless switched off: Redis over TLS with the private authority's certificate. */
    public static final String REDIS_TLS_PROFILE = "redis-tls";
    public static final String REDIS_TLS = "GMSHIELD_REDIS_TLS";

    /** Required outside test mode. */
    public static final List<String> REQUIRED = List.of(
            "GMSHIELD_DB_URL", "GMSHIELD_DB_USER", "GMSHIELD_DB_PASSWORD", "GMSHIELD_REDIS_HOST",
            "GMSHIELD_REDIS_USERNAME", "GMSHIELD_REDIS_PASSWORD");

    /** Production secrets: test mode refuses to start beside any of them. */
    public static final List<String> SECRETS = List.of("GMSHIELD_DB_PASSWORD", "GMSHIELD_REDIS_PASSWORD");

    public static final String REASON_UNKNOWN_MODE = "mode.unknown";
    public static final String REASON_MISSING = "setting.missing";
    public static final String REASON_SECRET_IN_TEST_MODE = "mode.test_with_production_secret";
    public static final String REASON_INVALID = "setting.invalid";

    public StartupSettings {
        refusedSettings = List.copyOf(refusedSettings);
    }

    public static StartupSettings check(Map<String, String> environment) {
        String mode = environment.get(MODE);
        if (isBlank(mode)) {
            List<String> missing = REQUIRED.stream().filter(name -> isBlank(environment.get(name))).toList();
            if (!missing.isEmpty()) {
                return refuse(false, REASON_MISSING, missing);
            }
            String tls = environment.get(REDIS_TLS);
            if (isBlank(tls) || "true".equals(tls)) {
                return allowed(false, true);
            }
            return "false".equals(tls) ? allowed(false, false) : refuse(false, REASON_INVALID, List.of(REDIS_TLS));
        }
        if (!TEST_MODE.equals(mode)) {
            return refuse(false, REASON_UNKNOWN_MODE, List.of(MODE));
        }
        List<String> secrets = SECRETS.stream().filter(name -> !isBlank(environment.get(name))).toList();
        if (!secrets.isEmpty()) {
            List<String> conflict = new ArrayList<>();
            conflict.add(MODE);
            conflict.addAll(secrets);
            return refuse(true, REASON_SECRET_IN_TEST_MODE, conflict);
        }
        return allowed(true, false);
    }

    public boolean refused() {
        return refusalReason != null;
    }

    /** One JSON log line that names the reason and the settings. Names are constants: no escaping needed. */
    public String refusalLine() {
        String names = refusedSettings.stream().map(name -> "\"" + name + "\"").collect(Collectors.joining(","));
        return "{\"timestamp\":\"" + Instant.now() + "\",\"level\":\"ERROR\",\"traceId\":\"" + TraceContext.NONE
                + "\",\"unit\":\"application\",\"event\":\"startup.refused\",\"reason\":\"" + refusalReason
                + "\",\"settings\":[" + names + "]}";
    }

    /** The Spring profiles this start adds: {@code test-mode} or {@code redis-tls}, or none. */
    public List<String> profiles() {
        if (testMode) {
            return List.of(TEST_MODE_PROFILE);
        }
        return redisTls ? List.of(REDIS_TLS_PROFILE) : List.of();
    }

    private static StartupSettings allowed(boolean testMode, boolean redisTls) {
        return new StartupSettings(testMode, redisTls, null, List.of());
    }

    private static StartupSettings refuse(boolean testMode, String reason, List<String> settings) {
        return new StartupSettings(testMode, false, reason, settings);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
