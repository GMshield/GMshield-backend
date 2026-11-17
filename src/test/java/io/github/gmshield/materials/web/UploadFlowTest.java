package io.github.gmshield.materials.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.accounts.data.AccountRow;
import io.github.gmshield.accounts.data.AccountStore;
import io.github.gmshield.accounts.data.InMemoryAccountStore;
import io.github.gmshield.accounts.data.InMemorySessionStore;
import io.github.gmshield.accounts.data.InMemorySignInAttemptStore;
import io.github.gmshield.accounts.data.SessionStore;
import io.github.gmshield.accounts.data.SignInAttemptStore;
import io.github.gmshield.audit.data.AuditStore;
import io.github.gmshield.audit.data.InMemoryAuditStore;
import io.github.gmshield.library.data.LibraryTestStores;
import io.github.gmshield.materials.data.InMemoryMaterialStore;
import io.github.gmshield.materials.data.InMemoryUploadStorage;
import io.github.gmshield.materials.data.MaterialTestStores;
import io.github.gmshield.materials.data.UploadRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.data.DataKeyStore;
import io.github.gmshield.vault.data.InMemoryDataKeyStore;
import io.github.gmshield.vault.service.FileStore;
import io.github.gmshield.vault.service.Vault;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The upload routes over real HTTP, without a database (Story 1.13): the tus headers on every answer, the version
 * check, a whole upload in two parts and its completion, the session, and another owner's upload, which answers
 * exactly like an unknown or a malformed id.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GMSHIELD_MODE=test")
@ActiveProfiles({"test-mode", "unit"})
class UploadFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CREDENTIAL = "gmshield-test-mode-frontend-credential-not-a-secret";
    private static final String TUS = "1.0.0";

    @TestConfiguration
    @Import({LibraryTestStores.class, MaterialTestStores.class})
    static class Stores {
        @Bean
        @Primary
        DataKeyStore inMemoryDataKeys() {
            return new InMemoryDataKeyStore();
        }

        @Bean
        @Primary
        AccountStore inMemoryAccounts() {
            return new InMemoryAccountStore();
        }

        @Bean
        @Primary
        SignInAttemptStore inMemoryAttempts() {
            return new InMemorySignInAttemptStore();
        }

        @Bean
        @Primary
        SessionStore inMemorySessions() {
            return new InMemorySessionStore(java.time.Clock.systemUTC());
        }

        @Bean
        @Primary
        AuditStore inMemoryAudit() {
            return new InMemoryAuditStore();
        }
    }

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private InMemoryAccountStore accounts;
    @Autowired
    private InMemoryUploadStorage uploads;
    @Autowired
    private InMemoryMaterialStore materials;
    @Autowired
    private FileStore files;
    @Autowired
    private Vault vault;

    private final HttpClient client = HttpClient.newHttpClient();

    record Gm(UUID id, String session) {
    }

    @Test
    void aRequestWithoutTusResumableOneZeroZeroIsRefusedWithTheVersionTheServerSpeaks() throws Exception {
        Gm gm = signUp();
        for (String version : new String[] {null, "", "0.2.2", "1.0"}) {
            List<String> headers = new ArrayList<>(List.of("X-Session-Token", gm.session(), "Upload-Length", "10",
                    "Upload-Metadata", metadata("a.txt")));
            if (version != null && !version.isEmpty()) {
                headers.addAll(List.of("Tus-Resumable", version));
            }
            HttpResponse<String> refused = send("POST", "/api/v1/uploads", null, headers.toArray(String[]::new));
            assertThat(refused.statusCode()).as(version).isEqualTo(412);
            assertThat(json(refused).path("code").asString()).isEqualTo("upload.tus_version");
            assertThat(refused.headers().firstValue("Tus-Version")).contains(TUS);
            assertThat(refused.headers().firstValue("Tus-Resumable")).contains(TUS);
            assertThat(refused.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        }
        assertThat(uploads.rows.values()).noneMatch(row -> row.ownerId().equals(gm.id()));
    }

    @Test
    void theUploadRoutesNeedASession() throws Exception {
        HttpResponse<String> refused = send("POST", "/api/v1/uploads", null, "Tus-Resumable", TUS, "Upload-Length", "10",
                "Upload-Metadata", metadata("a.txt"));
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(json(refused).path("code").asString()).isEqualTo("session.required");
        assertThat(refused.headers().firstValue("Tus-Resumable")).contains(TUS);
    }

    @Test
    void aFileUploadedInTwoPartsIsVerifiedAndItsVersionWaits() throws Exception {
        Gm gm = signUp();
        byte[] content = pdf(3 * 1024 * 1024 + 17);
        Instant before = Instant.now();

        HttpResponse<String> created = create(gm, content.length, "Livro básico.pdf");

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String location = created.headers().firstValue("Location").orElseThrow();
        assertThat(location).matches("/api/v1/uploads/[0-9a-f-]{36}");
        assertThat(created.headers().firstValue("Tus-Resumable")).contains(TUS);
        assertThat(created.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        String expires = created.headers().firstValue("Upload-Expires").orElseThrow();
        assertThat(expires).matches("[A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT");
        Instant expiresAt = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(expires, Instant::from);
        assertThat(expiresAt).isBetween(before.plusSeconds(6 * 3600 - 1), Instant.now().plusSeconds(6 * 3600 + 1));
        UUID id = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        assertThat(materials.versions.get(uploads.row(id).versionId()).state()).isEqualTo("uploading");

        HttpResponse<String> head = head(gm, location);
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.headers().firstValue("Upload-Offset")).contains("0");
        assertThat(head.headers().firstValue("Upload-Length")).contains(Integer.toString(content.length));
        assertThat(head.headers().firstValue("Upload-Expires")).contains(expires);
        assertThat(head.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(head.headers().firstValue("Tus-Resumable")).contains(TUS);

        int half = 2 * 1024 * 1024 + 5;
        HttpResponse<String> first = patch(gm, location, 0, java.util.Arrays.copyOf(content, half));
        assertThat(first.statusCode()).as(first.body()).isEqualTo(204);
        assertThat(first.headers().firstValue("Upload-Offset")).contains(Integer.toString(half));
        assertThat(first.headers().firstValue("Tus-Resumable")).contains(TUS);
        assertThat(head(gm, location).headers().firstValue("Upload-Offset"))
                .contains(Integer.toString(half));
        HttpResponse<String> second = patch(gm, location, half, java.util.Arrays.copyOfRange(content, half, content.length));
        assertThat(second.headers().firstValue("Upload-Offset")).contains(Integer.toString(content.length));

        HttpResponse<String> completed = complete(gm, location, sha256Hex(content));

        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(201);
        assertThat(completed.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode body = json(completed);
        VersionRow version = materials.versions.get(uploads.row(id).versionId());
        assertThat(body.path("state").asString()).isEqualTo("waiting");
        assertThat(body.path("versionId").asString()).isEqualTo(version.id().toString());
        assertThat(body.path("materialId").asString()).isEqualTo(version.materialId().toString());
        assertThat(version.format()).isEqualTo("pdf");
        assertThat(version.sizeBytes()).isEqualTo(content.length);
        assertThat(uploads.row(id).state()).isEqualTo(UploadRow.COMPLETE);
        ByteArrayOutputStream stored = new ByteArrayOutputStream();
        for (int i = 0; i < uploads.row(id).pieceCount(); i++) {
            stored.writeBytes(files.readPiece(gm.id(), id, i));
        }
        assertThat(stored.toByteArray()).isEqualTo(content);
    }

    @Test
    void problemsAreProblemDetailsWithTheTusHeaders() throws Exception {
        Gm gm = signUp();
        byte[] content = pdf(1000);
        String location = create(gm, content.length, "book.pdf").headers().firstValue("Location").orElseThrow();

        HttpResponse<String> conflict = patch(gm, location, 5, content);
        assertProblem(conflict, 409, "upload.offset_conflict");

        HttpResponse<String> mismatch = send("PATCH", location, content, session(gm), "Tus-Resumable", TUS,
                "Content-Type", "application/offset+octet-stream", "Upload-Offset", "0",
                "Upload-Checksum", "sha256 " + Base64.getEncoder().encodeToString(sha256(new byte[] {9})));
        assertProblem(mismatch, 460, "upload.part_checksum_mismatch");

        HttpResponse<String> noChecksum = send("PATCH", location, content, session(gm), "Tus-Resumable", TUS,
                "Content-Type", "application/offset+octet-stream", "Upload-Offset", "0");
        assertProblem(noChecksum, 400, "request.invalid");
        HttpResponse<String> md5 = send("PATCH", location, content, session(gm), "Tus-Resumable", TUS,
                "Content-Type", "application/offset+octet-stream", "Upload-Offset", "0",
                "Upload-Checksum", "md5 " + Base64.getEncoder().encodeToString(new byte[16]));
        assertProblem(md5, 400, "request.invalid");
        HttpResponse<String> wrongType = send("PATCH", location, content, session(gm), "Tus-Resumable", TUS,
                "Content-Type", "application/octet-stream", "Upload-Offset", "0",
                "Upload-Checksum", "sha256 " + Base64.getEncoder().encodeToString(sha256(content)));
        assertProblem(wrongType, 415, "request.unsupported_media_type");
        assertProblem(complete(gm, location, sha256Hex(content)), 409, "upload.incomplete");
        assertProblem(complete(gm, location, "not-hex"), 400, "request.invalid");

        assertProblem(create(gm, 2147483649L, "big.pdf"), 413, "upload.too_large");
        assertProblem(create(gm, 0, "empty.pdf"), 413, "upload.too_large");
        assertProblem(send("POST", "/api/v1/uploads", null, session(gm), "Tus-Resumable", TUS, "Upload-Length", "10",
                "Upload-Metadata", "name " + Base64.getEncoder().encodeToString("a.txt".getBytes(StandardCharsets.UTF_8))),
                400, "upload.metadata_invalid");
        assertProblem(send("POST", "/api/v1/uploads", null, session(gm), "Tus-Resumable", TUS, "Upload-Length", "ten",
                "Upload-Metadata", metadata("a.txt")), 400, "request.invalid");

        byte[] zip = new byte[] {'P', 'K', 3, 4, 20, 0, 0, 0, 8, 0};
        String zipLocation = create(gm, zip.length, "rules.md").headers().firstValue("Location").orElseThrow();
        HttpResponse<String> refused = patch(gm, zipLocation, 0, zip);
        assertProblem(refused, 415, "upload.format_refused");
        assertThat(json(refused).path("params").path("accepted").valueStream().map(JsonNode::asString).toList())
                .containsExactly("pdf", "txt", "markdown", "jpeg", "png", "webp", "tiff", "bmp", "heic");
        assertProblem(head(gm, zipLocation), 404, null);
    }

    @Test
    void anotherOwnersUploadAnUnknownIdAndAMalformedIdAreTheSameNotFound() throws Exception {
        Gm a = signUp();
        Gm b = signUp();
        byte[] content = pdf(1000);
        String location = create(a, content.length, "secret.pdf").headers().firstValue("Location").orElseThrow();
        UUID id = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

        JsonNode expected = json(complete(b, "/api/v1/uploads/" + UUID.randomUUID(), sha256Hex(content)));
        assertThat(expected.path("code").asString()).isEqualTo("request.not_found");
        for (String path : List.of(location, "/api/v1/uploads/" + UUID.randomUUID(), "/api/v1/uploads/not-a-uuid",
                "/api/v1/uploads/" + id.toString().toUpperCase() + "0")) {
            HttpResponse<String> head = head(b, path);
            assertThat(head.statusCode()).as(path).isEqualTo(404);
            assertThat(head.headers().firstValue("Upload-Offset")).isEmpty();
            HttpResponse<String> part = patch(b, path, 0, content);
            assertProblem(part, 404, "request.not_found");
            assertThat(withoutTraceId(json(part))).isEqualTo(withoutTraceId(expected));
            HttpResponse<String> completion = complete(b, path, sha256Hex(content));
            assertThat(withoutTraceId(json(completion))).as(path).isEqualTo(withoutTraceId(expected));
            assertThat(part.body() + completion.body()).doesNotContain(id.toString()).doesNotContain("secret");
        }
        assertThat(uploads.row(id).offset()).isZero();
        assertThat(files.fileExists(a.id(), id)).isFalse();
        assertThat(patch(a, location, 0, content).statusCode()).isEqualTo(204);
        assertThat(OwnerContext.current()).isEmpty();
    }

    // --- helpers ---

    private HttpResponse<String> create(Gm gm, long length, String name) throws Exception {
        return send("POST", "/api/v1/uploads", null, session(gm), "Tus-Resumable", TUS, "Upload-Length",
                Long.toString(length), "Upload-Metadata", metadata(name) + ",kind,language cHQtQlI=");
    }

    private HttpResponse<String> head(Gm gm, String location) throws Exception {
        return send("HEAD", location, null, session(gm), "Tus-Resumable", TUS);
    }

    private HttpResponse<String> patch(Gm gm, String location, long offset, byte[] part) throws Exception {
        return send("PATCH", location, part, session(gm), "Tus-Resumable", TUS, "Content-Type",
                "application/offset+octet-stream", "Upload-Offset", Long.toString(offset),
                "Upload-Checksum", "sha256 " + Base64.getEncoder().encodeToString(sha256(part)));
    }

    private HttpResponse<String> complete(Gm gm, String location, String hex) throws Exception {
        return send("POST", location + "/completion", ("{\"sha256\":\"" + hex + "\"}").getBytes(StandardCharsets.UTF_8),
                session(gm), "Tus-Resumable", TUS, "Content-Type", "application/json");
    }

    private static String[] session(Gm gm) {
        return new String[] {"X-Session-Token", gm.session()};
    }

    private static String metadata(String name) {
        return "filename " + Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> send(String method, String path, byte[] body, String[] first, String... more)
            throws Exception {
        String[] all = java.util.Arrays.copyOf(first, first.length + more.length);
        System.arraycopy(more, 0, all, first.length, more.length);
        return send(method, path, body, all);
    }

    private HttpResponse<String> send(String method, String path, byte[] body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + CREDENTIAL)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Tus-Resumable")).contains(TUS);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        if (code != null) {
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
            assertThat(json(response).path("code").asString()).isEqualTo(code);
            assertThat(json(response).path("status").asInt()).isEqualTo(status);
        }
    }

    private Gm signUp() throws Exception {
        String sub = "upl-" + UUID.randomUUID();
        String[] attempt = start();
        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put("attemptToken", attempt[0]);
        callback.put("state", attempt[1]);
        callback.put("code", "stand-in." + sub + ".gm@example.test");
        assertThat(json(post("/api/v1/sign-in/google/callback", callback)).path("outcome").asString())
                .isEqualTo("username_required");
        Map<String, Object> complete = new LinkedHashMap<>();
        complete.put("attemptToken", attempt[0]);
        complete.put("username", "u" + sub.substring(sub.length() - 12).replace("-", "_"));
        complete.put("termsAccepted", true);
        HttpResponse<String> response = post("/api/v1/sign-in/complete", complete);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        AccountRow row = accounts.findByGoogleIdFingerprint(vault.fingerprint("account", "google_id", sub)).orElseThrow();
        return new Gm(row.id(), json(response).path("sessionToken").asString());
    }

    private String[] start() throws Exception {
        JsonNode body = json(post("/api/v1/sign-in/google", null));
        String state = null;
        for (String pair : URI.create(body.path("authorizationUrl").asString()).getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals("state")) {
                state = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return new String[] {body.path("attemptToken").asString(), state};
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

    private static Map<String, Object> withoutTraceId(JsonNode problem) {
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = JSON.convertValue(problem, Map.class);
        fields.remove("traceId");
        return fields;
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    static byte[] pdf(int size) {
        byte[] content = new byte[size];
        byte[] head = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(head, 0, content, 0, head.length);
        for (int i = head.length; i < size; i++) {
            content[i] = (byte) (i * 7 + (i >>> 9));
        }
        return content;
    }

    static byte[] sha256(byte[] content) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(content);
    }

    static String sha256Hex(byte[] content) throws Exception {
        return HexFormat.of().formatHex(sha256(content));
    }
}
