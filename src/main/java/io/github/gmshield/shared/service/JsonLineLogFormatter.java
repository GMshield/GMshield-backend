package io.github.gmshield.shared.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

/**
 * Writes each log event as one JSON object on one line: {@code timestamp}, {@code level},
 * {@code traceId}, {@code unit} and {@code event}, then the event's own fields.
 *
 * <p>The application's own code logs an event name as the message and ids or numbers as key-value
 * pairs ({@code log.atInfo().addKeyValue("status", 200).log("request.completed")}): never content,
 * never a secret. Message arguments are not written at all. A line from a library is the event
 * {@code framework.log} with the library's message. An exception is written as its type and the
 * place it was thrown, never its message, which may quote content.
 */
public final class JsonLineLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

    static final String BASE_PACKAGE = "io.github.gmshield";
    static final String UNIT_APPLICATION = "application";
    static final String UNIT_FRAMEWORK = "framework";
    static final String EVENT_FRAMEWORK = "framework.log";
    private static final int MAX_CAUSES = 5;

    private final JsonWriter<Map<String, Object>> writer = JsonWriter.standard();

    @Override
    public String format(ILoggingEvent event) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("timestamp", event.getInstant().toString());
        line.put("level", event.getLevel().toString());
        String traceId = event.getMDCPropertyMap().get(TraceContext.KEY);
        line.put(TraceContext.KEY, traceId == null ? TraceContext.NONE : traceId);
        String logger = event.getLoggerName();
        if (isOwn(logger)) {
            line.put("unit", unitOf(logger));
            line.put("event", event.getMessage());
            List<KeyValuePair> pairs = event.getKeyValuePairs();
            if (pairs != null) {
                for (KeyValuePair pair : pairs) {
                    line.putIfAbsent(pair.key, pair.value instanceof Number || pair.value instanceof Boolean
                            ? pair.value : String.valueOf(pair.value));
                }
            }
        } else {
            line.put("unit", UNIT_FRAMEWORK);
            line.put("event", EVENT_FRAMEWORK);
            line.put("logger", logger);
            line.put("message", event.getFormattedMessage());
        }
        IThrowableProxy thrown = event.getThrowableProxy();
        if (thrown != null) {
            line.putIfAbsent("errorType", thrown.getClassName());
            String thrownAt = firstFrame(thrown);
            if (thrownAt != null) {
                line.putIfAbsent("errorAt", thrownAt);
            }
            List<String> causes = new ArrayList<>();
            for (IThrowableProxy cause = thrown.getCause(); cause != null && causes.size() < MAX_CAUSES;
                    cause = cause.getCause()) {
                causes.add(cause.getClassName());
            }
            if (!causes.isEmpty()) {
                line.putIfAbsent("errorCauses", causes);
            }
        }
        return writer.writeToString(line) + "\n";
    }

    private static boolean isOwn(String logger) {
        return logger != null && (logger.equals(BASE_PACKAGE) || logger.startsWith(BASE_PACKAGE + "."));
    }

    /** {@code io.github.gmshield.shared.web.TraceIdFilter} is the unit {@code shared.web}. */
    static String unitOf(String logger) {
        String[] parts = logger.substring(Math.min(logger.length(), BASE_PACKAGE.length() + 1)).split("\\.");
        List<String> packages = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || Character.isUpperCase(part.charAt(0)) || packages.size() == 2) {
                break;
            }
            packages.add(part);
        }
        return packages.isEmpty() ? UNIT_APPLICATION : String.join(".", packages);
    }

    private static String firstFrame(IThrowableProxy thrown) {
        StackTraceElementProxy[] frames = thrown.getStackTraceElementProxyArray();
        if (frames == null || frames.length == 0) {
            return null;
        }
        StackTraceElement frame = frames[0].getStackTraceElement();
        return frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
    }
}
