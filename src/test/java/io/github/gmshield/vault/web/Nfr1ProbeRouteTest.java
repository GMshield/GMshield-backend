package io.github.gmshield.vault.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.gmshield.accounts.data.AccountRow;
import io.github.gmshield.accounts.data.AccountStore;
import io.github.gmshield.accounts.data.InMemoryAccountStore;
import io.github.gmshield.shared.service.JsonLineLogFormatter;
import io.github.gmshield.vault.data.DataKeyStore;
import io.github.gmshield.vault.data.InMemoryDataKeyStore;
import io.github.gmshield.vault.data.InMemoryProbeStore;
import io.github.gmshield.vault.data.ProbeRow;
import io.github.gmshield.vault.data.ProbeStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The NFR-1 gate's seed route, as the pipeline calls it in the staging copy (test mode). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GMSHIELD_MODE=test")
@ActiveProfiles({"test-mode", "unit"})
class Nfr1ProbeRouteTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TEXT = "GMshield NFR-1 probe 6f1c0e2a9b7d4c3e8a5f0b1d2c3e4f5a";

    @TestConfiguration
    static class Stores {
        @Bean
        @Primary
        DataKeyStore inMemoryDataKeys() {
            return new InMemoryDataKeyStore();
        }

        @Bean
        @Primary
        ProbeStore inMemoryProbes() {
            return new InMemoryProbeStore();
        }

        @Bean
        @Primary
        AccountStore inMemoryAccounts() {
            return new InMemoryAccountStore();
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Value("${gmshield.files.dir}")
    private Path filesDir;

    @Autowired
    private ProbeStore probes;

    @Autowired
    private DataKeyStore dataKeys;

    @Autowired
    private AccountStore accounts;

    private final HttpClient client = HttpClient.newHttpClient();
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void captureLog() {
        // Server and background threads log while a test reads the list: a copy-on-write list never throws
        // ConcurrentModificationException there (an ArrayList did, in a develop run of ConversationFlowTest).
        logged.list = new java.util.concurrent.CopyOnWriteArrayList<>();
        logged.start();
        root.addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        root.detachAppender(logged);
        logged.stop();
    }

    @Test
    void seedStoresTheTextEncryptedAndReadBackReturnsIt() throws Exception {
        HttpResponse<String> seeded = post(Nfr1ProbeController.PATH, "{\"text\":\"" + TEXT + "\"}");

        assertThat(seeded.statusCode()).isEqualTo(201);
        assertThat(seeded.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        UUID id = UUID.fromString(JSON.readTree(seeded.body()).path("id").asString());
        int findsAfterSeed = ((InMemoryDataKeyStore) dataKeys).finds.get();
        assertThat(findsAfterSeed).isPositive();   // the seed's own read-back reloaded the wrapped key

        HttpResponse<String> read = get(Nfr1ProbeController.PATH + "/" + id);
        // A cold cache for the probe's owner: the data key comes from the store (vault_data_key) again.
        assertThat(((InMemoryDataKeyStore) dataKeys).finds.get()).isGreaterThan(findsAfterSeed);
        assertThat(read.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(read.body());
        assertThat(body.path("text").asString()).isEqualTo(TEXT);
        assertThat(body.path("fileText").asString()).isEqualTo(TEXT);

        ProbeRow row = probes.find(id).orElseThrow();
        assertThat(new String(row.text(), StandardCharsets.ISO_8859_1)).doesNotContain(TEXT);
        Path piece = filesDir.resolve(row.ownerId().toString()).resolve(row.fileId().toString()).resolve("0.piece");
        byte[] stored = Files.readAllBytes(piece);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(TEXT)
                .doesNotContain(Base64.getEncoder().encodeToString(TEXT.getBytes(StandardCharsets.UTF_8)));

        // Story 1.9: the text is also a real account row's Google id and email address, stored encrypted.
        AccountRow account = ((InMemoryAccountStore) accounts).rows.values().stream()
                .filter(candidate -> candidate.termsVersion().endsWith("draft")).findFirst().orElseThrow();
        for (byte[] value : new byte[][] {account.googleId(), account.email(), account.username()}) {
            assertThat(new String(value, StandardCharsets.ISO_8859_1)).doesNotContain(TEXT);
        }

        JsonLineLogFormatter formatter = new JsonLineLogFormatter();
        assertThat(String.join("", logged.list.stream().map(formatter::format).toList())).doesNotContain(TEXT);
    }

    @Test
    void blankOrMissingTextIsAnInvalidRequestAndAnUnknownIdIsNotFound() throws Exception {
        assertThat(post(Nfr1ProbeController.PATH, "{\"text\":\" \"}").statusCode()).isEqualTo(400);
        assertThat(post(Nfr1ProbeController.PATH, "{}").statusCode()).isEqualTo(400);
        assertThat(post(Nfr1ProbeController.PATH, "{\"text\":\"" + "x".repeat(1025) + "\"}").statusCode()).isEqualTo(400);
        assertThat(get(Nfr1ProbeController.PATH + "/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
    }

    @Test
    void theRouteIsLeftOutOfBothOpenApiDocuments() throws Exception {
        for (String api : new String[] {"public", "internal"}) {
            HttpResponse<String> document = get("/v3/api-docs/" + api);
            assertThat(document.statusCode()).isEqualTo(200);
            assertThat(document.body()).doesNotContain("nfr1").doesNotContain("test-mode");
        }
    }

    @Test
    void testModeMigrationsAddTheProbeTableOutsideTheProductionLocation() throws Exception {
        Path main = Path.of("src", "main", "resources", "db");
        try (Stream<Path> production = Files.list(main.resolve("migration"))) {
            for (Path sql : production.toList()) {
                assertThat(Files.readString(sql)).doesNotContain("vault_probe");
            }
        }
        assertThat(Files.readString(main.resolve("test-mode-migration").resolve("R__vault_probe.sql")))
                .contains("create table if not exists vault_probe");
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
