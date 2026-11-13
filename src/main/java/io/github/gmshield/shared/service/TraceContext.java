package io.github.gmshield.shared.service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * The trace id of the work in progress. It arrives in the W3C {@code traceparent} header, is
 * carried across the three applications, and appears in every log line and every error answer.
 */
public final class TraceContext {

    /** Key of the trace id in the logging context. */
    public static final String KEY = "traceId";

    /** What a log line carries when no request is being served (W3C: the invalid, absent trace). */
    public static final String NONE = "0".repeat(32);

    private static final Pattern TRACEPARENT =
            Pattern.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})(-.*)?$");
    private static final String NO_PARENT = "0".repeat(16);
    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceContext() {
    }

    /** The trace id of a valid {@code traceparent} header value; empty when missing or malformed. */
    public static Optional<String> fromTraceparent(String header) {
        if (header == null) {
            return Optional.empty();
        }
        Matcher matcher = TRACEPARENT.matcher(header.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String version = matcher.group(1);
        String traceId = matcher.group(2);
        boolean extraFields = matcher.group(5) != null;
        if (version.equals("ff") || (version.equals("00") && extraFields)
                || traceId.equals(NONE) || matcher.group(3).equals(NO_PARENT)) {
            return Optional.empty();
        }
        return Optional.of(traceId);
    }

    public static String newTraceId() {
        byte[] bytes = new byte[16];
        String traceId;
        do {
            RANDOM.nextBytes(bytes);
            traceId = HexFormat.of().formatHex(bytes);
        } while (traceId.equals(NONE));
        return traceId;
    }

    /** The trace id of the current thread's work, or {@link #NONE}. */
    public static String current() {
        String traceId = MDC.get(KEY);
        return traceId == null ? NONE : traceId;
    }

    public static void set(String traceId) {
        MDC.put(KEY, traceId);
    }

    public static void clear() {
        MDC.remove(KEY);
    }
}
