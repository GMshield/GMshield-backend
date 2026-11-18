package io.github.gmshield.materials.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.materials.data.UploadRow;
import io.github.gmshield.materials.data.UploadStorage;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.FileStore;
import io.github.gmshield.vault.service.Vault;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 1.13 against a real PostgreSQL (test mode: postgres:5432): the three tables and what they store, a
 * multi-part upload whose pieces are read back and decrypted to the original bytes, the lease, completion and its
 * mismatch, a refused format that leaves nothing, the sweep's three steps resumed after a stop at each step, and
 * another owner's upload through HTTP. The sweep is driven by the test, on a clock the test moves.
 *
 * <p>The raw rows are read here with SQL to prove what is stored; the application never reads {@code material} or
 * {@code material_version} with native SQL.
 */
// Both sweeps are off: the tests drive the upload sweep, and JobIntegrationTest the job sweep.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
            "gmshield.materials.upload-sweep.enabled=false",
            "gmshield.materials.version-removal-sweep.enabled=false",
            "gmshield.files.dir=${java.io.tmpdir}/gmshield-upload-it"})
@ActiveProfiles("test-mode")
class UploadIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CREDENTIAL = "gmshield-test-mode-frontend-credential-not-a-secret";
    private static final int MIB = 1024 * 1024;

    /** A clock the test moves. */
    static final class TestClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private Uploads uploads;
    @Autowired
    private UploadSweep sweep;
    @Autowired
    private UploadStorage storage;
    @Autowired
    private FileStore files;
    @Autowired
    private Vault vault;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private TestClock clock;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void aFileInThreePartsIsStoredOnlyEncryptedAndReadsBackWholeAndItsVersionWaits() throws Exception {
        UUID owner = Ids.newId();
        byte[] content = pdf(9 * MIB + 321);
        UUID id = as(owner, () -> uploads.create(content.length, "Livro do Mestre.pdf")).uploadId();
        Map<String, Object> created = row("select * from upload where id = ?", id);
        UUID versionId = (UUID) created.get("version_id");
        UUID materialId = jdbc.sql("select material_id from material_version where id = ?").param(versionId)
                .query(UUID.class).single();
        assertThat(created.get("owner_id")).isEqualTo(owner);
        assertThat(created.get("state")).isEqualTo("open");
        assertThat(created.get("format")).isNull();
        assertThat(state(versionId)).isEqualTo("uploading");
        byte[] name = jdbc.sql("select name from material where id = ? and owner_id = ?").param(materialId).param(owner)
                .query(byte[].class).single();
        assertThat(new String(name, StandardCharsets.ISO_8859_1)).doesNotContain("Livro");
        assertThat(vault.decryptText(new Binding("material", "name", materialId, owner), name)).isEqualTo("Livro do Mestre.pdf");

        int[] cuts = {0, 4 * MIB + 3, 8 * MIB + 9, content.length};
        for (int i = 0; i < 3; i++) {
            byte[] part = Arrays.copyOfRange(content, cuts[i], cuts[i + 1]);
            long start = cuts[i];
            assertThat(as(owner, () -> uploads.patch(id, start, part.length, sha256(part), new ByteArrayInputStream(part)))
                    .offset()).isEqualTo(cuts[i + 1]);
            assertThat(row("select upload_offset from upload where id = ?", id).get("upload_offset"))
                    .isEqualTo((long) cuts[i + 1]);
        }
        int pieces = ((Number) row("select piece_count from upload where id = ?", id).get("piece_count")).intValue();
        assertThat(pieces).isEqualTo(5);
        ByteArrayOutputStream stored = new ByteArrayOutputStream();
        for (int index = 0; index < pieces; index++) {
            stored.writeBytes(files.readPiece(owner, id, index));
        }
        assertThat(stored.toByteArray()).isEqualTo(content);

        Uploads.Completed completed = as(owner, () -> uploads.complete(id, sha256(content)));

        assertThat(completed).isEqualTo(new Uploads.Completed(materialId, versionId, "waiting"));
        Map<String, Object> version = row("select state, format, size_bytes from material_version where id = ?", versionId);
        assertThat(version).containsEntry("state", "waiting").containsEntry("format", "pdf")
                .containsEntry("size_bytes", (long) content.length);
        Map<String, Object> upload = row("select state, busy_token, format from upload where id = ?", id);
        assertThat(upload).containsEntry("state", "complete").containsEntry("format", "pdf");
        assertThat(upload.get("busy_token")).isNull();
        // No column of the three tables holds the name or the file's hash in clear.
        String dump = jdbc.sql("select concat_ws('|', u::text, v::text, m::text) from upload u join material_version v"
                + " on v.id = u.version_id join material m on m.id = v.material_id where u.id = ?").param(id)
                .query(String.class).single();
        assertThat(dump).doesNotContain("Livro").doesNotContain(java.util.HexFormat.of().formatHex(sha256(content)));
    }

    @Test
    void oneLeaseAtATimeTwoConcurrentPartsGiveOneSuccessAndOneBusy() throws Exception {
        UUID owner = Ids.newId();
        byte[] content = pdf(8 * MIB);
        UUID id = as(owner, () -> uploads.create(content.length, "book.pdf")).uploadId();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Uploads.Progress> slow = threads.submit(() -> as(owner, () -> uploads.patch(id, 0, content.length,
                    sha256(content), new java.io.FilterInputStream(new ByteArrayInputStream(content)) {
                        private boolean waited;

                        @Override
                        public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
                            if (!waited) {
                                waited = true;
                                firstStarted.countDown();
                                try {
                                    release.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            return super.read(buffer, offset, length);
                        }
                    })));
            firstStarted.await();
            Future<Uploads.Progress> second = threads.submit(() -> as(owner, () -> uploads.patch(id, 0, content.length,
                    sha256(content), new ByteArrayInputStream(content))));
            assertThatThrownBy(second::get).hasCauseInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(((ApiException) thrown.getCause()).problem().code()).isEqualTo("upload.busy"));
            assertThat(row("select busy_token from upload where id = ?", id).get("busy_token")).isNotNull();
            release.countDown();
            assertThat(slow.get().offset()).isEqualTo(content.length);
        } finally {
            threads.shutdownNow();
        }
        assertThat(row("select busy_token, busy_until from upload where id = ?", id).values()).containsOnlyNulls();

        // A lease that lapsed (its holder vanished) is taken over.
        UUID other = as(owner, () -> uploads.create(content.length, "other.pdf")).uploadId();
        assertThat(storage.joined().takeLease(other, owner, 0, UUID.randomUUID(), clock.instant().plus(Duration.ofMinutes(2)),
                clock.instant())).isTrue();
        assertThatThrownBy(() -> as(owner, () -> uploads.patch(other, 0, content.length, sha256(content),
                new ByteArrayInputStream(content)))).satisfies(code("upload.busy"));
        // Postgres keeps microseconds, rounded: a second more than the lease is surely past it.
        clock.advance(Duration.ofMinutes(2).plusSeconds(1));
        assertThat(as(owner, () -> uploads.patch(other, 0, content.length, sha256(content),
                new ByteArrayInputStream(content))).offset()).isEqualTo(content.length);
    }

    @Test
    void aRefusedFormatAndAWrongWholeFileChecksumLeaveNoPieceBehind() throws Exception {
        UUID owner = Ids.newId();
        byte[] zip = new byte[] {'P', 'K', 3, 4, 20, 0, 0, 0, 8, 0, 0, 0};
        UUID refused = as(owner, () -> uploads.create(zip.length, "rules.md")).uploadId();
        UUID refusedVersion = (UUID) row("select version_id from upload where id = ?", refused).get("version_id");
        assertThatThrownBy(() -> as(owner, () -> uploads.patch(refused, 0, zip.length, sha256(zip),
                new ByteArrayInputStream(zip)))).satisfies(code("upload.format_refused"));
        assertThat(count("select count(*) from upload where id = ?", refused)).isZero();
        assertThat(count("select count(*) from material_version where id = ?", refusedVersion)).isZero();
        assertThat(count("select count(*) from material where owner_id = ?", owner)).isZero();
        assertThat(files.fileExists(owner, refused)).isFalse();

        byte[] text = "Regras: role 2d6 e some o bônus.\n".getBytes(StandardCharsets.UTF_8);
        UUID id = as(owner, () -> uploads.create(text.length, "regras.txt")).uploadId();
        UUID versionId = (UUID) row("select version_id from upload where id = ?", id).get("version_id");
        as(owner, () -> uploads.patch(id, 0, text.length, sha256(text), new ByteArrayInputStream(text)));
        assertThat(files.fileExists(owner, id)).isTrue();
        assertThatThrownBy(() -> as(owner, () -> uploads.complete(id, sha256("other".getBytes(StandardCharsets.UTF_8)))))
                .satisfies(code("upload.file_checksum_mismatch"));
        assertThat(state(versionId)).isEqualTo("discarded");
        assertThat(count("select count(*) from upload where id = ?", id)).isZero();
        assertThat(files.fileExists(owner, id)).isFalse();
    }

    @Test
    void theSweepDiscardsAnExpiredUploadAndFinishesARemovalStoppedAfterAnyStep() throws Exception {
        UUID owner = Ids.newId();
        byte[] part = pdf(4096);   // the first 4 KiB of a 5000-byte file: enough to tell its format
        UUID[] ids = new UUID[3];
        UUID[] versions = new UUID[3];
        for (int i = 0; i < 3; i++) {
            UUID id = as(owner, () -> uploads.create(5000, "book.pdf")).uploadId();
            as(owner, () -> uploads.patch(id, 0, part.length, sha256(part), new ByteArrayInputStream(part)));
            ids[i] = id;
            versions[i] = (UUID) row("select version_id from upload where id = ?", id).get("version_id");
        }
        clock.advance(Duration.ofHours(6));
        assertThatThrownBy(() -> as(owner, () -> uploads.head(ids[0]))).satisfies(code("upload.expired"));
        Instant now = clock.instant();

        // ids[1] stopped after step 1, ids[2] after step 2.
        assertThat(sweep.start(storage.joined().find(ids[1]).orElseThrow(), now)).isTrue();
        assertThat(sweep.start(storage.joined().find(ids[2]).orElseThrow(), now)).isTrue();
        files.deleteFile(owner, ids[2]);
        assertThat(row("select state from upload where id = ?", ids[1]).get("state")).isEqualTo("removing");
        assertThat(state(versions[1])).isEqualTo("discarded");
        assertThat(files.fileExists(owner, ids[1])).isTrue();
        // Step 1 again is a no-op: the upload is no longer open.
        assertThat(sweep.start(storage.joined().find(ids[1]).orElseThrow(), now)).isFalse();

        assertThat(sweep.sweep()).isGreaterThanOrEqualTo(3);

        for (int i = 0; i < 3; i++) {
            assertThat(count("select count(*) from upload where id = ?", ids[i])).as("upload %d", i).isZero();
            assertThat(state(versions[i])).as("version %d", i).isEqualTo("discarded");
            assertThat(files.fileExists(owner, ids[i])).as("files %d", i).isFalse();
        }
        assertThat(count("select count(*) from upload where state = 'removing' and owner_id = ?", owner)).isZero();
    }

    @Test
    void anotherOwnersUploadIsNotFoundLikeAnUnknownOneThroughHttp() throws Exception {
        String a = signUp();
        String b = signUp();
        byte[] content = pdf(1000);
        HttpResponse<String> created = send("POST", "/api/v1/uploads", null, a, "Upload-Length", "1000",
                "Upload-Metadata", "filename " + Base64.getEncoder().encodeToString("segredo.pdf".getBytes(StandardCharsets.UTF_8)));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String location = created.headers().firstValue("Location").orElseThrow();

        JsonNode unknown = json(send("PATCH", "/api/v1/uploads/" + Ids.newId(), content, b, part(0, content)));
        HttpResponse<String> crossOwner = send("PATCH", location, content, b, part(0, content));
        assertThat(crossOwner.statusCode()).isEqualTo(404);
        assertThat(withoutTraceId(json(crossOwner))).isEqualTo(withoutTraceId(unknown));
        assertThat(send("HEAD", location, null, b).statusCode()).isEqualTo(404);
        assertThat(send("HEAD", location, null, a).headers().firstValue("Upload-Offset")).contains("0");
        assertThat(send("PATCH", location, content, a, part(0, content)).statusCode()).isEqualTo(204);
        HttpResponse<String> done = send("POST", location + "/completion",
                ("{\"sha256\":\"" + java.util.HexFormat.of().formatHex(sha256(content)) + "\"}").getBytes(StandardCharsets.UTF_8),
                a, "Content-Type", "application/json");
        assertThat(done.statusCode()).as(done.body()).isEqualTo(201);
        assertThat(json(done).path("state").asString()).isEqualTo("waiting");
    }

    // --- helpers ---

    private static String[] part(long offset, byte[] content) throws Exception {
        return new String[] {"Content-Type", "application/offset+octet-stream", "Upload-Offset", Long.toString(offset),
            "Upload-Checksum", "sha256 " + Base64.getEncoder().encodeToString(sha256(content))};
    }

    private HttpResponse<String> send(String method, String path, byte[] body, String session, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + CREDENTIAL).header("Tus-Resumable", "1.0.0")
                .header("X-Session-Token", session)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A new account's session token, through the stand-in for Google. */
    private String signUp() throws Exception {
        String sub = "it-upl-" + UUID.randomUUID();
        JsonNode started = json(post("/api/v1/sign-in/google", null));
        String state = null;
        for (String pair : URI.create(started.path("authorizationUrl").asString()).getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals("state")) {
                state = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put("attemptToken", started.path("attemptToken").asString());
        callback.put("state", state);
        callback.put("code", "stand-in." + sub + ".gm@example.test");
        assertThat(json(post("/api/v1/sign-in/google/callback", callback)).path("outcome").asString())
                .isEqualTo("username_required");
        Map<String, Object> complete = new LinkedHashMap<>();
        complete.put("attemptToken", started.path("attemptToken").asString());
        complete.put("username", "u" + sub.substring(sub.length() - 12).replace("-", "_"));
        complete.put("termsAccepted", true);
        HttpResponse<String> response = post("/api/v1/sign-in/complete", complete);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response).path("sessionToken").asString();
    }

    private HttpResponse<String> post(String path, Map<String, Object> body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + CREDENTIAL);
        if (body == null) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String state(UUID versionId) {
        return jdbc.sql("select state from material_version where id = ?").param(versionId).query(String.class).single();
    }

    private Map<String, Object> row(String sql, UUID id) {
        return jdbc.sql(sql).param(id).query().singleRow();
    }

    private long count(String sql, UUID id) {
        return jdbc.sql(sql).param(id).query(Long.class).single();
    }

    private static <T> T as(UUID owner, Callable<T> work) throws Exception {
        return OwnerContext.callAs(owner, work::call);
    }

    private static java.util.function.Consumer<Throwable> code(String expected) {
        return thrown -> {
            assertThat(thrown).isInstanceOf(ApiException.class);
            assertThat(((ApiException) thrown).problem().code()).isEqualTo(expected);
        };
    }

    private static Map<String, Object> withoutTraceId(JsonNode problem) {
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = JSON.convertValue(problem, Map.class);
        fields.remove("traceId");
        return fields;
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static byte[] pdf(int size) {
        byte[] content = new byte[size];
        byte[] head = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(head, 0, content, 0, head.length);
        for (int i = head.length; i < size; i++) {
            content[i] = (byte) (i * 13 + (i >>> 10));
        }
        return content;
    }

    private static byte[] sha256(byte[] content) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(content);
    }
}
