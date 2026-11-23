package io.github.gmshield.vault.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.gmshield.GmshieldApplication;
import io.github.gmshield.HttpTestSupport;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.JsonLineLogFormatter;
import io.github.gmshield.vault.data.DataKeyStore;
import io.github.gmshield.vault.data.InMemoryDataKeyStore;
import io.github.gmshield.vault.web.IntegrityTestController;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;

/** The vault as the application wires it, and the problem a request meets on a failed check. */
class VaultSpringTest extends HttpTestSupport {

    @TestConfiguration
    static class Keys {
        @Bean
        @Primary
        DataKeyStore inMemoryDataKeys() {
            return new InMemoryDataKeyStore();
        }
    }

    @Autowired
    private Vault vault;

    @Autowired
    private FileStore files;

    @Value("${gmshield.files.dir}")
    private Path filesDir;

    @Test
    void theApplicationsVaultAndFileStoreRoundTrip() {
        UUID owner = Ids.newId();
        Binding binding = new Binding("note", "body", Ids.newId(), owner);
        UUID file = Ids.newId();

        files.writePiece(owner, file, 0, new byte[] {1, 2, 3});

        assertThat(vault.decryptText(binding, vault.encryptText(binding, "hello"))).isEqualTo("hello");
        assertThat(files.readPiece(owner, file, 0)).containsExactly(1, 2, 3);
        assertThat(Files.exists(filesDir.resolve(owner.toString()).resolve(file.toString()).resolve("0.piece"))).isTrue();
    }

    @Test
    void failedCheckIsTheIntegrityProblemWithNoContentAndALogLineWithIds() throws Exception {
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        // Server and background threads log while a test reads the list: a copy-on-write list never throws
        // ConcurrentModificationException there (an ArrayList did, in a develop run of ConversationFlowTest).
        logged.list = new java.util.concurrent.CopyOnWriteArrayList<>();
        logged.start();
        root.addAppender(logged);
        HttpResponse<String> response;
        try {
            response = get(IntegrityTestController.PATH);
        } finally {
            root.detachAppender(logged);
            logged.stop();
        }

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        JsonNode problem = json(response);
        assertThat(problem.path("code").asString()).isEqualTo("integrity.failed");
        assertThat(problem.path("category").asString()).isEqualTo("integrity");
        assertThat(problem.path("params").isEmpty()).isTrue();
        assertThat(response.body()).doesNotContain(IntegrityTestController.SECRET_TEXT).doesNotContain("note");

        JsonLineLogFormatter formatter = new JsonLineLogFormatter();
        String lines = String.join("", logged.list.stream().map(formatter::format).toList());
        assertThat(lines).contains("vault.integrity_failed").doesNotContain(IntegrityTestController.SECRET_TEXT);
        JsonNode failure = logged.list.stream().map(formatter::format).map(JSON::readTree)
                .filter(line -> line.path("event").asString().equals("vault.integrity_failed")).findFirst().orElseThrow();
        assertThat(failure.path("rowId").asString()).matches("[0-9a-f-]{36}");
        assertThat(failure.path("ownerId").asString()).matches("[0-9a-f-]{36}");
    }

    @Test
    void malformedKeysStopTheStartWithoutShowingThem() {
        String bad = "bm90LTMyLWJ5dGVz";   // "not-32-bytes"
        assertThatThrownBy(() -> new SpringApplicationBuilder(GmshieldApplication.class)
                        .profiles("unit")
                        .run("--server.port=0", "--gmshield.vault.master-key=" + bad)
                        .close())
                .hasStackTraceContaining("setting.invalid")
                .hasStackTraceContaining("GMSHIELD_VAULT_MASTER_KEY")
                .satisfies(e -> assertThat(stackTrace(e)).doesNotContain(bad));
    }

    @Test
    void theSameKeyTwiceStopsTheStartWithoutShowingIt() {
        String same = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=";   // 32 bytes of 7
        assertThatThrownBy(() -> new SpringApplicationBuilder(GmshieldApplication.class)
                        .profiles("unit")
                        .run("--server.port=0", "--gmshield.vault.master-key=" + same, "--gmshield.vault.index-key=" + same)
                        .close())
                .hasStackTraceContaining("setting.invalid")
                .satisfies(e -> assertThat(stackTrace(e)).doesNotContain(same));
    }

    @Test
    void theTestModeProfileWithoutTestModeGetsNoRandomKeys() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(GmshieldApplication.class)
                        .profiles("test-mode", "unit")
                        .properties("server.port=0")
                        .run()
                        .close())
                .hasStackTraceContaining("the test-mode profile needs GMSHIELD_MODE=test");
    }

    private static String stackTrace(Throwable e) {
        java.io.StringWriter out = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }
}
