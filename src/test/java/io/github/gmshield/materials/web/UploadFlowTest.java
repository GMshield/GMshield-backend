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
import io.github.gmshield.jobs.data.InMemoryJobStorage;
import io.github.gmshield.jobs.data.JobMemoryStores;
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
    @Import({LibraryTestStores.class, MaterialTestStores.class, JobMemoryStores.class})
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
    private InMemoryJobStorage jobs;
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
        // Story 1.15: the completion created the version's first ingestion step, for this owner only.
        assertThat(materials.steps.values()).filteredOn(step -> step.versionId().equals(version.id()))
                .singleElement().satisfies(step -> {
                    assertThat(step.ownerId()).isEqualTo(gm.id());
                    assertThat(step.stage()).isEqualTo("clean");
                    assertThat(jobs.row(step.jobId()).kind()).isEqualTo("ingestion");
                    assertThat(jobs.row(step.jobId()).ownerId()).isEqualTo(gm.id());
                    OwnerContext.runAs(UUID.randomUUID(), () -> assertThat(materials.findStepByJob(step.jobId())).isEmpty());
                });
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

    // --- Story 1.14: the details, the list and the read ---

    @Test
    void theDetailsAreStoredAndTheMaterialIsListedAndReadWithItsUploadWhileUploading() throws Exception {
        Gm gm = signUp();
        String campaign = json(get(gm, "/api/v1/campaigns/current")).path("id").asString();
        HttpResponse<String> created = send("POST", "/api/v1/uploads", null, session(gm), "Tus-Resumable", TUS,
                "Upload-Length", "1000", "Upload-Metadata", metadata("Livro do Jogador.pdf") + ",kind " + b64("rulebook")
                        + ",language " + b64("detect") + ",campaign " + b64(campaign));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String location = created.headers().firstValue("Location").orElseThrow();
        String uploadId = location.substring(location.lastIndexOf('/') + 1);

        HttpResponse<String> listed = get(gm, "/api/v1/materials?state=uploading&state=waiting&state=processing");
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        assertThat(listed.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode item = json(listed).path("items").get(0);
        assertThat(json(listed).path("items").size()).isEqualTo(1);
        assertThat(json(listed).has("nextCursor")).isFalse();
        assertThat(item.path("name").asString()).isEqualTo("Livro do Jogador.pdf");
        assertThat(item.path("state").asString()).isEqualTo("uploading");
        assertThat(item.path("kind").asString()).isEqualTo("rulebook");
        assertThat(item.path("upload").path("id").asString()).isEqualTo(uploadId);
        assertThat(item.path("upload").path("offset").asLong()).isZero();
        assertThat(item.path("upload").path("length").asLong()).isEqualTo(1000);
        assertThat(Instant.parse(item.path("upload").path("expiresAt").asString())).isAfter(Instant.now());

        byte[] content = pdf(1000);
        assertThat(patch(gm, location, 0, content).statusCode()).isEqualTo(204);
        String materialId = json(complete(gm, location, sha256Hex(content))).path("materialId").asString();
        HttpResponse<String> read = get(gm, "/api/v1/materials/" + materialId);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(json(read).path("id").asString()).isEqualTo(materialId);
        assertThat(json(read).path("state").asString()).isEqualTo("waiting");
        assertThat(json(read).has("upload")).isFalse();
        assertThat(json(get(gm, "/api/v1/materials?state=uploading")).path("items").size()).isZero();
        assertThat(json(get(gm, "/api/v1/materials?state=waiting&limit=1")).path("items").get(0).path("id").asString())
                .isEqualTo(materialId);
    }

    @Test
    void anotherOwnersMaterialIsNotFoundAndNotListedAndAnotherOwnersBelongingIsInvalidMetadata() throws Exception {
        Gm a = signUp();
        Gm b = signUp();
        String aCampaign = json(get(a, "/api/v1/campaigns/current")).path("id").asString();
        String aSystem = json(get(a, "/api/v1/campaigns/current")).path("system").path("id").asString();
        byte[] content = pdf(1000);
        String location = create(a, content.length, "secret.pdf").headers().firstValue("Location").orElseThrow();
        patch(a, location, 0, content);
        String materialId = json(complete(a, location, sha256Hex(content))).path("materialId").asString();

        JsonNode expected = json(get(b, "/api/v1/materials/" + UUID.randomUUID()));
        assertThat(expected.path("code").asString()).isEqualTo("request.not_found");
        for (String path : List.of("/api/v1/materials/" + materialId, "/api/v1/materials/not-a-uuid",
                "/api/v1/materials/" + materialId.toUpperCase() + "0")) {
            HttpResponse<String> response = get(b, path);
            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertThat(withoutTraceId(json(response))).as(path).isEqualTo(withoutTraceId(expected));
            assertThat(response.body()).doesNotContain(materialId).doesNotContain("secret");
        }
        HttpResponse<String> theirs = get(b, "/api/v1/materials?state=waiting&state=uploading");
        assertThat(json(theirs).path("items").size()).isZero();
        assertThat(theirs.body()).doesNotContain(materialId);
        assertThat(json(get(a, "/api/v1/materials?state=waiting")).path("items").size()).isEqualTo(1);

        int before = materials.materials.size();
        List<String> refused = List.of(",campaign " + b64(aCampaign), ",system " + b64(aSystem),
                ",campaign " + b64(json(get(b, "/api/v1/campaigns/current")).path("id").asString()) + ",system " + b64(
                        json(get(b, "/api/v1/campaigns/current")).path("system").path("id").asString()),
                ",kind " + b64("novel"), ",language " + b64("fr"), ",kind");
        JsonNode first = null;
        for (String details : refused) {
            HttpResponse<String> response = send("POST", "/api/v1/uploads", null, session(b), "Tus-Resumable", TUS,
                    "Upload-Length", "10", "Upload-Metadata", metadata("a.txt") + details);
            assertProblem(response, 400, "upload.metadata_invalid");
            assertThat(response.body()).doesNotContain(aCampaign).doesNotContain(aSystem);
            first = first == null ? json(response) : first;
            assertThat(withoutTraceId(json(response))).as(details).isEqualTo(withoutTraceId(first));
        }
        assertThat(materials.materials).hasSize(before);
    }

    @Test
    void theListPagesWithItsLimitAndCursor() throws Exception {
        Gm gm = signUp();
        for (String name : List.of("a.pdf", "b.pdf", "c.pdf")) {
            assertThat(create(gm, 10, name).statusCode()).isEqualTo(201);
        }

        JsonNode first = json(get(gm, "/api/v1/materials?state=uploading&limit=2"));
        assertThat(first.path("items").size()).isEqualTo(2);
        String cursor = first.path("nextCursor").asString();
        assertThat(cursor).matches("[0-9a-f-]{36}");
        assertThat(cursor).isEqualTo(first.path("items").get(1).path("id").asString());
        JsonNode second = json(get(gm, "/api/v1/materials?state=uploading&limit=2&cursor=" + cursor));
        assertThat(second.path("items").size()).isEqualTo(1);
        assertThat(second.has("nextCursor")).isFalse();
        List<String> names = new ArrayList<>();
        for (JsonNode page : List.of(first, second)) {
            page.path("items").forEach(item -> names.add(item.path("name").asString()));
        }
        assertThat(names).containsExactlyInAnyOrder("a.pdf", "b.pdf", "c.pdf");
        // A numbered page: every page names the total for its states, and a page can start at a position.
        assertThat(first.path("total").asLong()).isEqualTo(3);
        assertThat(second.path("total").asLong()).isEqualTo(3);
        JsonNode last = json(get(gm, "/api/v1/materials?state=uploading&limit=2&offset=2"));
        assertThat(last.path("items").size()).isEqualTo(1);
        assertThat(last.path("items").get(0).path("id").asString()).isEqualTo(second.path("items").get(0).path("id").asString());
        assertThat(last.has("nextCursor")).isFalse();
        assertThat(last.path("total").asLong()).isEqualTo(3);
        JsonNode middle = json(get(gm, "/api/v1/materials?state=uploading&limit=1&offset=1"));
        assertThat(middle.path("items").get(0).path("id").asString()).isEqualTo(first.path("items").get(1).path("id").asString());
        assertThat(middle.path("nextCursor").asString()).isEqualTo(cursor);
        // Another state has its own total, and a position past the end is an empty page.
        assertThat(json(get(gm, "/api/v1/materials?state=ready")).path("total").asLong()).isZero();
        JsonNode past = json(get(gm, "/api/v1/materials?offset=7"));
        assertThat(past.path("items").size()).isZero();
        assertThat(past.path("total").asLong()).isEqualTo(3);
        for (String query : List.of("offset=-1", "offset=x", "offset=01", "offset=100001", "offset=1&cursor=" + cursor)) {
            HttpResponse<String> refused = get(gm, "/api/v1/materials?state=uploading&" + query);
            assertThat(refused.statusCode()).as(query).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).isEqualTo("request.invalid");
        }
    }

    @Test
    void theListNeedsAKnownStateALimitFromOneToFiftyAndAWellFormedCursorAndASession() throws Exception {
        Gm gm = signUp();
        for (String query : List.of("?state=", "?state=lost", "?state=waiting&state=lost",
                "?state=waiting&limit=0", "?state=waiting&limit=51", "?state=waiting&limit=x", "?state=waiting&limit=01",
                "?state=waiting&cursor=nope")) {
            HttpResponse<String> response = get(gm, "/api/v1/materials" + query);
            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(json(response).path("code").asString()).as(query).isEqualTo("request.invalid");
        }
        assertThat(get(gm, "/api/v1/materials?state=waiting&limit=50&cursor=" + UUID.randomUUID()).statusCode())
                .isEqualTo(200);
        // Story 3.3: without a state, every material.
        assertThat(get(gm, "/api/v1/materials").statusCode()).isEqualTo(200);
        assertThat(get(gm, "/api/v1/materials?limit=5").statusCode()).isEqualTo(200);
        HttpResponse<String> noSession = send("GET", "/api/v1/materials?state=waiting", null);
        assertThat(noSession.statusCode()).isEqualTo(401);
        assertThat(json(noSession).path("code").asString()).isEqualTo("session.required");
        assertThat(send("GET", "/api/v1/materials/" + UUID.randomUUID(), null).statusCode()).isEqualTo(401);
    }

    // --- Story 3.3: where a material belongs

    @Test
    void aMaterialCarriesItsVersionSizeAndPlaceAndAnUploadToTwoSystemsIsTwoRows() throws Exception {
        Gm gm = signUp();
        JsonNode current = json(get(gm, "/api/v1/campaigns/current"));
        String first = current.path("system").path("id").asString();
        String second = system(gm, "Pathfinder");
        byte[] content = pdf(1000);
        HttpResponse<String> created = send("POST", "/api/v1/uploads", null, session(gm), "Tus-Resumable", TUS,
                "Upload-Length", "1000", "Upload-Metadata", metadata("srd.pdf") + ",systems " + b64(first + "," + second));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String location = created.headers().firstValue("Location").orElseThrow();
        patch(gm, location, 0, content);
        String materialId = json(complete(gm, location, sha256Hex(content))).path("materialId").asString();

        JsonNode read = json(get(gm, "/api/v1/materials/" + materialId));

        assertThat(as(gm, () -> materials.systemsOf(UUID.fromString(materialId)))).hasSize(2);
        assertThat(read.path("version").asLong()).isZero();
        assertThat(read.path("size").asLong()).isEqualTo(1000);
        assertThat(read.path("belonging").path("campaign").isNull()).isTrue();
        List<String> names = new ArrayList<>();
        read.path("belonging").path("systems").forEach(place -> names.add(place.path("name").asString()));
        assertThat(names).containsExactlyInAnyOrder(current.path("system").path("name").asString(), "Pathfinder");
        // A campaign's material names the campaign and its system; one with no place names neither.
        String campaign = current.path("id").asString();
        HttpResponse<String> ofCampaign = send("POST", "/api/v1/uploads", null, session(gm), "Tus-Resumable", TUS,
                "Upload-Length", "10", "Upload-Metadata", metadata("notas.md") + ",campaign " + b64(campaign));
        HttpResponse<String> loose = create(gm, 10, "solto.pdf");
        JsonNode listed = json(get(gm, "/api/v1/materials?state=uploading"));
        assertThat(ofCampaign.statusCode()).isEqualTo(201);
        assertThat(loose.statusCode()).isEqualTo(201);
        // By name, not by position: the list is newest first by id, and two ids minted in one millisecond sort either way.
        JsonNode looseItem = itemNamed(listed, "solto.pdf");
        assertThat(looseItem.path("belonging").toString()).isEqualTo("{\"campaign\":null,\"systems\":[]}");
        assertThat(looseItem.has("size")).isFalse();
        JsonNode campaignItem = itemNamed(listed, "notas.md").path("belonging").path("campaign");
        assertThat(campaignItem.path("id").asString()).isEqualTo(campaign);
        assertThat(campaignItem.path("name").asString()).isEqualTo(current.path("name").asString());
        assertThat(campaignItem.path("system").path("id").asString()).isEqualTo(first);
        // The single system key still works.
        assertThat(send("POST", "/api/v1/uploads", null, session(gm), "Tus-Resumable", TUS, "Upload-Length", "10",
                "Upload-Metadata", metadata("a.pdf") + ",system " + b64(first)).statusCode()).isEqualTo(201);
    }

    /** The item of a material list named {@code name}. */
    private static JsonNode itemNamed(JsonNode listed, String name) {
        for (JsonNode item : listed.path("items")) {
            if (item.path("name").asString().equals(name)) {
                return item;
            }
        }
        throw new AssertionError("no listed material named " + name);
    }

    @Test
    void systemsWithACampaignOrTheSingleSystemKeyAnEmptyIdOrOverAHundredAreInvalidMetadata() throws Exception {
        Gm gm = signUp();
        JsonNode current = json(get(gm, "/api/v1/campaigns/current"));
        String system = current.path("system").path("id").asString();
        int before = materials.materials.size();
        List<String> refused = List.of(",campaign " + b64(current.path("id").asString()) + ",systems " + b64(system),
                ",system " + b64(system) + ",systems " + b64(system), ",systems " + b64(system + ","),
                ",systems " + b64(String.join(",", java.util.Collections.nCopies(101, system))), ",systems");
        for (String details : refused) {
            HttpResponse<String> response = send("POST", "/api/v1/uploads", null, session(gm), "Tus-Resumable", TUS,
                    "Upload-Length", "10", "Upload-Metadata", metadata("a.txt") + details);
            assertProblem(response, 400, "upload.metadata_invalid");
        }
        assertThat(materials.materials).hasSize(before);
    }

    @Test
    void anImageSetTakesSeveralSystems() throws Exception {
        Gm gm = signUp();
        String first = json(get(gm, "/api/v1/campaigns/current")).path("system").path("id").asString();
        String second = system(gm, "Pathfinder");
        String body = "{\"name\":\"Fichas\",\"systems\":[\"" + first + "\",\"" + second
                + "\"],\"images\":[{\"name\":\"a.jpg\",\"length\":10}]}";

        HttpResponse<String> created = sendJson(gm, "POST", "/api/v1/image-sets", body);

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID materialId = UUID.fromString(json(created).path("materialId").asString());
        assertThat(as(gm, () -> materials.systemsOf(materialId))).hasSize(2);
        assertThat(sendJson(gm, "POST", "/api/v1/image-sets", body.replace("\"systems\"", "\"system\":\"" + first
                + "\",\"systems\"")).statusCode()).isEqualTo(400);
        // An empty list names no system: refused, as tus refuses an empty value.
        int before = materials.materials.size();
        HttpResponse<String> empty = sendJson(gm, "POST", "/api/v1/image-sets",
                "{\"name\":\"Fichas\",\"systems\":[],\"images\":[{\"name\":\"a.jpg\",\"length\":10}]}");
        assertThat(empty.statusCode()).as(empty.body()).isEqualTo(400);
        assertThat(json(empty).path("code").asString()).isEqualTo("upload.metadata_invalid");
        assertThat(materials.materials).hasSize(before);
    }

    @Test
    void anImageSetWithTheSingleSystemFieldAloneStillBelongsToThatSystem() throws Exception {
        Gm gm = signUp();
        Gm other = signUp();
        String mine = json(get(gm, "/api/v1/campaigns/current")).path("system").path("id").asString();
        String theirs = json(get(other, "/api/v1/campaigns/current")).path("system").path("id").asString();
        String body = "{\"name\":\"Fichas\",\"system\":\"%s\",\"images\":[{\"name\":\"a.jpg\",\"length\":10}]}";

        // What a 1.20 frontend sends between the two deploys.
        HttpResponse<String> created = sendJson(gm, "POST", "/api/v1/image-sets", body.formatted(mine));

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID materialId = UUID.fromString(json(created).path("materialId").asString());
        assertThat(as(gm, () -> materials.systemsOf(materialId))).singleElement()
                .satisfies(row -> assertThat(row.systemId()).isEqualTo(UUID.fromString(mine)));
        int before = materials.materials.size();
        HttpResponse<String> foreign = sendJson(gm, "POST", "/api/v1/image-sets", body.formatted(theirs));
        assertThat(foreign.statusCode()).as(foreign.body()).isEqualTo(400);
        assertThat(json(foreign).path("code").asString()).isEqualTo("upload.metadata_invalid");
        assertThat(materials.materials).hasSize(before);
    }

    @Test
    void aMaterialsPlaceChangesOnItsVersionAndEveryRefusalChangesNothing() throws Exception {
        Gm gm = signUp();
        Gm other = signUp();
        JsonNode current = json(get(gm, "/api/v1/campaigns/current"));
        String campaign = current.path("id").asString();
        String system = current.path("system").path("id").asString();
        String second = system(gm, "Pathfinder");
        String materialId = uploaded(gm, ",campaign " + b64(campaign));
        String path = "/api/v1/materials/" + materialId;
        int jobsBefore = jobs.rows.size();

        HttpResponse<String> moved = sendJson(gm, "PATCH", path, change(0, null, List.of(system)));

        assertThat(moved.statusCode()).as(moved.body()).isEqualTo(200);
        assertThat(moved.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(json(moved).path("version").asLong()).isEqualTo(1);
        assertThat(json(moved).path("belonging").path("campaign").isNull()).isTrue();
        assertThat(json(moved).path("belonging").path("systems").get(0).path("id").asString()).isEqualTo(system);
        HttpResponse<String> both = sendJson(gm, "PATCH", path, change(1, null, List.of(system, second)));
        assertThat(both.statusCode()).as(both.body()).isEqualTo(200);
        assertThat(json(both).path("belonging").path("systems").size()).isEqualTo(2);
        HttpResponse<String> back = sendJson(gm, "PATCH", path, change(2, campaign, null));
        assertThat(json(back).path("belonging").path("campaign").path("id").asString()).isEqualTo(campaign);
        assertThat(json(get(gm, path)).path("version").asLong()).isEqualTo(3);
        // Nothing is processed again: no job came of the three changes.
        assertThat(jobs.rows).hasSize(jobsBefore);

        // Stale.
        HttpResponse<String> stale = sendJson(gm, "PATCH", path, change(0, null, List.of(system)));
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(json(stale).path("code").asString()).isEqualTo("request.stale_version");
        // Bad bodies: both, neither, [], 101 ids, unknown, another owner's, being deleted.
        String theirSystem = json(get(other, "/api/v1/campaigns/current")).path("system").path("id").asString();
        String deleting = system(gm, "Indo");
        assertThat(send("DELETE", "/api/v1/systems/" + deleting, null, session(gm)).statusCode()).isEqualTo(202);
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            tooMany.add(system);
        }
        for (String body : List.of(change(3, campaign, List.of(system)), "{\"version\":3,\"belonging\":{}}",
                "{\"version\":3}", change(3, null, List.of()), change(3, null, tooMany),
                change(3, null, List.of(UUID.randomUUID().toString())), change(3, null, List.of(theirSystem)),
                change(3, "not-a-uuid", null), change(3, null, List.of(deleting)))) {
            HttpResponse<String> refused = sendJson(gm, "PATCH", path, body);
            assertThat(refused.statusCode()).as(body).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).as(body).isEqualTo("material.belonging_invalid");
            assertThat(refused.body()).doesNotContain(theirSystem);
        }
        for (String body : List.of("{\"belonging\":{\"campaign\":\"" + campaign + "\"}}", "{\"version\":-1}",
                "not json")) {
            HttpResponse<String> refused = sendJson(gm, "PATCH", path, body);
            assertThat(refused.statusCode()).as(body).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).as(body).isEqualTo("request.invalid");
        }
        // Another owner's material, an unknown or a malformed id.
        JsonNode notFound = json(sendJson(other, "PATCH", "/api/v1/materials/" + UUID.randomUUID(),
                change(3, null, List.of(theirSystem))));
        for (String id : List.of(materialId, "not-a-uuid")) {
            HttpResponse<String> response = sendJson(other, "PATCH", "/api/v1/materials/" + id,
                    change(3, null, List.of(theirSystem)));
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(withoutTraceId(json(response))).isEqualTo(withoutTraceId(notFound));
            assertThat(response.body()).doesNotContain(materialId);
        }
        JsonNode after = json(get(gm, path));
        assertThat(after.path("version").asLong()).isEqualTo(3);
        assertThat(after.path("belonging").path("campaign").path("id").asString()).isEqualTo(campaign);
    }

    @Test
    void aMaterialWhoseOnlyPlaceIsBeingDeletedLeavesEveryListAndCannotBeChanged() throws Exception {
        Gm gm = signUp();
        String system = json(get(gm, "/api/v1/campaigns/current")).path("system").path("id").asString();
        String going = system(gm, "Indo");
        String kept = uploaded(gm, ",systems " + b64(going + "," + system));
        String gone = uploaded(gm, ",systems " + b64(going));

        assertThat(send("DELETE", "/api/v1/systems/" + going, null, session(gm)).statusCode()).isEqualTo(202);

        List<String> ids = new ArrayList<>();
        json(get(gm, "/api/v1/materials")).path("items").forEach(item -> ids.add(item.path("id").asString()));
        assertThat(ids).contains(kept).doesNotContain(gone);
        HttpResponse<String> refused = sendJson(gm, "PATCH", "/api/v1/materials/" + gone, change(0, null,
                List.of(system)));
        assertThat(refused.statusCode()).isEqualTo(404);
    }

    // --- Story 3.4: a replacement names a material and nothing else

    @Test
    void aReplacementNamesOnlyTheMaterialAndAnythingElseIsInvalidMetadata() throws Exception {
        Gm gm = signUp();
        JsonNode current = json(get(gm, "/api/v1/campaigns/current"));
        String campaign = current.path("id").asString();
        String system = current.path("system").path("id").asString();
        String materialId = ready(gm, uploaded(gm, ",kind " + b64("rulebook") + ",campaign " + b64(campaign)));
        int versions = materials.versions.size();
        String named = ",material " + b64(materialId);

        // Matrix row "Bad metadata".
        for (String details : List.of(named + ",kind " + b64("rulebook"), named + ",language " + b64("en"),
                named + ",campaign " + b64(campaign), named + ",system " + b64(system),
                named + ",systems " + b64(system), ",material")) {
            HttpResponse<String> response = send("POST", "/api/v1/uploads", null, session(gm), "Tus-Resumable", TUS,
                    "Upload-Length", "1000", "Upload-Metadata", metadata("novo.pdf") + details);
            assertProblem(response, 400, "upload.metadata_invalid");
        }
        for (String field : List.of("\"kind\":\"rulebook\"", "\"language\":\"en\"", "\"campaign\":\"" + campaign
                + "\"", "\"system\":\"" + system + "\"", "\"systems\":[\"" + system + "\"]")) {
            HttpResponse<String> response = sendJson(gm, "POST", "/api/v1/image-sets", "{\"name\":\"Fotos\","
                    + field + ",\"material\":\"" + materialId + "\",\"images\":[{\"name\":\"a.jpg\",\"length\":10}]}");
            assertThat(response.statusCode()).as(field).isEqualTo(400);
            assertThat(json(response).path("code").asString()).isEqualTo("upload.metadata_invalid");
        }
        assertThat(materials.versions).hasSize(versions);

        // A valid one: a new version of the same material, which keeps its name, kind and place.
        HttpResponse<String> created = send("POST", "/api/v1/uploads", null, session(gm), "Tus-Resumable", TUS,
                "Upload-Length", "2000", "Upload-Metadata", metadata("novo.pdf") + named);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(materials.versions).hasSize(versions + 1);
        JsonNode material = json(get(gm, "/api/v1/materials/" + materialId));
        assertThat(material.path("state").asString()).isEqualTo("uploading");
        assertThat(material.path("name").asString()).isEqualTo("material.pdf");
        assertThat(material.path("kind").asString()).isEqualTo("rulebook");
        assertThat(material.path("belonging").path("campaign").path("id").asString()).isEqualTo(campaign);
        assertThat(material.path("upload").path("length").asLong()).isEqualTo(2000);
        assertThat(material.path("serving").toString()).isEqualTo("{\"pages\":null,\"size\":1000}");
        // Matrix row "Busy": a second one waits for the first, by tus and by image set.
        HttpResponse<String> busy = send("POST", "/api/v1/uploads", null, session(gm), "Tus-Resumable", TUS,
                "Upload-Length", "1000", "Upload-Metadata", metadata("outro.pdf") + named);
        assertProblem(busy, 409, "material.version_in_progress");
        HttpResponse<String> busySet = sendJson(gm, "POST", "/api/v1/image-sets", "{\"name\":\"Fotos\",\"material\":\""
                + materialId + "\",\"images\":[{\"name\":\"a.jpg\",\"length\":10}]}");
        assertThat(busySet.statusCode()).isEqualTo(409);
        assertThat(json(busySet).path("code").asString()).isEqualTo("material.version_in_progress");
        assertThat(materials.versions).hasSize(versions + 1);

        // A refused format ends the new version alone: the material stays, ready on its old one.
        String location = created.headers().firstValue("Location").orElseThrow();
        byte[] notAFile = new byte[2000];
        assertProblem(patch(gm, location, 0, notAFile), 415, "upload.format_refused");
        assertThat(materials.versions).hasSize(versions);
        assertThat(json(get(gm, "/api/v1/materials/" + materialId)).path("state").asString()).isEqualTo("ready");
    }

    @Test
    void anotherOwnersUnknownMalformedOrDeletingMaterialIsNotFoundForAReplacement() throws Exception {
        Gm a = signUp();
        Gm b = signUp();
        String system = json(get(a, "/api/v1/campaigns/current")).path("system").path("id").asString();
        String mine = ready(a, uploaded(a, ""));
        String going = system(a, "Indo");
        String gone = ready(a, uploaded(a, ",systems " + b64(going)));
        assertThat(send("DELETE", "/api/v1/systems/" + going, null, session(a)).statusCode()).isEqualTo(202);
        int versions = materials.versions.size();

        // Matrix row "Other owner", and an unknown, a malformed or a deleting material: one answer.
        for (Object[] attempt : new Object[][] {{b, mine}, {a, UUID.randomUUID().toString()}, {a, "not-a-uuid"},
            {a, gone}}) {
            HttpResponse<String> response = send("POST", "/api/v1/uploads", null, session((Gm) attempt[0]),
                    "Tus-Resumable", TUS, "Upload-Length", "1000", "Upload-Metadata", metadata("novo.pdf")
                            + ",material " + b64((String) attempt[1]));
            assertProblem(response, 404, "request.not_found");
            assertThat(response.body()).doesNotContain(mine);
        }
        assertThat(materials.versions).hasSize(versions);
        assertThat(system).isNotBlank();
    }

    /** The material's only version becomes ready, as an accept leaves it: its id. */
    private String ready(Gm gm, String materialId) {
        UUID id = UUID.fromString(materialId);
        materials.versions.replaceAll((versionId, row) -> row.materialId().equals(id)
                ? new io.github.gmshield.materials.data.VersionRow(row.id(), row.ownerId(), row.materialId(), "ready",
                        row.format(), row.sizeBytes(), row.createdAt(), row.updatedAt(), row.pageTotal(),
                        row.pipelineVersion(), row.failureCode())
                : row);
        return materialId;
    }

    private String system(Gm gm, String name) throws Exception {
        HttpResponse<String> created = sendJson(gm, "POST", "/api/v1/systems", "{\"name\":\"" + name + "\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return json(created).path("id").asString();
    }

    /** A verified 1000-byte PDF with these metadata details: its material's id. */
    private String uploaded(Gm gm, String details) throws Exception {
        byte[] content = pdf(1000);
        HttpResponse<String> created = send("POST", "/api/v1/uploads", null, session(gm), "Tus-Resumable", TUS,
                "Upload-Length", "1000", "Upload-Metadata", metadata("material.pdf") + details);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String location = created.headers().firstValue("Location").orElseThrow();
        patch(gm, location, 0, content);
        return json(complete(gm, location, sha256Hex(content))).path("materialId").asString();
    }

    private static String change(long version, String campaign, List<String> systems) {
        Map<String, Object> belonging = new LinkedHashMap<>();
        if (campaign != null) {
            belonging.put("campaign", campaign);
        }
        if (systems != null) {
            belonging.put("systems", systems);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("belonging", belonging);
        return JSON.writeValueAsString(body);
    }

    private HttpResponse<String> sendJson(Gm gm, String method, String path, String body) throws Exception {
        return send(method, path, body.getBytes(StandardCharsets.UTF_8), session(gm), "Content-Type",
                "application/json");
    }

    private static <T> T as(Gm gm, java.util.function.Supplier<T> work) {
        return OwnerContext.callAs(gm.id(), work::get);
    }

    private HttpResponse<String> get(Gm gm, String path) throws Exception {
        return send("GET", path, null, session(gm));
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    // --- helpers ---

    private HttpResponse<String> create(Gm gm, long length, String name) throws Exception {
        return send("POST", "/api/v1/uploads", null, session(gm), "Tus-Resumable", TUS, "Upload-Length",
                Long.toString(length), "Upload-Metadata", metadata(name) + ",future,note cHQtQlI=");
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
