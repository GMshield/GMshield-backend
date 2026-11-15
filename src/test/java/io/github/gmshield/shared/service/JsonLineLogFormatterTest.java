package io.github.gmshield.shared.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The shape of one log line. */
class JsonLineLogFormatterTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    private final JsonLineLogFormatter formatter = new JsonLineLogFormatter();

    @Test
    void ownEventIsWrittenWithItsUnitTraceIdAndFieldsButNeverItsArguments() {
        LoggingEvent event = event("io.github.gmshield.library.service.Materials", "material.accepted {}",
                null, "the content of an argument");
        event.setMDCPropertyMap(Map.of(TraceContext.KEY, TRACE_ID));
        event.setKeyValuePairs(List.of(
                new KeyValuePair("materialId", "0195-id"), new KeyValuePair("pages", 12),
                new KeyValuePair("unit", "an attempt to overwrite a fixed field")));

        String line = formatter.format(event);
        JsonNode parsed = parse(line);

        assertThat(parsed.propertyNames())
                .containsExactly("timestamp", "level", "traceId", "unit", "event", "materialId", "pages");
        assertThat(parsed.path("level").asString()).isEqualTo("INFO");
        assertThat(parsed.path("traceId").asString()).isEqualTo(TRACE_ID);
        assertThat(parsed.path("unit").asString()).isEqualTo("library.service");
        assertThat(parsed.path("event").asString()).isEqualTo("material.accepted {}");
        assertThat(parsed.path("pages").asInt()).isEqualTo(12);
        assertThat(line).doesNotContain("the content of an argument");
    }

    @Test
    void lineOutsideARequestCarriesTheAbsentTraceId() {
        JsonNode parsed = parse(formatter.format(event("io.github.gmshield.GmshieldApplication", "started", null)));

        assertThat(parsed.path("traceId").asString()).isEqualTo(TraceContext.NONE);
        assertThat(parsed.path("unit").asString()).isEqualTo("application");
    }

    @Test
    void libraryLineIsAFrameworkEventOnOneLine() {
        String line = formatter.format(event("org.example.Library", "first \"line\"\nsecond {}", null, "line"));
        JsonNode parsed = parse(line);

        assertThat(line.strip()).doesNotContain("\n");
        assertThat(parsed.path("unit").asString()).isEqualTo("framework");
        assertThat(parsed.path("event").asString()).isEqualTo("framework.log");
        assertThat(parsed.path("logger").asString()).isEqualTo("org.example.Library");
        assertThat(parsed.path("message").asString()).isEqualTo("first \"line\"\nsecond line");
    }

    @Test
    void exceptionIsWrittenByTypeAndPlaceNeverByMessage() {
        Exception failure = new IllegalStateException("content: the dragon's stat block",
                new java.sql.SQLException("select secret from accounts"));

        String line = formatter.format(event("io.github.gmshield.shared.web.ApiExceptionHandler", "request.failed", failure));
        JsonNode parsed = parse(line);

        assertThat(parsed.path("errorType").asString()).isEqualTo("java.lang.IllegalStateException");
        assertThat(parsed.path("errorAt").asString()).startsWith(JsonLineLogFormatterTest.class.getName() + ".");
        assertThat(parsed.path("errorCauses").valueStream().map(JsonNode::asString)).containsExactly("java.sql.SQLException");
        assertThat(line).doesNotContain("dragon").doesNotContain("select secret");
    }

    @Test
    void unitIsTheModuleAndItsPart() {
        assertThat(JsonLineLogFormatter.unitOf("io.github.gmshield.shared.web.TraceIdFilter")).isEqualTo("shared.web");
        assertThat(JsonLineLogFormatter.unitOf("io.github.gmshield.jobs.service.internal.Claims")).isEqualTo("jobs.service");
        assertThat(JsonLineLogFormatter.unitOf("io.github.gmshield.GmshieldApplication")).isEqualTo("application");
        assertThat(JsonLineLogFormatter.unitOf("io.github.gmshield")).isEqualTo("application");
    }

    private LoggingEvent event(String logger, String message, Throwable thrown, Object... arguments) {
        Logger source = context.getLogger(logger);
        return new LoggingEvent(Logger.class.getName(), source, Level.INFO, message, thrown, arguments);
    }

    private static JsonNode parse(String line) {
        assertThat(line).endsWith("\n");
        return JsonMapper.builder().build().readTree(line);
    }
}
