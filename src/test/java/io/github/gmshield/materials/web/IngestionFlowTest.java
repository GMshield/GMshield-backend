package io.github.gmshield.materials.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import io.github.gmshield.jobs.data.InMemoryJobStream;
import io.github.gmshield.jobs.data.JobMemoryStores;
import io.github.gmshield.jobs.data.JobRow;
import io.github.gmshield.jobs.service.JobSweep;
import io.github.gmshield.library.data.LibraryTestStores;
import io.github.gmshield.materials.data.InMemoryMaterialStore;
import io.github.gmshield.materials.data.InMemoryUploadStorage;
import io.github.gmshield.materials.data.MaterialTestStores;
import io.github.gmshield.materials.data.StepRow;
import io.github.gmshield.materials.data.UploadRow;
import io.github.gmshield.materials.data.VersionFileRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.shared.service.TraceContext;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * Story 1.15 without a database or Redis: every backend row of the I/O matrix, from the GM's upload through the
 * worker's claim, its input and outputs and its report, to what the GM reads. The chain, the version's states, the
 * adopted files, the raw upload's removal, the failures and the step cap. The same tables against PostgreSQL are in
 * {@code IngestionIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GMSHIELD_MODE=test")
@ActiveProfiles({"test-mode", "unit"})
class IngestionFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CREDENTIAL = "gmshield-test-mode-frontend-credential-not-a-secret";
    private static final String WORKER_CREDENTIAL = "gmshield-test-mode-worker-credential-not-a-secret";
    private static final String TUS = "1.0.0";
    private static final int MIB = 1024 * 1024;

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
    private InMemoryJobStorage jobs;
    @Autowired
    private InMemoryJobStream stream;
    @Autowired
    private JobSweep sweep;
    @Autowired
    private FileStore files;
    @Autowired
    private Vault vault;

    private final HttpClient client = HttpClient.newHttpClient();
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    record Gm(UUID id, String session) {
    }

    /** A completed upload: its material, version, raw upload and first step's job. */
    record Uploaded(String materialId, UUID versionId, UUID uploadId, UUID jobId) {
    }

    @BeforeEach
    void startLog() {
        // One ingestion job at a time across every test: end whatever an earlier test left offered or running.
        jobs.rows.replaceAll((id, row) -> row.state().equals("offered") || row.state().equals("running")
                || row.state().equals("waiting") ? row.released("cancelled", null, row.updatedAt()) : row);
        stream.clear();
        logged.start();
        root.addAppender(logged);
    }

    @AfterEach
    void stopLog() {
        root.detachAppender(logged);
        logged.stop();
    }

    // --- the chain's start

    @Test
    void aCompletedPdfWaitsWithOneCleanStepOfferedUnderTheTraceOfItsCompletion() throws Exception {
        Gm gm = signUp();
        String trace = TraceContext.newTraceId();
        byte[] content = pdf(5 * MIB + 77);

        Uploaded uploaded = upload(gm, content, "Livro.pdf", trace);

        assertThat(materials.versions.get(uploaded.versionId()).state()).isEqualTo("waiting");
        List<StepRow> steps = steps(uploaded.versionId());
        assertThat(steps).singleElement().satisfies(step -> {
            assertThat(step.stage()).isEqualTo("clean");
            assertThat(step.firstPage()).isNull();
            assertThat(step.inputFileId()).isEqualTo(uploaded.uploadId());
            assertThat(step.ownerId()).isEqualTo(gm.id());
        });
        JobRow job = jobs.row(uploaded.jobId());
        assertThat(job.kind()).isEqualTo("ingestion");
        assertThat(job.lane()).isEqualTo("ingestion");
        assertThat(job.ownerId()).isEqualTo(gm.id());
        assertThat(job.state()).isEqualTo("offered");
        assertThat(job.traceId()).isEqualTo(trace);
        assertThat(stream.offered("gmshield:work:ingestion")).containsExactly(uploaded.jobId());
        assertThat(state(gm, uploaded)).isEqualTo("waiting");

        // The first claim: the version is processing, and the worker reads the raw file through its input.
        JsonNode claim = claim(uploaded.jobId());
        assertThat(claim.path("kind").asString()).isEqualTo("ingestion");
        assertThat(claim.path("input").path("stage").asString()).isEqualTo("clean");
        assertThat(claim.path("input").path("format").asString()).isEqualTo("pdf");
        assertThat(claim.path("input").path("input").asString()).isEqualTo("source");
        assertThat(claim.path("input").path("versionId").asString()).isEqualTo(uploaded.versionId().toString());
        assertThat(claim.path("input").has("firstPage")).isFalse();
        assertThat(materials.versions.get(uploaded.versionId()).state()).isEqualTo("processing");
        HttpResponse<byte[]> input = input(uploaded.jobId(), claim, "source");
        assertThat(input.statusCode()).isEqualTo(200);
        assertThat(input.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/octet-stream");
        assertThat(input.headers().firstValueAsLong("Content-Length")).hasValue(content.length);
        assertThat(input.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(input.body()).isEqualTo(content);
        assertThat(input(uploaded.jobId(), claim, "other").statusCode()).isEqualTo(404);
        JsonNode read = json(get(gm, "/api/v1/materials/" + uploaded.materialId()));
        assertThat(read.path("state").asString()).isEqualTo("processing");
        assertThat(read.has("pages")).isFalse();
        assertThat(read.has("failure")).isFalse();

        // Every backend line about the job carries the completion's trace id.
        List<ILoggingEvent> lines = logged.list.stream().filter(event -> event.getKeyValuePairs() != null
                && event.getKeyValuePairs().stream().anyMatch(pair -> pair.key.equals("jobId")
                && uploaded.jobId().toString().equals(String.valueOf(pair.value)))).toList();
        assertThat(lines).extracting(ILoggingEvent::getMessage)
                .contains("job.created", "ingestion.step_created", "job.offered", "job.claimed", "ingestion.processing");
        assertThat(lines).allSatisfy(event -> assertThat(event.getMDCPropertyMap().get("traceId")).isEqualTo(trace));
    }

    @Test
    void aTextMarkdownOrImageUploadGetsNoStepAndStaysWaiting() throws Exception {
        Gm gm = signUp();
        byte[] text = "Regras da casa\n".repeat(400).getBytes(StandardCharsets.UTF_8);
        byte[] png = png(6000);
        for (Uploaded uploaded : List.of(upload(gm, text, "notas.txt", null), upload(gm, text, "notas.md", null),
                upload(gm, png, "mapa.png", null))) {
            assertThat(uploaded.jobId()).isNull();
            assertThat(steps(uploaded.versionId())).isEmpty();
            assertThat(state(gm, uploaded)).isEqualTo("waiting");
        }
        assertThat(jobs.rows.values()).noneMatch(row -> row.ownerId().equals(gm.id()));
    }

    // --- a done clean step

    @Test
    void aDoneCleanStepRecordsTheOriginalItsBatchesAndThePageTotalAndRemovesTheRawUpload() throws Exception {
        Gm gm = signUp();
        byte[] content = pdf(2 * MIB);
        Uploaded uploaded = upload(gm, content, "Livro.pdf", null);
        JsonNode claim = claim(uploaded.jobId());
        byte[] cleaned = pdf(5 * MIB + 3);
        byte[] first = pdf(3000);
        byte[] second = pdf(2000);
        UUID original = output(uploaded.jobId(), claim, cleaned);
        UUID batch1 = output(uploaded.jobId(), claim, first);
        UUID batch2 = output(uploaded.jobId(), claim, second);

        HttpResponse<String> reported = outcome(uploaded.jobId(), claim, "{\"outcome\":\"done\",\"result\":{"
                + "\"pipelineVersion\":\"1\",\"pageTotal\":25,\"original\":{\"fileId\":\"" + original + "\"},"
                + "\"batches\":[{\"fileId\":\"" + batch1 + "\",\"firstPage\":1,\"lastPage\":20},"
                + "{\"fileId\":\"" + batch2 + "\",\"firstPage\":21,\"lastPage\":25}],\"next\":[]}}");

        assertThat(reported.statusCode()).as(reported.body()).isEqualTo(204);
        VersionRow version = materials.versions.get(uploaded.versionId());
        assertThat(version.state()).isEqualTo("processing");
        assertThat(version.pageTotal()).isEqualTo(25);
        assertThat(version.pipelineVersion()).isEqualTo("1");
        assertThat(version.failureCode()).isNull();
        List<VersionFileRow> stored = OwnerContext.callAs(gm.id(), () -> materials.versionFiles(uploaded.versionId()));
        assertThat(stored).extracting(VersionFileRow::role, VersionFileRow::fileId, VersionFileRow::firstPage,
                        VersionFileRow::lastPage)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("original", original, 1, 25),
                        org.assertj.core.groups.Tuple.tuple("batch", batch1, 1, 20),
                        org.assertj.core.groups.Tuple.tuple("batch", batch2, 21, 25));
        assertThat(stored.getFirst().sizeBytes()).isEqualTo(cleaned.length);
        assertThat(stored.getFirst().pieceCount()).isEqualTo(2);
        assertThat(readBack(gm.id(), original, 2)).isEqualTo(cleaned);
        assertThat(readBack(gm.id(), batch2, 1)).isEqualTo(second);
        // Adopted: no output row is left for the sweep, which leaves the files alone.
        assertThat(jobs.outputs).isEmpty();
        sweep.sweep();
        assertThat(files.fileExists(gm.id(), batch1)).isTrue();
        // The raw upload: marked removing in the report's transaction, its pieces and row gone after the commit.
        assertThat(uploads.rows).doesNotContainKey(uploaded.uploadId());
        assertThat(files.fileExists(gm.id(), uploaded.uploadId())).isFalse();
        assertThat(jobs.row(uploaded.jobId()).state()).isEqualTo("done");
        assertThat(steps(uploaded.versionId())).hasSize(1);

        JsonNode read = json(get(gm, "/api/v1/materials/" + uploaded.materialId()));
        assertThat(read.path("state").asString()).isEqualTo("processing");
        assertThat(read.path("pages").path("done").asInt()).isZero();
        assertThat(read.path("pages").path("total").asInt()).isEqualTo(25);
        assertThat(read.has("failure")).isFalse();
        JsonNode listed = json(get(gm, "/api/v1/materials?state=processing")).path("items").get(0);
        assertThat(listed.path("pages").path("total").asInt()).isEqualTo(25);
    }

    @Test
    void nextStepsWithinTheCapBecomeWaitingIngestionJobsOverTheVersionsFiles() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        JsonNode claim = claim(uploaded.jobId());
        UUID original = output(uploaded.jobId(), claim, pdf(4000));
        UUID batch = output(uploaded.jobId(), claim, pdf(4000));

        HttpResponse<String> reported = outcome(uploaded.jobId(), claim, "{\"outcome\":\"done\",\"result\":{"
                + "\"pipelineVersion\":\"1\",\"pageTotal\":2,\"original\":{\"fileId\":\"" + original + "\"},"
                + "\"batches\":[{\"fileId\":\"" + batch + "\",\"firstPage\":1,\"lastPage\":2}],"
                + "\"next\":[{\"stage\":\"read\",\"firstPage\":1,\"lastPage\":1,\"input\":\"" + batch + "\"},"
                + "{\"stage\":\"read\",\"firstPage\":2,\"lastPage\":2,\"input\":\"" + batch + "\"}]}}");

        assertThat(reported.statusCode()).as(reported.body()).isEqualTo(204);
        List<StepRow> steps = steps(uploaded.versionId());
        assertThat(steps).hasSize(3);
        List<StepRow> next = steps.stream().filter(step -> step.stage().equals("read")).toList();
        assertThat(next).extracting(StepRow::firstPage).containsExactlyInAnyOrder(1, 2);
        assertThat(next).allSatisfy(step -> {
            assertThat(step.inputFileId()).isEqualTo(batch);
            assertThat(jobs.row(step.jobId()).kind()).isEqualTo("ingestion");
            assertThat(jobs.row(step.jobId()).traceId()).isEqualTo(jobs.row(uploaded.jobId()).traceId());
        });
        // One ingestion job at a time: one of them is offered, the other waits.
        assertThat(next.stream().map(step -> jobs.row(step.jobId()).state()).toList())
                .containsExactlyInAnyOrder("offered", "waiting");
        // The next step's claim gets its page range and opens the batch as its input.
        StepRow offered = next.stream().filter(step -> jobs.row(step.jobId()).state().equals("offered")).findFirst()
                .orElseThrow();
        JsonNode nextClaim = claim(offered.jobId());
        assertThat(nextClaim.path("input").path("stage").asString()).isEqualTo("read");
        assertThat(nextClaim.path("input").path("firstPage").asInt()).isEqualTo(offered.firstPage());
        assertThat(nextClaim.path("input").path("pageTotal").asInt()).isEqualTo(2);
        assertThat(nextClaim.path("input").path("pipelineVersion").asString()).isEqualTo("1");
        assertThat(input(offered.jobId(), nextClaim, "source").body()).hasSize(4000);
    }

    // --- failures

    @Test
    void aPasswordProtectedPdfFailsAtOnceAndLeavesNoFileOfTheVersion() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Protegido.pdf", null);
        JsonNode claim = claim(uploaded.jobId());
        UUID stray = output(uploaded.jobId(), claim, pdf(3000));

        assertThat(outcome(uploaded.jobId(), claim, "{\"outcome\":\"failed\",\"code\":\"ingestion.password_required\","
                + "\"retry\":false}").statusCode()).isEqualTo(204);

        JobRow job = jobs.row(uploaded.jobId());
        assertThat(job.state()).isEqualTo("failed");
        assertThat(job.attempt()).isEqualTo(1);
        VersionRow version = materials.versions.get(uploaded.versionId());
        assertThat(version.state()).isEqualTo("failed");
        assertThat(version.failureCode()).isEqualTo("ingestion.password_required");
        assertThat(uploads.rows).doesNotContainKey(uploaded.uploadId());
        assertThat(files.fileExists(gm.id(), uploaded.uploadId())).isFalse();
        sweep.sweep();
        assertThat(files.fileExists(gm.id(), stray)).isFalse();
        assertThat(jobs.outputs).isEmpty();
        assertThat(materials.files.values()).noneMatch(file -> file.versionId().equals(uploaded.versionId()));

        // The material stays, so the GM sees why.
        JsonNode read = json(get(gm, "/api/v1/materials/" + uploaded.materialId()));
        assertThat(read.path("state").asString()).isEqualTo("failed");
        assertThat(read.path("failure").asString()).isEqualTo("ingestion.password_required");
        assertThat(read.path("name").asString()).isEqualTo("Protegido.pdf");
        assertThat(json(get(gm, "/api/v1/materials?state=failed")).path("items").get(0).path("failure").asString())
                .isEqualTo("ingestion.password_required");
    }

    @Test
    void aFileThatIsNotAPdfInsideFailsAtOnceWithItsCode() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Quebrado.pdf", null);
        JsonNode claim = claim(uploaded.jobId());

        outcome(uploaded.jobId(), claim, "{\"outcome\":\"failed\",\"code\":\"ingestion.file_unreadable\",\"retry\":false}");

        assertThat(jobs.row(uploaded.jobId()).state()).isEqualTo("failed");
        assertThat(materials.versions.get(uploaded.versionId()).failureCode()).isEqualTo("ingestion.file_unreadable");
        assertThat(json(get(gm, "/api/v1/materials/" + uploaded.materialId())).path("failure").asString())
                .isEqualTo("ingestion.file_unreadable");
    }

    @Test
    void aStepThatCrashesIsRetriedUpToThreeAttemptsThenFailsTheVersion() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        for (int attempt = 1; attempt <= 3; attempt++) {
            JsonNode claim = claim(uploaded.jobId());
            assertThat(claim.path("attempt").asInt()).isEqualTo(attempt);
            assertThat(outcome(uploaded.jobId(), claim, "{\"outcome\":\"failed\",\"code\":\"ingestion.step_failed\"}")
                    .statusCode()).isEqualTo(204);
            if (attempt < 3) {
                assertThat(jobs.row(uploaded.jobId()).state()).isEqualTo("offered");
                assertThat(materials.versions.get(uploaded.versionId()).state()).isEqualTo("processing");
            }
        }
        assertThat(jobs.row(uploaded.jobId()).state()).isEqualTo("failed");
        VersionRow version = materials.versions.get(uploaded.versionId());
        assertThat(version.state()).isEqualTo("failed");
        assertThat(version.failureCode()).isEqualTo("ingestion.step_failed");
        // Only a password removes the raw upload: a crash keeps it for "Tentar de novo" (Story 1.20).
        assertThat(uploads.row(uploaded.uploadId()).state()).isEqualTo(UploadRow.COMPLETE);
    }

    @Test
    void aReportWhoseNextStepsPassTheCapFailsTheVersionAndAdoptsNothing() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        JsonNode claim = claim(uploaded.jobId());
        UUID original = output(uploaded.jobId(), claim, pdf(4000));
        // One page: at most 4 x 1 + 1 = 5 steps; the clean step and five more are six.
        StringBuilder next = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            next.append(i == 0 ? "" : ",").append("{\"stage\":\"read\",\"firstPage\":1,\"lastPage\":1,\"input\":\"")
                    .append(original).append("\"}");
        }

        HttpResponse<String> reported = outcome(uploaded.jobId(), claim, "{\"outcome\":\"done\",\"result\":{"
                + "\"pipelineVersion\":\"1\",\"pageTotal\":1,\"original\":{\"fileId\":\"" + original + "\"},"
                + "\"next\":[" + next + "]}}");

        assertThat(reported.statusCode()).as(reported.body()).isEqualTo(204);
        VersionRow version = materials.versions.get(uploaded.versionId());
        assertThat(version.state()).isEqualTo("failed");
        assertThat(version.failureCode()).isEqualTo("ingestion.too_many_steps");
        assertThat(steps(uploaded.versionId())).hasSize(1);
        assertThat(materials.files.values()).noneMatch(file -> file.versionId().equals(uploaded.versionId()));
        sweep.sweep();
        assertThat(files.fileExists(gm.id(), original)).isFalse();
        assertThat(json(get(gm, "/api/v1/materials/" + uploaded.materialId())).path("failure").asString())
                .isEqualTo("ingestion.too_many_steps");
    }

    @Test
    void aReportThatCannotBeRecordedIsRefusedAndChangesNothing() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        JsonNode claim = claim(uploaded.jobId());
        UUID original = output(uploaded.jobId(), claim, pdf(4000));
        Uploaded other = upload(signUp(), pdf(MIB), "Outro.pdf", null);
        String unknown = UUID.randomUUID().toString();

        for (String result : List.of("{}", "{\"pipelineVersion\":\"\"}",
                "{\"pipelineVersion\":\"1\",\"original\":{\"fileId\":\"" + original + "\"}}",
                "{\"pipelineVersion\":\"1\",\"pageTotal\":3,\"original\":{\"fileId\":\"" + unknown + "\"}}",
                "{\"pipelineVersion\":\"1\",\"pageTotal\":3,\"original\":{\"fileId\":\"not-an-id\"}}",
                "{\"pipelineVersion\":\"1\",\"pageTotal\":3,\"batches\":[{\"fileId\":\"" + original
                        + "\",\"firstPage\":2,\"lastPage\":4}]}",
                "{\"pipelineVersion\":\"1\",\"pageTotal\":3,\"original\":{\"fileId\":\"" + original + "\"},"
                        + "\"next\":[{\"stage\":\"Read!\",\"input\":\"" + original + "\"}]}",
                "{\"pipelineVersion\":\"1\",\"pageTotal\":3,\"next\":[{\"stage\":\"read\",\"input\":\""
                        + other.uploadId() + "\"}]}")) {
            HttpResponse<String> refused = outcome(uploaded.jobId(), claim, "{\"outcome\":\"done\",\"result\":" + result + "}");
            assertThat(refused.statusCode()).as(result).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).as(result).isEqualTo("job.result_invalid");
        }
        assertThat(jobs.row(uploaded.jobId()).state()).isEqualTo("running");
        VersionRow version = materials.versions.get(uploaded.versionId());
        assertThat(version.state()).isEqualTo("processing");
        assertThat(version.pageTotal()).isNull();
        assertThat(version.pipelineVersion()).isNull();
        assertThat(jobs.outputs).containsKey(original);
        assertThat(uploads.row(uploaded.uploadId()).state()).isEqualTo(UploadRow.COMPLETE);
    }

    // --- owners

    @Test
    void anotherOwnersStepsAndFilesAreNeverSeen() throws Exception {
        Gm a = signUp();
        Gm b = signUp();
        Uploaded uploaded = upload(a, pdf(MIB), "Segredo.pdf", null);
        JsonNode claim = claim(uploaded.jobId());
        UUID original = output(uploaded.jobId(), claim, pdf(4000));
        outcome(uploaded.jobId(), claim, "{\"outcome\":\"done\",\"result\":{\"pipelineVersion\":\"1\",\"pageTotal\":1,"
                + "\"original\":{\"fileId\":\"" + original + "\"}}}");

        OwnerContext.runAs(b.id(), () -> {
            assertThat(materials.findStepByJob(uploaded.jobId())).isEmpty();
            assertThat(materials.countSteps(uploaded.versionId())).isZero();
            assertThat(materials.versionFiles(uploaded.versionId())).isEmpty();
        });
        OwnerContext.runAs(a.id(), () -> {
            assertThat(materials.findStepByJob(uploaded.jobId())).isPresent();
            assertThat(materials.versionFiles(uploaded.versionId())).hasSize(1);
        });
        HttpResponse<String> theirs = get(b, "/api/v1/materials/" + uploaded.materialId());
        assertThat(theirs.statusCode()).isEqualTo(404);
        assertThat(theirs.body()).doesNotContain("pages").doesNotContain(uploaded.materialId());
    }

    // --- helpers

    private Uploaded upload(Gm gm, byte[] content, String name, String trace) throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/uploads", null, session(gm), "Tus-Resumable", TUS,
                "Upload-Length", Integer.toString(content.length), "Upload-Metadata", "filename "
                        + Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8)));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String location = created.headers().firstValue("Location").orElseThrow();
        UUID uploadId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        for (int offset = 0; offset < content.length; offset += 4 * MIB) {
            byte[] part = java.util.Arrays.copyOfRange(content, offset, Math.min(content.length, offset + 4 * MIB));
            HttpResponse<String> patched = send("PATCH", location, part, session(gm), "Tus-Resumable", TUS,
                    "Content-Type", "application/offset+octet-stream", "Upload-Offset", Integer.toString(offset),
                    "Upload-Checksum", "sha256 " + Base64.getEncoder().encodeToString(sha256(part)));
            assertThat(patched.statusCode()).as(patched.body()).isEqualTo(204);
        }
        List<String> headers = new ArrayList<>(List.of(session(gm)));
        headers.addAll(List.of("Tus-Resumable", TUS, "Content-Type", "application/json"));
        if (trace != null) {
            headers.addAll(List.of("traceparent", "00-" + trace + "-00f067aa0ba902b7-01"));
        }
        HttpResponse<String> completed = send("POST", location + "/completion",
                ("{\"sha256\":\"" + HexFormat.of().formatHex(sha256(content)) + "\"}").getBytes(StandardCharsets.UTF_8),
                headers.toArray(String[]::new));
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(201);
        UUID versionId = UUID.fromString(json(completed).path("versionId").asString());
        UUID jobId = steps(versionId).stream().map(StepRow::jobId).findFirst().orElse(null);
        return new Uploaded(json(completed).path("materialId").asString(), versionId, uploadId, jobId);
    }

    private List<StepRow> steps(UUID versionId) {
        return materials.steps.values().stream().filter(step -> step.versionId().equals(versionId)).toList();
    }

    private String state(Gm gm, Uploaded uploaded) throws Exception {
        return json(get(gm, "/api/v1/materials/" + uploaded.materialId())).path("state").asString();
    }

    private JsonNode claim(UUID jobId) throws Exception {
        HttpResponse<String> claimed = worker("POST", "/internal/v1/jobs/" + jobId + "/claims", null);
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(201);
        return json(claimed);
    }

    private HttpResponse<byte[]> input(UUID jobId, JsonNode claim, String name) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/internal/v1/jobs/" + jobId + "/claims/"
                        + claim.path("claimId").asString() + "/inputs/" + name))
                .header("Authorization", "Bearer " + WORKER_CREDENTIAL)
                .header("X-Job-Grant", claim.path("grant").asString()).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private UUID output(UUID jobId, JsonNode claim, byte[] content) throws Exception {
        HttpResponse<String> stored = worker("POST", "/internal/v1/jobs/" + jobId + "/claims/"
                + claim.path("claimId").asString() + "/outputs", content, "Content-Type", "application/octet-stream",
                "X-Job-Grant", claim.path("grant").asString());
        assertThat(stored.statusCode()).as(stored.body()).isEqualTo(201);
        return UUID.fromString(json(stored).path("fileId").asString());
    }

    private HttpResponse<String> outcome(UUID jobId, JsonNode claim, String body) throws Exception {
        return worker("POST", "/internal/v1/jobs/" + jobId + "/claims/" + claim.path("claimId").asString() + "/outcome",
                body.getBytes(StandardCharsets.UTF_8), "Content-Type", "application/json",
                "X-Job-Grant", claim.path("grant").asString());
    }

    private HttpResponse<String> worker(String method, String path, byte[] body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + WORKER_CREDENTIAL)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private byte[] readBack(UUID owner, UUID fileId, int pieces) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < pieces; i++) {
            out.writeBytes(files.readPiece(owner, fileId, i));
        }
        return out.toByteArray();
    }

    private HttpResponse<String> get(Gm gm, String path) throws Exception {
        return send("GET", path, null, session(gm));
    }

    private static String[] session(Gm gm) {
        return new String[] {"X-Session-Token", gm.session()};
    }

    private HttpResponse<String> send(String method, String path, byte[] body, String[] first, String... more)
            throws Exception {
        String[] all = java.util.Arrays.copyOf(first, first.length + more.length);
        System.arraycopy(more, 0, all, first.length, more.length);
        return send(method, path, body, all);
    }

    private HttpResponse<String> send(String method, String path, byte[] body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + CREDENTIAL)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private Gm signUp() throws Exception {
        String sub = "ing-" + UUID.randomUUID();
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
        AccountRow row = accounts.findByGoogleIdFingerprint(vault.fingerprint("account", "google_id", sub)).orElseThrow();
        return new Gm(row.id(), json(response).path("sessionToken").asString());
    }

    private HttpResponse<String> post(String path, Map<String, Object> body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + CREDENTIAL);
        if (body == null) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static byte[] pdf(int size) {
        return UploadFlowTest.pdf(size);
    }

    private static byte[] png(int size) {
        byte[] content = new byte[size];
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
        System.arraycopy(head, 0, content, 0, head.length);
        return content;
    }

    private static byte[] sha256(byte[] content) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(content);
    }
}
