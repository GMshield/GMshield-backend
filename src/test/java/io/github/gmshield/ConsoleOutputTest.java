package io.github.gmshield;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/**
 * What the application really writes to its console: every line is one JSON object. This fails
 * when the console format of application.yaml (logging.structured.format.console) is removed.
 */
@ExtendWith(OutputCaptureExtension.class)
class ConsoleOutputTest extends HttpTestSupport {

    @Test
    void everyConsoleLineIsOneJsonObjectWithTraceIdUnitAndEvent(CapturedOutput output) throws Exception {
        get("/api/v1/version");
        String traceId = json(get("/test-only/unexpected")).path("traceId").asString();

        // The line of a request is written just after its answer: wait for the last one.
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (output.getOut().lines().filter(line -> line.contains("request.completed")).count() < 2
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }

        List<String> lines = output.getOut().lines().filter(line -> !line.isBlank()).toList();
        assertThat(lines).hasSizeGreaterThanOrEqualTo(3);
        List<JsonNode> parsed = lines.stream().map(JSON::readTree).toList();
        assertThat(parsed).allSatisfy(line -> {
            assertThat(line.isObject()).isTrue();
            assertThat(line.path("traceId").asString()).matches("[0-9a-f]{32}");
            assertThat(line.path("unit").asString()).isNotEmpty();
            assertThat(line.path("event").asString()).isNotEmpty();
        });
        assertThat(parsed.stream().map(line -> line.path("event").asString()))
                .contains("request.completed", "request.failed");
        assertThat(parsed.stream().filter(line -> line.path("event").asString().equals("request.failed")))
                .allSatisfy(line -> assertThat(line.path("traceId").asString()).isEqualTo(traceId));
        assertThat(output.getAll()).doesNotContain("do-not-leak");
    }
}
