package io.github.gmshield;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.gmshield.shared.service.JsonLineLogFormatter;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/** The error shape, the trace id and the log line of a failure (I/O matrix of Story 1.1). */
class ErrorAndTraceTest extends HttpTestSupport {

    private static final Set<String> PROBLEM_FIELDS =
            Set.of("type", "title", "status", "category", "code", "params", "traceId");
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void captureLog() {
        logged.start();
        rootLogger.addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        rootLogger.detachAppender(logged);
        logged.stop();
    }

    @Test
    void unknownPathIsANotFoundProblem() throws Exception {
        HttpResponse<String> response = get("/api/v1/nope");

        assertProblem(response, 404, "request", "request.not_found");
        assertNothingInternal(response.body());
    }

    @Test
    void unknownPathOutsideTheApisIsTheSameProblem() throws Exception {
        assertProblem(get("/nope"), 404, "request", "request.not_found");
        assertProblem(get("/error"), 404, "request", "request.not_found");
    }

    @Test
    void wrongMethodIsAProblemToo() throws Exception {
        HttpResponse<String> response = send("DELETE", "/api/v1/version");

        assertProblem(response, 405, "request", "request.method_not_allowed");
        assertThat(response.headers().firstValue("Allow")).isPresent();
    }

    @Test
    void knownProblemOfAModuleCarriesItsCodeAndParams() throws Exception {
        HttpResponse<String> response = get("/test-only/known");

        JsonNode problem = assertProblem(response, 409, "library", "library.material_locked");
        assertThat(problem.path("title").asString()).isEqualTo("Material locked");
        assertThat(fieldNames(problem.path("params"))).containsExactly("limit", "materialId");
        assertThat(problem.path("params").path("materialId").asString()).isEqualTo("0195-id");
        assertThat(problem.path("params").path("limit").asInt()).isEqualTo(3);
    }

    @Test
    void valueOfTheWrongTypeIsAnInvalidRequest() throws Exception {
        assertThat(get("/test-only/number/12").statusCode()).isEqualTo(200);

        HttpResponse<String> response = get("/test-only/number/twelve");

        assertProblem(response, 400, "request", "request.invalid");
        assertNothingInternal(response.body());
        assertThat(response.body()).doesNotContain("twelve");
    }

    @Test
    void completedRequestIsLoggedByRouteAndStatusNeverByPathOrQuery() throws Exception {
        get("/api/v1/nope?q=do-not-log");

        JsonNode completed = logLines().stream()
                .filter(line -> line.path("event").asString().equals("request.completed")).findFirst().orElseThrow();
        assertThat(completed.path("level").asString()).isEqualTo("INFO");
        assertThat(completed.path("unit").asString()).isEqualTo("shared.web");
        assertThat(completed.path("method").asString()).isEqualTo("GET");
        assertThat(completed.path("route").asString()).isEqualTo("unmatched");
        assertThat(completed.path("status").asInt()).isEqualTo(404);
        assertThat(completed.path("durationMs").isNumber()).isTrue();
        assertThat(logLines().toString()).doesNotContain("do-not-log").doesNotContain("nope");
    }

    @Test
    void healthProbeIsNotLoggedAtInfo() throws Exception {
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
        get("/api/v1/version");

        List<JsonNode> completed = logLines().stream()
                .filter(line -> line.path("event").asString().equals("request.completed")).toList();
        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).path("route").asString()).isEqualTo("/api/v1/version");
    }

    @Test
    void exceptionThatLeavesTheFilterChainIsAServerProblemLoggedWithStatus500() throws Exception {
        HttpResponse<String> response = get(ThrowingTestController.FILTER_FAILURE_PATH);

        JsonNode problem = assertProblem(response, 500, "server", "server.unexpected");
        assertNothingInternal(response.body());
        String traceId = problem.path("traceId").asString();

        List<JsonNode> lines = logLines();
        JsonNode completed = lines.stream()
                .filter(line -> line.path("event").asString().equals("request.completed")).findFirst().orElseThrow();
        assertThat(completed.path("status").asInt()).isEqualTo(500);
        assertThat(completed.path("traceId").asString()).isEqualTo(traceId);
        JsonNode failure = lines.stream()
                .filter(line -> line.path("event").asString().equals("request.failed")).findFirst().orElseThrow();
        assertThat(failure.path("traceId").asString()).isEqualTo(traceId);
        assertThat(failure.path("unit").asString()).isEqualTo("shared.web");
        assertThat(failure.path("code").asString()).isEqualTo("server.unexpected");
        assertThat(failure.path("errorType").asString()).isNotEmpty();
        assertThat(failure.path("errorAt").asString()).isNotEmpty();
        assertThat(lines.toString()).doesNotContain("do-not-leak").doesNotContain("select *");
    }

    @Test
    void requestTheServerItselfRefusesIsAProblemToo() throws Exception {
        String answer = raw("GET /a[b HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        assertThat(answer).startsWith("HTTP/1.1 400").containsIgnoringCase("content-type: application/problem+json")
                .containsIgnoringCase("cache-control: no-store");
        JsonNode problem = JSON.readTree(answer.substring(answer.indexOf('{'), answer.lastIndexOf('}') + 1));
        assertThat(fieldNames(problem)).containsExactlyInAnyOrderElementsOf(PROBLEM_FIELDS);
        assertThat(problem.path("code").asString()).isEqualTo("request.invalid");
        assertThat(answer).doesNotContain("<html").doesNotContain("Tomcat");
    }

    @Test
    void unexpectedFailureIsAServerProblemWithTheTraceIdOfItsLogLine() throws Exception {
        HttpResponse<String> response = get("/test-only/unexpected");

        JsonNode problem = assertProblem(response, 500, "server", "server.unexpected");
        assertNothingInternal(response.body());

        List<JsonNode> lines = logLines();
        JsonNode failure = lines.stream().filter(line -> line.path("event").asString().equals("request.failed"))
                .findFirst().orElseThrow();
        assertThat(failure.path("traceId").asString()).isEqualTo(problem.path("traceId").asString());
        assertThat(failure.path("unit").asString()).isEqualTo("shared.web");
        assertThat(failure.path("level").asString()).isEqualTo("ERROR");
        // The cause is logged by its type and the place it was thrown, never by its message.
        assertThat(failure.path("code").asString()).isEqualTo("server.unexpected");
        assertThat(failure.path("errorType").asString()).isEqualTo(IllegalStateException.class.getName());
        assertThat(failure.path("errorAt").asString()).startsWith(ThrowingTestController.class.getName() + ".unexpected:");
        assertThat(lines.toString()).doesNotContain("do-not-leak").doesNotContain("select *");
    }

    @Test
    void validTraceparentIsCarriedIntoTheBodyAndTheLog() throws Exception {
        HttpResponse<String> response =
                get("/test-only/unexpected", "traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01");

        assertThat(json(response).path("traceId").asString()).isEqualTo(TRACE_ID);
        assertThat(logLines()).isNotEmpty().allSatisfy(line -> {
            if (line.path("unit").asString().startsWith("shared")) {
                assertThat(line.path("traceId").asString()).isEqualTo(TRACE_ID);
            }
        });
        assertThat(logLines().stream().map(line -> line.path("event").asString()))
                .contains("request.failed", "request.completed");
    }

    @Test
    void malformedTraceparentGetsANewTraceId() throws Exception {
        for (String header : List.of(
                "garbage", "00-" + "0".repeat(32) + "-00f067aa0ba902b7-01", "00-" + TRACE_ID + "-" + "0".repeat(16) + "-01",
                "ff-" + TRACE_ID + "-00f067aa0ba902b7-01", "00-" + TRACE_ID.toUpperCase() + "-00f067aa0ba902b7-01")) {
            String traceId = json(get("/api/v1/nope", "traceparent", header)).path("traceId").asString();

            assertThat(traceId).matches("[0-9a-f]{32}").isNotEqualTo(TRACE_ID).isNotEqualTo("0".repeat(32));
        }
    }

    @Test
    void everyLogLineIsOneJsonObjectWithTraceIdUnitAndEvent() throws Exception {
        get("/api/v1/version");
        get("/test-only/unexpected");
        LoggerFactory.getLogger("org.example.library").warn("a library line with \"quotes\"\nand a second line");

        JsonLineLogFormatter formatter = new JsonLineLogFormatter();
        assertThat(logged.list).isNotEmpty().allSatisfy(event -> {
            String line = formatter.format(event);
            assertThat(line).endsWith("\n");
            assertThat(line.strip()).doesNotContain("\n");
            JsonNode parsed = JSON.readTree(line);
            assertThat(parsed.isObject()).isTrue();
            assertThat(parsed.path("timestamp").asString()).isNotEmpty();
            assertThat(parsed.path("traceId").asString()).matches("[0-9a-f]{32}");
            assertThat(parsed.path("unit").asString()).isNotEmpty();
            assertThat(parsed.path("event").asString()).isNotEmpty();
        });
    }

    private List<JsonNode> logLines() {
        JsonLineLogFormatter formatter = new JsonLineLogFormatter();
        return List.copyOf(logged.list).stream().map(event -> JSON.readTree(formatter.format(event))).toList();
    }

    private static JsonNode assertProblem(HttpResponse<String> response, int status, String category, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode problem = json(response);
        assertThat(fieldNames(problem)).containsExactlyInAnyOrderElementsOf(PROBLEM_FIELDS);
        assertThat(problem.path("type").asString()).isEqualTo("urn:gmshield:problem:" + code);
        assertThat(problem.path("title").asString()).isNotEmpty();
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        assertThat(problem.path("category").asString()).isEqualTo(category);
        assertThat(problem.path("code").asString()).isEqualTo(code);
        assertThat(problem.path("params").isObject()).isTrue();
        assertThat(problem.path("traceId").asString()).matches("[0-9a-f]{32}");
        return problem;
    }

    private static Set<String> fieldNames(JsonNode node) {
        return new TreeSet<>(node.propertyNames());
    }

    private static void assertNothingInternal(String body) {
        assertThat(body)
                .doesNotContain("Exception").doesNotContain("io.github").doesNotContain("org.springframework")
                .doesNotContain("java.").doesNotContain("\tat ").doesNotContainIgnoringCase("select ")
                .doesNotContain("do-not-leak").doesNotContain("nope");
    }
}
