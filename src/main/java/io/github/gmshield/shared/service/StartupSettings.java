package io.github.gmshield.shared.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Decides, before anything starts, whether the mode and the settings allow a start.
 *
 * <ul>
 *   <li>{@code GMSHIELD_MODE=test} (set only by the pipeline): built-in throwaway connection
 *       settings. Refused when a production secret is present.</li>
 *   <li>No mode: every required setting must be present, each vault key must be 32 bytes in
 *       Base64, and the public URL an https origin (http only on localhost). Redis is reached over TLS verified against the private authority (the
 *       {@code redis-tls} profile) unless {@code GMSHIELD_REDIS_TLS=false}, which only the
 *       development setup sets.</li>
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

    /** Wraps every owner's data key (AD-19). */
    public static final String VAULT_MASTER_KEY = "GMSHIELD_VAULT_MASTER_KEY";
    /** Source of the fingerprint and token-hash subkeys (AD-19). */
    public static final String VAULT_INDEX_KEY = "GMSHIELD_VAULT_INDEX_KEY";
    /** Length of each vault key once decoded from Base64. */
    public static final int VAULT_KEY_BYTES = 32;
    /** The Google OAuth client of the sign-in (Story 1.9), entered by hand in the server's secrets. */
    public static final String GOOGLE_CLIENT_ID = "GMSHIELD_GOOGLE_CLIENT_ID";
    public static final String GOOGLE_CLIENT_SECRET = "GMSHIELD_GOOGLE_CLIENT_SECRET";
    /** The origin the browser sees for the frontend; Google's redirect goes to {@code <it>/auth/google/callback}. */
    public static final String PUBLIC_URL = "GMSHIELD_PUBLIC_URL";
    /** The only hosts a plain-http public URL may name: the browser treats them as secure for cookies. */
    private static final java.util.Set<String> LOOPBACK_HOSTS = java.util.Set.of("localhost", "127.0.0.1", "[::1]");

    /** Required outside test mode. */
    public static final List<String> REQUIRED = List.of(
            "GMSHIELD_DB_URL", "GMSHIELD_DB_USER", "GMSHIELD_DB_PASSWORD", "GMSHIELD_REDIS_HOST",
            "GMSHIELD_REDIS_USERNAME", "GMSHIELD_REDIS_PASSWORD", VAULT_MASTER_KEY, VAULT_INDEX_KEY,
            GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, PUBLIC_URL);

    /** Production secrets: test mode refuses to start beside any of them. */
    public static final List<String> SECRETS = List.of(
            "GMSHIELD_DB_PASSWORD", "GMSHIELD_REDIS_PASSWORD", VAULT_MASTER_KEY, VAULT_INDEX_KEY,
            GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET);

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
            List<String> badKeys = invalidVaultKeys(environment);
            if (!badKeys.isEmpty()) {
                return refuse(false, REASON_INVALID, badKeys);
            }
            if (publicUrl(environment.get(PUBLIC_URL)) == null) {
                return refuse(false, REASON_INVALID, List.of(PUBLIC_URL));
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

    /**
     * The vault keys that are not 32 bytes in standard Base64, or both when they are the same key.
     * The values are only decoded and compared: never kept, printed or put in an exception.
     */
    private static List<String> invalidVaultKeys(Map<String, String> environment) {
        byte[] master = vaultKey(environment.get(VAULT_MASTER_KEY));
        byte[] index = vaultKey(environment.get(VAULT_INDEX_KEY));
        List<String> invalid = new ArrayList<>();
        if (master == null) {
            invalid.add(VAULT_MASTER_KEY);
        }
        if (index == null) {
            invalid.add(VAULT_INDEX_KEY);
        }
        if (invalid.isEmpty() && java.util.Arrays.equals(master, index)) {
            return List.of(VAULT_MASTER_KEY, VAULT_INDEX_KEY);
        }
        return invalid;
    }

    /** The decoded key, or null when the value is not exactly {@link #VAULT_KEY_BYTES} bytes in Base64. */
    public static byte[] vaultKey(String value) {
        if (value == null) {
            return null;
        }
        try {
            byte[] key = Base64.getDecoder().decode(value.strip());
            return key.length == VAULT_KEY_BYTES ? key : null;
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }

    /**
     * The public URL as an origin without a trailing slash ({@code https://app.example} or
     * {@code http://localhost:3000}), or null when it is not an https URL with a host and nothing else
     * (no user, path, query or fragment). Plain http is accepted only for {@code localhost},
     * {@code 127.0.0.1} and {@code [::1]}: elsewhere browsers drop the {@code __Host-} cookies and every
     * sign-in would silently expire.
     */
    public static String publicUrl(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        try {
            java.net.URI uri = new java.net.URI(stripped);
            boolean web = "https".equals(uri.getScheme())
                    || ("http".equals(uri.getScheme()) && LOOPBACK_HOSTS.contains(uri.getHost()));
            boolean originOnly = uri.getHost() != null && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
            return web && originOnly ? stripped : null;
        } catch (java.net.URISyntaxException notAUrl) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
