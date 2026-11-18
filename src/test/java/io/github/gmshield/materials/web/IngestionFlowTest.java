package io.github.gmshield.materials.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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
import io.github.gmshield.materials.data.OutlineRow;
import io.github.gmshield.materials.data.PageRangeRow;
import io.github.gmshield.materials.data.PageViewRow;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.StepRow;
import io.github.gmshield.materials.data.UploadRow;
import io.github.gmshield.materials.data.VersionFileRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.materials.service.Materials;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.shared.service.TraceContext;
import io.github.gmshield.vault.data.DataKeyStore;
import io.github.gmshield.vault.data.InMemoryDataKeyStore;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.FileStore;
import io.github.gmshield.vault.service.Vault;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 1.15 without a database or Redis: every backend row of the I/O matrix, from the GM's upload through the
 * worker's claim, its input and outputs and its report, to what the GM reads. The chain, the version's states, the
 * adopted files, the raw upload's removal, the failures and the step cap. Story 1.16 too: triage and layout steps, the
 * page ranges and their refusals, {@code pages.done}, the end of the chain, a retried and a re-offered step, the
 * restart on a pipeline change, a text upload's step, and the cross-owner test of the page ranges. Story 1.17: OCR
 * steps over a scanned and a mixed PDF, the page counts by layer and outcome the GM reads, and two owners' scans
 * offered in turn. Story 1.18: the held step over every text file, its job once every page has an outcome, its claim
 * input and text inputs, the passages and outline it reports (refused when invalid, replaced when reported again), a
 * failure or a restart ending it, and the cross-owner test of passages and outlines. Story 1.19: the page views a
 * reading report carries (refused when invalid, removed by a restart), each page served on the review ground with its
 * passages and its picture, and the cross-owner test of pages and pictures. The same tables against PostgreSQL are in
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
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private Materials reads;

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
    void aTextOrMarkdownUploadGetsOneTextStepOverItsRawFileAndAnImageStaysWaiting() throws Exception {
        Gm gm = signUp();
        byte[] text = "Regras da casa\n".repeat(400).getBytes(StandardCharsets.UTF_8);
        for (String name : List.of("notas.txt", "notas.md")) {
            Uploaded uploaded = upload(gm, text, name, null);
            assertThat(steps(uploaded.versionId())).singleElement().satisfies(step -> {
                assertThat(step.stage()).isEqualTo("text");
                assertThat(step.firstPage()).isNull();
                assertThat(step.inputFileId()).isEqualTo(uploaded.uploadId());
            });
            assertThat(state(gm, uploaded)).isEqualTo("waiting");
            // One ingestion job at a time: this one ends before the next upload's is offered.
            jobs.rows.computeIfPresent(uploaded.jobId(), (id, row) -> row.released("cancelled", null, row.updatedAt()));
        }
        Uploaded image = upload(gm, png(6000), "mapa.png", null);
        assertThat(image.jobId()).isNull();
        assertThat(steps(image.versionId())).isEmpty();
        assertThat(state(gm, image)).isEqualTo("waiting");
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
                .containsExactly(tuple("original", original, 1, 25),
                        tuple("batch", batch1, 1, 20),
                        tuple("batch", batch2, 21, 25));
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

        // Another page total than the one recorded: refused, nothing changes.
        HttpResponse<String> changedTotal = outcome(offered.jobId(), nextClaim,
                "{\"outcome\":\"done\",\"result\":{\"pipelineVersion\":\"1\",\"pageTotal\":3}}");
        assertThat(changedTotal.statusCode()).isEqualTo(400);
        assertThat(json(changedTotal).path("code").asString()).isEqualTo("job.result_invalid");
        assertThat(jobs.row(offered.jobId()).state()).isEqualTo("running");
        assertThat(materials.versions.get(uploaded.versionId()).pageTotal()).isEqualTo(2);
        assertThat(materials.versions.get(uploaded.versionId()).state()).isEqualTo("processing");
        // Another pipeline version than the chain's first counts as pipeline_changed: the chain restarts.
        assertThat(outcome(offered.jobId(), nextClaim,
                "{\"outcome\":\"done\",\"result\":{\"pipelineVersion\":\"2\",\"pageTotal\":2}}").statusCode())
                .isEqualTo(204);
        VersionRow version = materials.versions.get(uploaded.versionId());
        assertThat(version.state()).isEqualTo("processing");
        assertThat(version.pipelineVersion()).isNull();
        assertThat(version.pageTotal()).isEqualTo(2);
        assertThat(steps(uploaded.versionId())).filteredOn(step -> step.endedAt() == null).singleElement()
                .satisfies(step -> {
                    assertThat(step.stage()).isEqualTo("clean");
                    assertThat(step.inputFileId()).isEqualTo(original);
                });
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
    void aFinalFailureWithACodeOutsideIngestionOrNoneIsRecordedAsAStepFailure() throws Exception {
        Gm gm = signUp();
        for (String body : List.of("{\"outcome\":\"failed\",\"code\":\"worker.handler_failed\",\"retry\":false}",
                "{\"outcome\":\"failed\",\"retry\":false}")) {
            Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
            JsonNode claim = claim(uploaded.jobId());

            assertThat(outcome(uploaded.jobId(), claim, body).statusCode()).as(body).isEqualTo(204);

            assertThat(jobs.row(uploaded.jobId()).state()).isEqualTo("failed");
            VersionRow version = materials.versions.get(uploaded.versionId());
            assertThat(version.state()).as(body).isEqualTo("failed");
            assertThat(version.failureCode()).as(body).isEqualTo("ingestion.step_failed");
            assertThat(json(get(gm, "/api/v1/materials/" + uploaded.materialId())).path("failure").asString())
                    .isEqualTo("ingestion.step_failed");
        }
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

    // --- Story 1.16: reading pages

    @Test
    void aDigitalPdfOf45PagesGoesThroughThreeTriageAndThreeLayoutStepsToNeedsReview() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        Cleaned cleaned = clean(uploaded, 45, "2");

        assertThat(steps(uploaded.versionId())).filteredOn(step -> step.stage().equals("triage"))
                .extracting(StepRow::firstPage, StepRow::lastPage, StepRow::inputFileId)
                .containsExactlyInAnyOrder(tuple(1, 20, cleaned.batches().get(0)),
                        tuple(21, 40, cleaned.batches().get(1)),
                        tuple(41, 45, cleaned.batches().get(2)));
        List<Integer> done = new ArrayList<>();
        List<String> stages = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            StepRow step = offeredStep(uploaded.versionId());
            JsonNode claim = claim(step.jobId());
            JsonNode input = claim.path("input");
            stages.add(input.path("stage").asString());
            assertThat(input.path("attempt").asInt()).isEqualTo(1);
            assertThat(input.path("pipelineVersion").asString()).isEqualTo("2");
            assertThat(input.path("pageTotal").asInt()).isEqualTo(45);
            VersionFileRow batch = batchOf(gm, uploaded, step.inputFileId());
            assertThat(input.path("inputFileId").asString()).isEqualTo(batch.fileId().toString());
            assertThat(input.path("inputFirstPage").asInt()).isEqualTo(batch.firstPage());
            assertThat(input.path("inputLastPage").asInt()).isEqualTo(batch.lastPage());
            if (input.path("stage").asString().equals("triage")) {
                report(step, claim, triage(step, step.firstPage(), step.lastPage(), List.of()));
            } else {
                report(step, claim, layout(step, claim, step.firstPage(), step.lastPage(), List.of()));
            }
            done.add(json(get(gm, "/api/v1/materials/" + uploaded.materialId())).path("pages").path("done").asInt());
        }

        assertThat(stages).containsExactly("triage", "triage", "triage", "layout", "layout", "layout");
        assertThat(done).containsExactly(0, 0, 0, 20, 40, 45);
        JsonNode read = json(get(gm, "/api/v1/materials/" + uploaded.materialId()));
        assertThat(read.path("state").asString()).isEqualTo("needs_review");
        assertThat(read.path("pages").path("done").asInt()).isEqualTo(45);
        assertThat(read.path("pages").path("total").asInt()).isEqualTo(45);
        assertThat(steps(uploaded.versionId())).hasSize(7).allSatisfy(step -> assertThat(step.endedAt()).isNotNull());
        assertThat(OwnerContext.callAs(gm.id(), () -> materials.versionFiles(uploaded.versionId())))
                .filteredOn(file -> file.role().equals("text")).extracting(VersionFileRow::firstPage)
                .containsExactlyInAnyOrder(1, 21, 41);
        assertThat(outcomes(uploaded.versionId())).extracting(row -> row.value()).containsOnly("read_directly");
        assertThat(layers(uploaded.versionId())).extracting(row -> row.value()).containsOnly("text");
        assertThat(json(get(gm, "/api/v1/materials?state=needs_review")).path("items").get(0).path("pages")
                .path("done").asInt()).isEqualTo(45);
    }

    @Test
    void aMixedPdfReadsItsTextPagesByLayoutAndItsImagePagesByOcrToNeedsReview() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Misto.pdf", null);
        clean(uploaded, 45, "2");

        List<JsonNode> counts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            StepRow step = offeredStep(uploaded.versionId());
            JsonNode claim = claim(step.jobId());
            switch (step.stage()) {
                case "triage" -> {
                    List<Integer> images = step.firstPage() == 1 ? List.of(6, 7) : List.of();
                    report(step, claim, triage(step, step.firstPage(), step.lastPage(), images));
                }
                case "ocr" -> report(step, claim, ocr(step, claim, step.firstPage(), step.lastPage(), List.of()));
                default -> report(step, claim, layout(step, claim, step.firstPage(), step.lastPage(), List.of()));
            }
            counts.add(json(get(gm, "/api/v1/materials/" + uploaded.materialId())).path("pages"));
        }

        assertThat(steps(uploaded.versionId())).filteredOn(step -> step.stage().equals("layout"))
                .extracting(StepRow::firstPage, StepRow::lastPage)
                .containsExactlyInAnyOrder(tuple(1, 5),
                        tuple(8, 20), tuple(21, 40),
                        tuple(41, 45));
        assertThat(steps(uploaded.versionId())).filteredOn(step -> step.stage().equals("ocr"))
                .extracting(StepRow::firstPage, StepRow::lastPage, StepRow::inputFileId)
                .containsExactly(tuple(6, 7, steps(uploaded.versionId()).stream()
                        .filter(step -> step.stage().equals("triage") && step.firstPage() == 1).findFirst()
                        .orElseThrow().inputFileId()));
        assertThat(steps(uploaded.versionId())).allSatisfy(step -> assertThat(step.endedAt()).isNotNull());
        // While pages wait, the counts say which kind: the first triage alone sorted 18 text and 2 image pages.
        assertThat(counts.get(0).path("text").path("total").asInt()).isEqualTo(18);
        assertThat(counts.get(0).path("image").path("total").asInt()).isEqualTo(2);
        assertThat(counts.get(0).path("image").path("done").asInt()).isZero();
        JsonNode read = json(get(gm, "/api/v1/materials/" + uploaded.materialId()));
        assertThat(read.path("state").asString()).isEqualTo("needs_review");
        JsonNode pages = read.path("pages");
        assertThat(pages.path("done").asInt()).isEqualTo(45);
        assertThat(pages.path("total").asInt()).isEqualTo(45);
        assertThat(pages.path("text").path("done").asInt()).isEqualTo(43);
        assertThat(pages.path("text").path("total").asInt()).isEqualTo(43);
        assertThat(pages.path("image").path("done").asInt()).isEqualTo(2);
        assertThat(pages.path("image").path("total").asInt()).isEqualTo(2);
        assertThat(pages.path("readDirectly").asInt()).isEqualTo(43);
        assertThat(pages.path("readByOcr").asInt()).isEqualTo(2);
        assertThat(pages.path("unreadable").asInt()).isZero();
        assertThat(layers(uploaded.versionId())).filteredOn(row -> row.value().equals("image"))
                .extracting(row -> row.firstPage(), row -> row.lastPage())
                .containsExactly(tuple(6, 7));
        assertThat(outcomes(uploaded.versionId())).filteredOn(row -> row.value().equals("read_by_ocr"))
                .extracting(row -> row.firstPage(), row -> row.lastPage())
                .containsExactly(tuple(6, 7));
    }

    // --- Story 1.17: reading scanned pages

    @Test
    void aScannedPdfOf25PagesIsReadByThreeOcrStepsToNeedsReview() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Digitalizado.pdf", null);
        clean(uploaded, 25, "2");
        List<Integer> all = IntStream.rangeClosed(1, 25).boxed().toList();

        List<String> stages = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            StepRow step = offeredStep(uploaded.versionId());
            JsonNode claim = claim(step.jobId());
            stages.add(step.stage());
            if (step.stage().equals("triage")) {
                report(step, claim, triage(step, step.firstPage(), step.lastPage(), all));
            } else {
                // A blank scan on page 13 is unreadable; the chain goes on.
                List<Integer> blank = step.firstPage() == 11 ? List.of(13) : List.of();
                report(step, claim, ocr(step, claim, step.firstPage(), step.lastPage(), blank));
            }
        }

        assertThat(stages).containsExactly("triage", "triage", "ocr", "ocr", "ocr");
        assertThat(steps(uploaded.versionId())).filteredOn(step -> step.stage().equals("ocr"))
                .extracting(StepRow::firstPage, StepRow::lastPage)
                .containsExactlyInAnyOrder(tuple(1, 10), tuple(11, 20), tuple(21, 25));
        JsonNode read = json(get(gm, "/api/v1/materials/" + uploaded.materialId()));
        assertThat(read.path("state").asString()).isEqualTo("needs_review");
        JsonNode pages = read.path("pages");
        assertThat(pages.path("done").asInt()).isEqualTo(25);
        assertThat(pages.path("text").path("total").asInt()).isZero();
        assertThat(pages.path("image").path("done").asInt()).isEqualTo(25);
        assertThat(pages.path("image").path("total").asInt()).isEqualTo(25);
        assertThat(pages.path("readDirectly").asInt()).isZero();
        assertThat(pages.path("readByOcr").asInt()).isEqualTo(24);
        assertThat(pages.path("unreadable").asInt()).isEqualTo(1);
        assertThat(json(get(gm, "/api/v1/materials?state=needs_review")).path("items").get(0).path("pages")
                .path("readByOcr").asInt()).isEqualTo(24);
    }

    @Test
    void anOcrStepInProgressBetweenTextBatchesIsReadingImageWhileLaterTextPagesWait() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Misto.pdf", null);
        clean(uploaded, 30, "2");
        String path = "/api/v1/materials/" + uploaded.materialId();
        // Before any layer is recorded, the triage in progress reads nothing yet.
        assertThat(json(get(gm, path)).path("pages").path("reading").isNull()).isTrue();
        for (int i = 0; i < 2; i++) {
            StepRow triage = offeredStep(uploaded.versionId());
            JsonNode claim = claim(triage.jobId());
            List<Integer> images = triage.firstPage() == 1 ? IntStream.rangeClosed(9, 20).boxed().toList() : List.of();
            report(triage, claim, triage(triage, triage.firstPage(), triage.lastPage(), images));
        }
        StepRow layout = offeredStep(uploaded.versionId());
        assertThat(layout.stage()).isEqualTo("layout");
        assertThat(json(get(gm, path)).path("pages").path("reading").asString()).isEqualTo("text");
        JsonNode layoutClaim = claim(layout.jobId());
        report(layout, layoutClaim, layout(layout, layoutClaim, 1, 8, List.of()));

        // Batch 1's OCR 9-18 is offered, then running, while batch 2's text pages 21-30 still wait.
        StepRow ocr = offeredStep(uploaded.versionId());
        assertThat(ocr.stage()).isEqualTo("ocr");
        assertThat(json(get(gm, path)).path("pages").path("reading").asString()).isEqualTo("image");
        claim(ocr.jobId());
        JsonNode pages = json(get(gm, path)).path("pages");
        assertThat(pages.path("reading").asString()).isEqualTo("image");
        assertThat(pages.path("text").path("done").asInt()).isEqualTo(8);
        assertThat(pages.path("text").path("total").asInt()).isEqualTo(18);
        assertThat(pages.path("image").path("done").asInt()).isZero();
        assertThat(pages.path("image").path("total").asInt()).isEqualTo(12);
        assertThat(json(get(gm, "/api/v1/materials?state=processing")).path("items").get(0).path("pages")
                .path("reading").asString()).isEqualTo("image");
    }

    @Test
    void twoOwnersScansAreOfferedInTurnOneStepAtATime() throws Exception {
        Gm a = signUp();
        Gm b = signUp();
        Uploaded scanA = upload(a, pdf(MIB), "A.pdf", null);
        Uploaded scanB = upload(b, pdf(MIB), "B.pdf", null);
        Map<UUID, UUID> ownerOf = Map.of(scanA.versionId(), a.id(), scanB.versionId(), b.id());
        List<Integer> all = IntStream.rangeClosed(1, 20).boxed().toList();

        List<UUID> owners = new ArrayList<>();
        List<String> stages = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            StepRow step = offeredStep(List.of(scanA.versionId(), scanB.versionId()));
            owners.add(ownerOf.get(step.versionId()));
            stages.add(step.stage());
            JsonNode claim = claim(step.jobId());
            switch (step.stage()) {
                case "clean" -> cleanReport(step.jobId(), claim, 20, "2");
                case "triage" -> report(step, claim, triage(step, 1, 20, all));
                default -> report(step, claim, ocr(step, claim, step.firstPage(), step.lastPage(), List.of()));
            }
        }

        assertThat(owners).containsExactly(a.id(), b.id(), a.id(), b.id(), a.id(), b.id(), a.id(), b.id());
        assertThat(stages).containsExactly("clean", "clean", "triage", "triage", "ocr", "ocr", "ocr", "ocr");
        assertThat(state(a, scanA)).isEqualTo("needs_review");
        assertThat(state(b, scanB)).isEqualTo("needs_review");
    }

    @Test
    void aLayoutStepThatFailsIsRetriedAsItsSecondAttemptAndAnUnreadablePageIsRecorded() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        clean(uploaded, 20, "2");
        StepRow triage = offeredStep(uploaded.versionId());
        report(triage, claim(triage.jobId()), triage(triage, 1, 20, List.of()));
        StepRow layout = offeredStep(uploaded.versionId());
        JsonNode first = claim(layout.jobId());
        assertThat(outcome(layout.jobId(), first, "{\"outcome\":\"failed\",\"code\":\"ingestion.step_failed\"}")
                .statusCode()).isEqualTo(204);
        assertThat(steps(uploaded.versionId())).filteredOn(step -> step.endedAt() == null).hasSize(1);

        JsonNode second = claim(layout.jobId());
        assertThat(second.path("input").path("attempt").asInt()).isEqualTo(2);
        report(layout, second, layout(layout, second, 1, 20, List.of(9)));

        assertThat(outcomes(uploaded.versionId())).extracting(row -> row.firstPage(), row -> row.lastPage(),
                        row -> row.value())
                .containsExactly(tuple(1, 8, "read_directly"),
                        tuple(9, 9, "unreadable"),
                        tuple(10, 20, "read_directly"));
        JsonNode read = json(get(gm, "/api/v1/materials/" + uploaded.materialId()));
        assertThat(read.path("state").asString()).isEqualTo("needs_review");
        assertThat(read.path("pages").path("done").asInt()).isEqualTo(20);
    }

    @Test
    void aStepWhoseWorkerDiedIsTakenBackWhenItsClaimLapsesAndFinishedStepsAreNotRerun() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        clean(uploaded, 20, "2");
        StepRow triage = offeredStep(uploaded.versionId());
        report(triage, claim(triage.jobId()), triage(triage, 1, 20, List.of()));
        StepRow layout = offeredStep(uploaded.versionId());
        claim(layout.jobId());

        // The worker is killed mid-layout: its claim lapses, and the sweep offers the step again.
        jobs.rows.computeIfPresent(layout.jobId(), (id, row) -> new JobRow(row.id(), row.ownerId(), row.kind(),
                row.lane(), row.state(), row.attempt(), row.traceId(), row.offeredAt(), row.claimId(), row.grantHash(),
                row.claimRenewAt(), Instant.now().minusSeconds(1), row.outcomeCode(), row.createdAt(),
                row.updatedAt()));
        sweep.sweep();
        assertThat(jobs.row(layout.jobId()).state()).isEqualTo("offered");
        assertThat(offeredStep(uploaded.versionId()).jobId()).isEqualTo(layout.jobId());

        JsonNode again = claim(layout.jobId());
        report(layout, again, layout(layout, again, 1, 20, List.of()));

        assertThat(steps(uploaded.versionId())).hasSize(3);
        assertThat(jobs.row(uploaded.jobId()).attempt()).isEqualTo(1);
        assertThat(jobs.row(triage.jobId()).attempt()).isEqualTo(1);
        assertThat(state(gm, uploaded)).isEqualTo("needs_review");
        assertThat(outcomes(uploaded.versionId())).hasSize(1);
    }

    @Test
    void aStepFailedWithPipelineChangedRestartsTheChainFromTheCleanedOriginal() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        Cleaned cleaned = clean(uploaded, 25, "1");
        StepRow firstTriage = offeredStep(uploaded.versionId());
        JsonNode triageClaim = claim(firstTriage.jobId());
        assertThat(triageClaim.path("input").path("pipelineVersion").asString()).isEqualTo("1");
        report(firstTriage, triageClaim, triage(firstTriage, firstTriage.firstPage(), firstTriage.lastPage(), List.of())
                .replace("\"pipelineVersion\":\"2\"", "\"pipelineVersion\":\"1\""));
        assertThat(layers(uploaded.versionId())).isNotEmpty();

        // A worker of pipeline "2" claims the next step of this chain of pipeline "1".
        StepRow secondTriage = offeredStep(uploaded.versionId());
        JsonNode stale = claim(secondTriage.jobId());
        assertThat(outcome(secondTriage.jobId(), stale,
                "{\"outcome\":\"failed\",\"code\":\"ingestion.pipeline_changed\",\"retry\":false}").statusCode())
                .isEqualTo(204);

        VersionRow version = materials.versions.get(uploaded.versionId());
        assertThat(version.state()).isEqualTo("processing");
        assertThat(version.pipelineVersion()).isNull();
        assertThat(version.pageTotal()).isEqualTo(25);
        assertThat(version.failureCode()).isNull();
        assertThat(layers(uploaded.versionId())).isEmpty();
        assertThat(outcomes(uploaded.versionId())).isEmpty();
        assertThat(OwnerContext.callAs(gm.id(), () -> materials.versionFiles(uploaded.versionId())))
                .extracting(VersionFileRow::role, VersionFileRow::fileId)
                .containsExactly(tuple("original", cleaned.original()));
        assertThat(cleaned.batches()).allSatisfy(batch -> assertThat(files.fileExists(gm.id(), batch)).isFalse());
        // The old steps ended; the first layout step's job, which was waiting, is cancelled.
        List<StepRow> steps = steps(uploaded.versionId());
        StepRow oldLayout = steps.stream().filter(step -> step.stage().equals("layout")).findFirst().orElseThrow();
        assertThat(jobs.row(oldLayout.jobId()).state()).isEqualTo("cancelled");
        assertThat(jobs.row(secondTriage.jobId()).state()).isEqualTo("failed");
        assertThat(steps).filteredOn(step -> step.endedAt() == null).singleElement().satisfies(step -> {
            assertThat(step.stage()).isEqualTo("clean");
            assertThat(step.inputFileId()).isEqualTo(cleaned.original());
        });
        assertThat(state(gm, uploaded)).isEqualTo("processing");
        assertThat(json(get(gm, "/api/v1/materials/" + uploaded.materialId())).path("pages").path("done").asInt())
                .isZero();

        // The new clean step reads the stored original; its report replaces it, under the new pipeline version.
        StepRow restart = offeredStep(uploaded.versionId());
        JsonNode claim = claim(restart.jobId());
        assertThat(claim.path("input").has("pipelineVersion")).isFalse();
        assertThat(claim.path("input").path("pageTotal").asInt()).isEqualTo(25);
        assertThat(claim.path("input").path("inputFileId").asString()).isEqualTo(cleaned.original().toString());
        assertThat(input(restart.jobId(), claim, "source").body()).hasSize(cleaned.originalSize());
        Cleaned again = cleanReport(restart.jobId(), claim, 25, "2");
        assertThat(OwnerContext.callAs(gm.id(), () -> materials.versionFiles(uploaded.versionId())))
                .filteredOn(file -> file.role().equals("original")).extracting(VersionFileRow::fileId)
                .containsExactly(again.original());
        assertThat(files.fileExists(gm.id(), cleaned.original())).isFalse();
        assertThat(materials.versions.get(uploaded.versionId()).pipelineVersion()).isEqualTo("2");
        assertThat(jobs.row(oldLayout.jobId()).state()).isEqualTo("cancelled");
    }

    @Test
    void aReportUnderAnotherPipelineVersionRestartsTheChainLikeTheFailure() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        Cleaned cleaned = clean(uploaded, 20, "1");
        StepRow triage = offeredStep(uploaded.versionId());

        report(triage, claim(triage.jobId()), triage(triage, 1, 20, List.of()));

        assertThat(jobs.row(triage.jobId()).state()).isEqualTo("done");
        assertThat(layers(uploaded.versionId())).isEmpty();
        assertThat(materials.versions.get(uploaded.versionId()).pipelineVersion()).isNull();
        assertThat(steps(uploaded.versionId())).filteredOn(step -> step.endedAt() == null).singleElement()
                .satisfies(step -> assertThat(step.inputFileId()).isEqualTo(cleaned.original()));
    }

    @Test
    void aPageGivenASecondLayerOrOutcomeOrTextOverAnUnreadPageIsRefusedAndNothingIsRecorded() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        Cleaned cleaned = clean(uploaded, 20, "2");
        StepRow triage = offeredStep(uploaded.versionId());
        JsonNode triageClaim = claim(triage.jobId());
        UUID batch = cleaned.batches().getFirst();
        for (String result : List.of(
                "{\"pipelineVersion\":\"2\",\"layers\":[" + range(1, 5, "layer", "text") + ","
                        + range(5, 6, "layer", "image") + "]}",
                "{\"pipelineVersion\":\"2\",\"layers\":[" + range(1, 21, "layer", "text") + "]}",
                "{\"pipelineVersion\":\"2\",\"layers\":[" + range(0, 2, "layer", "text") + "]}",
                "{\"pipelineVersion\":\"2\",\"layers\":[" + range(1, 2, "layer", "scan") + "]}",
                "{\"pipelineVersion\":\"2\",\"pages\":[" + range(3, 2, "outcome", "unreadable") + "]}",
                "{\"pipelineVersion\":\"2\",\"pages\":[" + range(1, 2, "outcome", "read") + "]}")) {
            HttpResponse<String> refused = outcome(triage.jobId(), triageClaim,
                    "{\"outcome\":\"done\",\"result\":" + result + "}");
            assertThat(refused.statusCode()).as(result).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).isEqualTo("job.result_invalid");
        }
        assertThat(layers(uploaded.versionId())).isEmpty();
        // Two layout steps over the same pages: the backend does not interpret them, but a page is read once.
        String twice = "{\"stage\":\"layout\",\"firstPage\":1,\"lastPage\":20,\"input\":\"" + batch + "\"}";
        report(triage, triageClaim, "{\"pipelineVersion\":\"2\",\"layers\":[" + range(1, 20, "layer", "text")
                + "],\"next\":[" + twice + "," + twice + "]}");

        StepRow layout = offeredStep(uploaded.versionId());
        JsonNode claim = claim(layout.jobId());
        UUID text = output(layout.jobId(), claim, "{\"pages\":[]}".getBytes(StandardCharsets.UTF_8));
        for (String result : List.of(
                // the same pages twice in one report
                "{\"pipelineVersion\":\"2\",\"pages\":[" + range(1, 10, "outcome", "read_directly") + ","
                        + range(10, 20, "outcome", "read_directly") + "]}",
                // a text file over a page with no outcome, or an unreadable one
                "{\"pipelineVersion\":\"2\",\"pages\":[" + range(1, 10, "outcome", "read_directly") + "],"
                        + "\"texts\":[{\"fileId\":\"" + text + "\",\"firstPage\":1,\"lastPage\":11}]}",
                "{\"pipelineVersion\":\"2\",\"pages\":[" + range(1, 10, "outcome", "read_directly") + ","
                        + range(11, 11, "outcome", "unreadable") + "],"
                        + "\"texts\":[{\"fileId\":\"" + text + "\",\"firstPage\":1,\"lastPage\":11}]}",
                // a layer for a page triage already gave one
                "{\"pipelineVersion\":\"2\",\"layers\":[" + range(4, 4, "layer", "image") + "]}")) {
            HttpResponse<String> refused = outcome(layout.jobId(), claim, "{\"outcome\":\"done\",\"result\":" + result + "}");
            assertThat(refused.statusCode()).as(result).isEqualTo(400);
        }
        assertThat(outcomes(uploaded.versionId())).isEmpty();
        assertThat(jobs.row(layout.jobId()).state()).isEqualTo("running");
        report(layout, claim, "{\"pipelineVersion\":\"2\",\"pages\":[" + range(1, 20, "outcome", "read_directly")
                + "],\"texts\":[{\"fileId\":\"" + text + "\",\"firstPage\":1,\"lastPage\":20}]}");

        // An outcome a second time, from a later report: refused, nothing recorded.
        assertThat(state(gm, uploaded)).isEqualTo("processing");
        StepRow extra = offeredStep(uploaded.versionId());
        JsonNode extraClaim = claim(extra.jobId());
        HttpResponse<String> repeated = outcome(extra.jobId(), extraClaim, "{\"outcome\":\"done\",\"result\":"
                + "{\"pipelineVersion\":\"2\",\"pages\":[" + range(20, 20, "outcome", "unreadable") + "]}}");
        assertThat(repeated.statusCode()).isEqualTo(400);
        assertThat(json(repeated).path("code").asString()).isEqualTo("job.result_invalid");
        assertThat(outcomes(uploaded.versionId())).hasSize(1);
    }

    @Test
    void aTextUploadsReportGivesItsOriginalItsPagesAndItsTextsAndEndsTheChain() throws Exception {
        Gm gm = signUp();
        byte[] text = "# Regras\n\nTexto da casa.\n".repeat(300).getBytes(StandardCharsets.UTF_8);
        Uploaded uploaded = upload(gm, text, "regras.md", null);
        JsonNode claim = claim(uploaded.jobId());
        assertThat(claim.path("input").path("stage").asString()).isEqualTo("text");
        assertThat(claim.path("input").path("format").asString()).isEqualTo("markdown");
        assertThat(claim.path("input").has("pipelineVersion")).isFalse();
        assertThat(claim.path("input").has("inputFileId")).isFalse();
        assertThat(input(uploaded.jobId(), claim, "source").body()).isEqualTo(text);
        UUID original = output(uploaded.jobId(), claim, text);
        UUID pages1 = output(uploaded.jobId(), claim, "{\"pages\":[]}".getBytes(StandardCharsets.UTF_8));
        UUID pages2 = output(uploaded.jobId(), claim, "{\"pages\":[]}".getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> reported = outcome(uploaded.jobId(), claim, "{\"outcome\":\"done\",\"result\":{"
                + "\"pipelineVersion\":\"2\",\"pageTotal\":600,\"original\":{\"fileId\":\"" + original + "\"},"
                + "\"pages\":[" + range(1, 600, "outcome", "read_directly") + "],"
                + "\"texts\":[{\"fileId\":\"" + pages1 + "\",\"firstPage\":1,\"lastPage\":500},"
                + "{\"fileId\":\"" + pages2 + "\",\"firstPage\":501,\"lastPage\":600}],\"next\":[]}}");

        assertThat(reported.statusCode()).as(reported.body()).isEqualTo(204);
        JsonNode read = json(get(gm, "/api/v1/materials/" + uploaded.materialId()));
        assertThat(read.path("state").asString()).isEqualTo("needs_review");
        assertThat(read.path("pages").path("done").asInt()).isEqualTo(600);
        assertThat(OwnerContext.callAs(gm.id(), () -> materials.versionFiles(uploaded.versionId())))
                .extracting(VersionFileRow::role, VersionFileRow::fileId, VersionFileRow::firstPage,
                        VersionFileRow::lastPage)
                .containsExactlyInAnyOrder(tuple("original", original, 1, 600),
                        tuple("text", pages1, 1, 500),
                        tuple("text", pages2, 501, 600));
        assertThat(readBack(gm.id(), original, 1)).isEqualTo(text);
        assertThat(uploads.rows).doesNotContainKey(uploaded.uploadId());
        assertThat(layers(uploaded.versionId())).isEmpty();
    }

    @Test
    void aFinalFailureOfOneStepFailsTheVersionAndCancelsItsOtherSteps() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        clean(uploaded, 45, "2");
        StepRow triage = offeredStep(uploaded.versionId());

        assertThat(outcome(triage.jobId(), claim(triage.jobId()),
                "{\"outcome\":\"failed\",\"code\":\"ingestion.step_failed\",\"retry\":false}").statusCode()).isEqualTo(204);

        VersionRow version = materials.versions.get(uploaded.versionId());
        assertThat(version.state()).isEqualTo("failed");
        assertThat(version.failureCode()).isEqualTo("ingestion.step_failed");
        List<StepRow> siblings = steps(uploaded.versionId()).stream()
                .filter(step -> step.stage().equals("triage") && !step.jobId().equals(triage.jobId())).toList();
        assertThat(siblings).hasSize(2)
                .allSatisfy(step -> assertThat(jobs.row(step.jobId()).state()).isEqualTo("cancelled"));
        assertThat(steps(uploaded.versionId())).hasSize(4).allSatisfy(step -> assertThat(step.endedAt()).isNotNull());
    }

    @Test
    void aRestartedTextUploadsFirstReportMayGiveAnotherPageTotalAndEndsInNeedsReview() throws Exception {
        Gm gm = signUp();
        byte[] text = "# Regras\n\nTexto da casa.\n".repeat(50).getBytes(StandardCharsets.UTF_8);
        Uploaded uploaded = upload(gm, text, "regras.md", null);
        JsonNode claim = claim(uploaded.jobId());
        UUID original = output(uploaded.jobId(), claim, text);
        UUID pages = output(uploaded.jobId(), claim, "{\"pages\":[]}".getBytes(StandardCharsets.UTF_8));
        // Pipeline "1" reads pages 1 and 2 of 3, and leaves page 3 to a later step over the original.
        assertThat(outcome(uploaded.jobId(), claim, "{\"outcome\":\"done\",\"result\":{\"pipelineVersion\":\"1\","
                + "\"pageTotal\":3,\"original\":{\"fileId\":\"" + original + "\"},\"pages\":["
                + range(1, 2, "outcome", "read_directly") + "],\"texts\":[{\"fileId\":\"" + pages
                + "\",\"firstPage\":1,\"lastPage\":2}],\"next\":[{\"stage\":\"text\",\"firstPage\":3,\"lastPage\":3,"
                + "\"input\":\"" + original + "\"}]}}").statusCode()).isEqualTo(204);
        StepRow later = offeredStep(uploaded.versionId());
        assertThat(outcome(later.jobId(), claim(later.jobId()),
                "{\"outcome\":\"failed\",\"code\":\"ingestion.pipeline_changed\",\"retry\":false}").statusCode())
                .isEqualTo(204);
        assertThat(materials.versions.get(uploaded.versionId()).pageTotal()).isEqualTo(3);

        // Pipeline "2" splits the same file into 5 pages: its first report sets the new page total.
        StepRow restart = offeredStep(uploaded.versionId());
        assertThat(restart.stage()).isEqualTo("text");
        assertThat(restart.inputFileId()).isEqualTo(original);
        JsonNode again = claim(restart.jobId());
        assertThat(again.path("input").has("pipelineVersion")).isFalse();
        UUID newOriginal = output(restart.jobId(), again, text);
        UUID newPages = output(restart.jobId(), again, "{\"pages\":[]}".getBytes(StandardCharsets.UTF_8));
        report(restart, again, "{\"pipelineVersion\":\"2\",\"pageTotal\":5,\"original\":{\"fileId\":\"" + newOriginal
                + "\"},\"pages\":[" + range(1, 5, "outcome", "read_directly") + "],\"texts\":[{\"fileId\":\""
                + newPages + "\",\"firstPage\":1,\"lastPage\":5}],\"next\":[]}");

        VersionRow version = materials.versions.get(uploaded.versionId());
        assertThat(version.state()).isEqualTo("needs_review");
        assertThat(version.pageTotal()).isEqualTo(5);
        assertThat(version.pipelineVersion()).isEqualTo("2");
        JsonNode read = json(get(gm, "/api/v1/materials/" + uploaded.materialId()));
        assertThat(read.path("pages").path("done").asInt()).isEqualTo(5);
        assertThat(read.path("pages").path("total").asInt()).isEqualTo(5);
        // Only the new original and the new text file remain.
        assertThat(OwnerContext.callAs(gm.id(), () -> materials.versionFiles(uploaded.versionId())))
                .extracting(VersionFileRow::role, VersionFileRow::fileId, VersionFileRow::lastPage)
                .containsExactlyInAnyOrder(tuple("original", newOriginal, 5), tuple("text", newPages, 5));
    }

    @Test
    void anotherOwnersPageLayersAndOutcomesAreNeverSeenNorWritten() throws Exception {
        Gm a = signUp();
        Gm b = signUp();
        Uploaded uploaded = upload(a, pdf(MIB), "Segredo.pdf", null);
        clean(uploaded, 20, "2");
        StepRow triage = offeredStep(uploaded.versionId());
        report(triage, claim(triage.jobId()), triage(triage, 1, 20, List.of(3)));

        OwnerContext.runAs(b.id(), () -> {
            assertThat(materials.pageLayers(uploaded.versionId())).isEmpty();
            assertThat(materials.layerOverlaps(uploaded.versionId(), 1, 20)).isFalse();
            assertThat(materials.outcomesWithin(uploaded.versionId(), 1, 20)).isEmpty();
            assertThat(materials.pagesDone(List.of(uploaded.versionId()))).isEmpty();
        });
        OwnerContext.runAs(a.id(), () -> assertThat(materials.pageLayers(uploaded.versionId())).hasSize(3));
        assertThatThrownBy(() -> OwnerContext.runAs(b.id(), () ->
                new TransactionTemplate(transactions).executeWithoutResult(
                        status -> materials.insertPageOutcome(new PageRangeRow(
                                UUID.randomUUID(), b.id(), uploaded.versionId(), 1, 1, "unreadable",
                                Instant.now())))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(materials.outcomes.values()).noneMatch(row -> row.versionId().equals(uploaded.versionId()));
    }

    // --- Story 1.18: passages

    @Test
    void aPdfOfTwoBatchesHoldsItsPassagesStepUntilEveryPageHasAnOutcomeThenReadsEveryTextFileInPageOrder()
            throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        Cleaned cleaned = cleanReport(uploaded.jobId(), claim(uploaded.jobId()), 25, "2", true);
        assertThat(passagesStep(uploaded.versionId())).satisfies(held -> {
            assertThat(held.jobId()).isNull();
            assertThat(held.inputTexts()).isTrue();
            assertThat(held.inputFileId()).isNull();
            assertThat(held.firstPage()).isEqualTo(1);
            assertThat(held.lastPage()).isEqualTo(25);
            assertThat(held.endedAt()).isNull();
        });

        // Matrix row "Held step": triage and layout of both batches; the passages step gets no job until the last.
        for (int i = 0; i < 4; i++) {
            assertThat(passagesStep(uploaded.versionId()).jobId()).isNull();
            StepRow step = offeredStep(uploaded.versionId());
            JsonNode claim = claim(step.jobId());
            if (step.stage().equals("triage")) {
                report(step, claim, triage(step, step.firstPage(), step.lastPage(), List.of()));
            } else {
                report(step, claim, layout(step, claim, step.firstPage(), step.lastPage(), List.of()));
            }
        }

        StepRow passages = passagesStep(uploaded.versionId());
        assertThat(passages.jobId()).isNotNull();
        assertThat(jobs.row(passages.jobId()).state()).isEqualTo("offered");
        assertThat(jobs.row(passages.jobId()).kind()).isEqualTo("ingestion");
        JsonNode read = json(get(gm, "/api/v1/materials/" + uploaded.materialId()));
        assertThat(read.path("state").asString()).isEqualTo("processing");
        assertThat(read.path("pages").path("done").asInt()).isEqualTo(25);
        assertThat(read.path("pages").path("reading").isNull()).isTrue();

        JsonNode claim = claim(passages.jobId());
        JsonNode input = claim.path("input");
        assertThat(input.path("stage").asString()).isEqualTo("passages");
        assertThat(input.path("input").asString()).isEqualTo("texts");
        assertThat(input.has("inputFileId")).isFalse();
        assertThat(input.path("firstPage").asInt()).isEqualTo(1);
        assertThat(input.path("lastPage").asInt()).isEqualTo(25);
        assertThat(input.path("pageTotal").asInt()).isEqualTo(25);
        List<VersionFileRow> texts = OwnerContext.callAs(gm.id(), () -> materials.versionFiles(uploaded.versionId()))
                .stream().filter(file -> file.role().equals("text")).toList();
        List<String> names = new ArrayList<>();
        input.path("texts").forEach(text -> names.add(text.path("name").asString() + " "
                + text.path("firstPage").asInt() + "-" + text.path("lastPage").asInt()));
        assertThat(names).containsExactly("text-" + texts.get(0).fileId() + " 1-20",
                "text-" + texts.get(1).fileId() + " 21-25");
        for (VersionFileRow text : texts) {
            HttpResponse<byte[]> opened = input(passages.jobId(), claim, "text-" + text.fileId());
            assertThat(opened.statusCode()).isEqualTo(200);
            assertThat(opened.body()).isEqualTo(readBack(gm.id(), text.fileId(), 1));
        }
        // Only the version's text files, by their names: not the source, a batch, nor another version's text file.
        assertThat(input(passages.jobId(), claim, "source").statusCode()).isEqualTo(404);
        assertThat(input(passages.jobId(), claim, "text-" + cleaned.batches().getFirst()).statusCode()).isEqualTo(404);
        assertThat(input(passages.jobId(), claim, "text-" + cleaned.original()).statusCode()).isEqualTo(404);
        assertThat(input(passages.jobId(), claim, "texts").statusCode()).isEqualTo(404);

        // Matrix row "Path across batches": what the worker cut, stored as it reported it.
        report(passages, claim, passagesReport(
                passage(19, "Magic Items\nItems of wonder.", "Magic Items"),
                passage(21, "Vorpal Sword\nA legendary blade.", "Magic Items", "Vorpal Sword"),
                passage(21, "Vorpal Sword\nIts edge.", "Magic Items", "Vorpal Sword"))
                .replace("\"outline\":[]", "\"outline\":[" + section(1, "Magic Items", 19) + ","
                        + section(2, "Vorpal Sword", 21) + "]"));

        assertThat(state(gm, uploaded)).isEqualTo("needs_review");
        assertThat(steps(uploaded.versionId())).allSatisfy(step -> assertThat(step.endedAt()).isNotNull());
        List<PassageRow> stored = passages(uploaded.versionId());
        assertThat(stored).extracting(PassageRow::ordinal, PassageRow::page)
                .containsExactly(tuple(0, 19), tuple(1, 21), tuple(2, 21));
        assertThat(stored).allSatisfy(row -> assertThat(row.ownerId()).isEqualTo(gm.id()));
        // Only an accepted version's passages are read (Story 1.20).
        assertThat(OwnerContext.callAs(gm.id(), () -> reads.passage(stored.get(1).id()))).isEmpty();
        accept(gm, uploaded);
        Materials.Passage vorpal = OwnerContext.callAs(gm.id(), () -> reads.passage(stored.get(1).id())).orElseThrow();
        assertThat(vorpal.sectionPath()).containsExactly("Magic Items", "Vorpal Sword");
        assertThat(vorpal.page()).isEqualTo(21);
        assertThat(vorpal.text()).isEqualTo("Vorpal Sword\nA legendary blade.");
        assertThat(vorpal.materialId().toString()).isEqualTo(uploaded.materialId());
        assertThat(vorpal.materialName()).isEqualTo("Livro.pdf");
        assertThat(vorpal.versionId()).isEqualTo(uploaded.versionId());
        assertThat(place(gm, stored.get(1)).path("boxes").get(0).size()).isEqualTo(4);
        assertThat(OwnerContext.callAs(gm.id(), () -> reads.outline(uploaded.versionId()))).hasValueSatisfying(
                sections -> assertThat(sections).containsExactly(new Materials.Section(1, "Magic Items", 19),
                        new Materials.Section(2, "Vorpal Sword", 21)));
        // The ids, the counts and the page: never a text, a title or a path in the log.
        assertThat(logged.list).noneMatch(event -> event.getFormattedMessage().contains("Vorpal")
                || String.valueOf(event.getKeyValuePairs()).contains("Vorpal"));
    }

    @Test
    void aScanWhosePagesAreAllUnreadableStillRunsItsPassagesStepWhichReportsNoneAndNeedsReview() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Branco.pdf", null);
        cleanReport(uploaded.jobId(), claim(uploaded.jobId()), 12, "2", true);
        List<Integer> all = IntStream.rangeClosed(1, 12).boxed().toList();
        for (int i = 0; i < 3; i++) {
            StepRow step = offeredStep(uploaded.versionId());
            JsonNode claim = claim(step.jobId());
            if (step.stage().equals("triage")) {
                report(step, claim, triage(step, 1, 12, all));
            } else {
                report(step, claim, ocr(step, claim, step.firstPage(), step.lastPage(), all));
            }
        }
        assertThat(OwnerContext.callAs(gm.id(), () -> materials.versionFiles(uploaded.versionId())))
                .noneMatch(file -> file.role().equals("text"));

        // Matrix row "All unreadable": the passages step runs over no text file and reports none.
        StepRow passages = offeredStep(uploaded.versionId());
        assertThat(passages.stage()).isEqualTo("passages");
        JsonNode claim = claim(passages.jobId());
        assertThat(claim.path("input").path("texts").isArray()).isTrue();
        assertThat(claim.path("input").path("texts").size()).isZero();
        report(passages, claim, passagesReport());

        assertThat(state(gm, uploaded)).isEqualTo("needs_review");
        assertThat(passages(uploaded.versionId())).isEmpty();
        assertThat(OwnerContext.callAs(gm.id(), () -> reads.outline(uploaded.versionId()))).hasValue(List.of());
        assertThat(json(get(gm, "/api/v1/materials/" + uploaded.materialId())).path("pages").path("unreadable")
                .asInt()).isEqualTo(12);
    }

    @Test
    void aMarkdownUploadsPassagesStepGetsItsJobFromTheTextReportAndKeepsRangesInItsPages() throws Exception {
        Gm gm = signUp();
        byte[] text = "# A\n\nUnder A.\n\n## B\n\nUnder B.\n".getBytes(StandardCharsets.UTF_8);
        Uploaded uploaded = upload(gm, text, "regras.md", null);
        JsonNode claim = claim(uploaded.jobId());
        UUID original = output(uploaded.jobId(), claim, text);
        byte[] pagesJson = ("{\"pages\":[{\"page\":1,\"headingPath\":[\"A\"],\"text\":\"# A\\n\\nUnder A.\\n\\n\","
                + "\"start\":0,\"end\":15},{\"page\":2,\"headingPath\":[\"A\",\"B\"],\"text\":\"## B\\n\\nUnder B.\\n\","
                + "\"start\":15,\"end\":30}]}").getBytes(StandardCharsets.UTF_8);
        UUID pages = output(uploaded.jobId(), claim, pagesJson);

        // The text step's one report gives every page an outcome: its held passages step gets its job at once.
        report(steps(uploaded.versionId()).getFirst(), claim, "{\"pipelineVersion\":\"2\",\"pageTotal\":2,"
                + "\"original\":{\"fileId\":\"" + original + "\"},\"pages\":[" + range(1, 2, "outcome", "read_directly")
                + "],\"texts\":[{\"fileId\":\"" + pages + "\",\"firstPage\":1,\"lastPage\":2}],\"next\":["
                + passagesStep(2) + "]}");

        assertThat(state(gm, uploaded)).isEqualTo("processing");
        StepRow passages = offeredStep(uploaded.versionId());
        assertThat(passages.stage()).isEqualTo("passages");
        assertThat(passages.inputTexts()).isTrue();
        JsonNode passagesClaim = claim(passages.jobId());
        assertThat(passagesClaim.path("input").path("format").asString()).isEqualTo("markdown");
        assertThat(input(passages.jobId(), passagesClaim, "text-" + pages).body()).isEqualTo(pagesJson);
        report(passages, passagesClaim, passagesReport(
                "{\"page\":1,\"sectionPath\":[\"A\"],\"text\":\"# A\\n\\nUnder A.\",\"range\":[0,13]}",
                "{\"page\":2,\"sectionPath\":[\"A\",\"B\"],\"text\":\"## B\\n\\nUnder B.\",\"range\":[0,14]}")
                .replace("\"outline\":[]", "\"outline\":[" + section(1, "A", 1) + "," + section(2, "B", 2) + "]"));

        assertThat(state(gm, uploaded)).isEqualTo("needs_review");
        List<PassageRow> stored = passages(uploaded.versionId());
        assertThat(stored).extracting(PassageRow::page).containsExactly(1, 2);
        // Matrix row "Markdown": path [A, B], and the range in its page.
        JsonNode place = place(gm, stored.get(1));
        assertThat(place.path("sectionPath").toString()).isEqualTo("[\"A\",\"B\"]");
        assertThat(place.path("range").toString()).isEqualTo("[0,14]");
        assertThat(place.has("boxes")).isFalse();
    }

    @Test
    void aPassagesReportThatCannotBeRecordedIsRefusedAndKeepsNothing() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        StepRow passages = passagesStepAfterReading(uploaded, 3);
        JsonNode claim = claim(passages.jobId());
        String title = "T".repeat(1001);
        String deep = "\"x\",".repeat(32) + "\"x\"";
        for (String result : List.of(
                passagesReport(passage(0, "Texto")),
                passagesReport(passage(4, "Texto")),
                passagesReport(passage(1, "")),
                passagesReport("{\"page\":1,\"sectionPath\":[],\"text\":\"" + "a".repeat(16_385)
                        + "\",\"boxes\":[]}"),
                passagesReport("{\"page\":1,\"sectionPath\":[" + deep + "],\"text\":\"a\",\"boxes\":[]}"),
                passagesReport(passage(1, "Texto", title)),
                passagesReport(passage(1, "Texto", "")),
                passagesReport("{\"page\":1,\"sectionPath\":[],\"text\":\"a\"}"),
                passagesReport("{\"page\":1,\"sectionPath\":[],\"text\":\"a\",\"boxes\":[],\"range\":[0,1]}"),
                passagesReport("{\"page\":1,\"text\":\"a\",\"boxes\":[]}"),
                passagesReport("{\"page\":1,\"sectionPath\":[],\"text\":\"a\",\"boxes\":[[0,0,1.5,1]]}"),
                passagesReport("{\"page\":1,\"sectionPath\":[],\"text\":\"a\",\"boxes\":[[0,0,1]]}"),
                passagesReport("{\"page\":1,\"sectionPath\":[],\"text\":\"a\",\"range\":[3,3]}"),
                passagesReport("{\"page\":1,\"sectionPath\":[1],\"text\":\"a\",\"range\":[0,1]}"),
                passagesReport(passage(1, "Texto")).replace("\"outline\":[]", "\"outline\":[" + section(1, title, 1)
                        + "]"),
                passagesReport(passage(1, "Texto")).replace("\"outline\":[]", "\"outline\":[" + section(1, "A", 4)
                        + "]"),
                passagesReport(passage(1, "Texto")).replace("\"outline\":[]", "\"outline\":[" + section(-1, "A", 1)
                        + "]"),
                passagesReport(passage(1, "Texto")).replace("\"outline\":[]", "\"outline\":[" + section(1001, "A", 1)
                        + "]"),
                passagesReport("{\"page\":1,\"sectionPath\":[],\"text\":\"a\",\"range\":[-1,2]}"),
                passagesReport("{\"page\":1,\"sectionPath\":[],\"text\":\"a\",\"range\":[0,1.5]}"),
                passagesReport(passage(1, "Texto")).replace("\"outline\":[]", "\"outline\":{}"))) {
            HttpResponse<String> refused = outcome(passages.jobId(), claim, "{\"outcome\":\"done\",\"result\":" + result
                    + "}");
            assertThat(refused.statusCode()).as(result.length() > 300 ? result.substring(0, 300) : result)
                    .isEqualTo(400);
            assertThat(json(refused).path("code").asString()).isEqualTo("job.result_invalid");
        }
        assertThat(passages(uploaded.versionId())).isEmpty();
        assertThat(materials.outlines.values()).noneMatch(row -> row.versionId().equals(uploaded.versionId()));
        assertThat(jobs.row(passages.jobId()).state()).isEqualTo("running");
        assertThat(state(gm, uploaded)).isEqualTo("processing");

        // At the limits: 16,384 code points (a character outside the BMP counts once), a path of 32, a title of 1,000.
        String limit = "🐉".repeat(16_384);
        String titles = ("\"" + "T".repeat(1000) + "\",").repeat(31) + "\"" + "T".repeat(1000) + "\"";
        report(passages, claim, passagesReport("{\"page\":3,\"sectionPath\":[" + titles + "],\"text\":\"" + limit
                + "\",\"boxes\":[[0,0,1,1]]}"));
        assertThat(state(gm, uploaded)).isEqualTo("needs_review");
        accept(gm, uploaded);
        assertThat(passages(uploaded.versionId())).singleElement()
                .satisfies(row -> assertThat(OwnerContext.callAs(gm.id(), () -> reads.passage(row.id()))
                        .orElseThrow().text()).isEqualTo(limit));
    }

    @Test
    void aPassagesStepIsRefusedATextFileOfAnotherVersionOfTheSameOwner() throws Exception {
        Gm gm = signUp();
        Uploaded other = upload(gm, pdf(MIB), "Outro.pdf", null);
        cleanReport(other.jobId(), claim(other.jobId()), 3, "2");
        StepRow otherTriage = offeredStep(other.versionId());
        report(otherTriage, claim(otherTriage.jobId()), triage(otherTriage, 1, 3, List.of()));
        StepRow otherLayout = offeredStep(other.versionId());
        JsonNode otherClaim = claim(otherLayout.jobId());
        report(otherLayout, otherClaim, layout(otherLayout, otherClaim, 1, 3, List.of()));
        assertThat(state(gm, other)).isEqualTo("needs_review");
        UUID theirs = textFileOf(gm, other);

        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        StepRow passages = passagesStepAfterReading(uploaded, 3);
        JsonNode claim = claim(passages.jobId());

        assertThat(input(passages.jobId(), claim, "text-" + textFileOf(gm, uploaded)).statusCode()).isEqualTo(200);
        // A text file of the same owner's other version is not this step's input.
        assertThat(input(passages.jobId(), claim, "text-" + theirs).statusCode()).isEqualTo(404);
    }

    private UUID textFileOf(Gm gm, Uploaded uploaded) {
        return OwnerContext.callAs(gm.id(), () -> materials.versionFiles(uploaded.versionId())).stream()
                .filter(file -> file.role().equals("text")).findFirst().orElseThrow().fileId();
    }

    @Test
    void passagesReportedAgainReplaceTheVersionsPassagesAndOutline() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        // Two held steps over every text file: each one's report carries the version's whole set.
        StepRow first = passagesStepAfterReading(uploaded, 3, 2);
        report(first, claim(first.jobId()), passagesReport(passage(1, "Um", "A"), passage(2, "Dois", "A"))
                .replace("\"outline\":[]", "\"outline\":[" + section(1, "A", 1) + "]"));
        assertThat(passages(uploaded.versionId())).hasSize(2);
        assertThat(state(gm, uploaded)).isEqualTo("processing");

        // Matrix row "Retried report": the second report leaves one set, its own.
        StepRow second = offeredStep(uploaded.versionId());
        assertThat(second.stage()).isEqualTo("passages");
        report(second, claim(second.jobId()), passagesReport(passage(1, "Um", "B"), passage(2, "Dois", "B"),
                passage(3, "Tres", "B")).replace("\"outline\":[]", "\"outline\":[" + section(1, "B", 1) + "]"));

        List<PassageRow> stored = passages(uploaded.versionId());
        assertThat(stored).extracting(PassageRow::ordinal).containsExactly(0, 1, 2);
        assertThat(state(gm, uploaded)).isEqualTo("needs_review");
        accept(gm, uploaded);
        assertThat(stored).extracting(row -> OwnerContext.callAs(gm.id(), () -> reads.passage(row.id()))
                .orElseThrow().sectionPath()).containsOnly(List.of("B"));
        assertThat(materials.outlines.values()).filteredOn(row -> row.versionId().equals(uploaded.versionId()))
                .hasSize(1);
        assertThat(OwnerContext.callAs(gm.id(), () -> reads.outline(uploaded.versionId()))).hasValue(
                List.of(new Materials.Section(1, "B", 1)));
        assertThat(state(gm, uploaded)).isEqualTo("ready");
    }

    @Test
    void aFinalFailureEndsTheHeldStepWithoutAJob() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        cleanReport(uploaded.jobId(), claim(uploaded.jobId()), 25, "2", true);
        StepRow triage = offeredStep(uploaded.versionId());

        assertThat(outcome(triage.jobId(), claim(triage.jobId()),
                "{\"outcome\":\"failed\",\"code\":\"ingestion.step_failed\",\"retry\":false}").statusCode()).isEqualTo(204);

        assertThat(state(gm, uploaded)).isEqualTo("failed");
        assertThat(passagesStep(uploaded.versionId())).satisfies(held -> {
            assertThat(held.jobId()).isNull();
            assertThat(held.endedAt()).isNotNull();
        });
        assertThat(steps(uploaded.versionId())).allSatisfy(step -> assertThat(step.endedAt()).isNotNull());
        assertThat(jobs.rows.values()).noneMatch(row -> row.state().equals("offered") || row.state().equals("waiting"));
    }

    @Test
    void aRestartEndsTheHeldStepAndRemovesThePassagesAndOutline() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        Cleaned cleaned = cleanReport(uploaded.jobId(), claim(uploaded.jobId()), 25, "2", true);
        StepRow held = passagesStep(uploaded.versionId());
        // Passages an earlier chain left (the restart must not keep them for the new one).
        UUID passageId = UUID.randomUUID();
        UUID outlineId = UUID.randomUUID();
        OwnerContext.runAs(gm.id(), () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            materials.insertPassages(List.of(new PassageRow(passageId, gm.id(), uploaded.versionId(), 0, 1,
                    new byte[] {1}, new byte[] {2}, Instant.now())));
            materials.insertOutline(new OutlineRow(outlineId, gm.id(), uploaded.versionId(), new byte[] {3},
                    Instant.now()));
        }));
        StepRow triage = offeredStep(uploaded.versionId());

        assertThat(outcome(triage.jobId(), claim(triage.jobId()),
                "{\"outcome\":\"failed\",\"code\":\"ingestion.pipeline_changed\",\"retry\":false}").statusCode())
                .isEqualTo(204);

        assertThat(state(gm, uploaded)).isEqualTo("processing");
        assertThat(materials.steps.get(held.id())).satisfies(ended -> {
            assertThat(ended.jobId()).isNull();
            assertThat(ended.endedAt()).isNotNull();
        });
        assertThat(materials.passages).doesNotContainKey(passageId);
        assertThat(materials.outlines).doesNotContainKey(outlineId);
        assertThat(steps(uploaded.versionId())).filteredOn(step -> step.endedAt() == null).singleElement()
                .satisfies(step -> {
                    assertThat(step.stage()).isEqualTo("clean");
                    assertThat(step.inputFileId()).isEqualTo(cleaned.original());
                });
    }

    @Test
    void anotherOwnersPassagesAndOutlineAreNeverSeenNorWritten() throws Exception {
        Gm a = signUp();
        Gm b = signUp();
        Uploaded uploaded = upload(a, pdf(MIB), "Segredo.pdf", null);
        StepRow passages = passagesStepAfterReading(uploaded, 2);
        report(passages, claim(passages.jobId()), passagesReport(passage(1, "Segredo", "Capítulo"))
                .replace("\"outline\":[]", "\"outline\":[" + section(1, "Capítulo", 1) + "]"));
        PassageRow theirs = passages(uploaded.versionId()).getFirst();
        accept(a, uploaded);

        // Matrix row "Two owners": B asks for A's passage id and A's outline.
        OwnerContext.runAs(b.id(), () -> {
            assertThat(reads.passage(theirs.id())).isEmpty();
            assertThat(reads.outline(uploaded.versionId())).isEmpty();
            assertThat(materials.findPassage(theirs.id())).isEmpty();
            assertThat(materials.passages(uploaded.versionId())).isEmpty();
            assertThat(materials.findOutline(uploaded.versionId())).isEmpty();
        });
        OwnerContext.runAs(a.id(), () -> {
            assertThat(reads.passage(theirs.id())).isPresent();
            assertThat(reads.outline(uploaded.versionId())).isPresent();
        });
        // B cannot write a passage or an outline naming A's version, nor remove A's.
        assertThatThrownBy(() -> OwnerContext.runAs(b.id(), () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> materials.insertPassages(List.of(new PassageRow(UUID.randomUUID(),
                        b.id(), uploaded.versionId(), 7, 1, new byte[] {1}, new byte[] {2}, Instant.now()))))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OwnerContext.runAs(b.id(), () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> materials.insertOutline(new OutlineRow(UUID.randomUUID(), b.id(),
                        uploaded.versionId(), new byte[] {3}, Instant.now())))))
                .isInstanceOf(IllegalStateException.class);
        OwnerContext.runAs(b.id(), () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(materials.deletePassages(uploaded.versionId())).isZero();
            assertThat(materials.deleteOutline(uploaded.versionId())).isFalse();
        }));
        assertThat(passages(uploaded.versionId())).hasSize(1);
        // There is no public route to a passage: the material's read names none.
        assertThat(get(b, "/api/v1/materials/" + uploaded.materialId()).statusCode()).isEqualTo(404);
        assertThat(get(a, "/api/v1/materials/" + uploaded.materialId()).body()).doesNotContain("Segredo\"")
                .doesNotContain("Capítulo").doesNotContain(theirs.id().toString());
    }

    // --- Story 1.18 helpers

    private static String passagesStep(int total) {
        return "{\"stage\":\"passages\",\"firstPage\":1,\"lastPage\":" + total + ",\"input\":\"texts\"}";
    }

    /** The version's one passages step. */
    private StepRow passagesStep(UUID versionId) {
        return steps(versionId).stream().filter(step -> step.stage().equals("passages")).reduce((first, second) -> {
            throw new AssertionError("two passages steps");
        }).orElseThrow(() -> new AssertionError("no passages step"));
    }

    private StepRow passagesStepAfterReading(Uploaded uploaded, int total) throws Exception {
        return passagesStepAfterReading(uploaded, total, 1);
    }

    /**
     * A PDF of {@code total} pages (one batch) cleaned with {@code held} passages steps, triaged and read: answers
     * the passages step that is offered.
     */
    private StepRow passagesStepAfterReading(Uploaded uploaded, int total, int held) throws Exception {
        JsonNode claim = claim(uploaded.jobId());
        UUID original = output(uploaded.jobId(), claim, pdf(4000));
        UUID batch = output(uploaded.jobId(), claim, pdf(3000));
        StringBuilder next = new StringBuilder("{\"stage\":\"triage\",\"firstPage\":1,\"lastPage\":" + total
                + ",\"input\":\"" + batch + "\"}");
        for (int i = 0; i < held; i++) {
            next.append(',').append(passagesStep(total));
        }
        report(steps(uploaded.versionId()).getFirst(), claim, "{\"pipelineVersion\":\"2\",\"pageTotal\":" + total
                + ",\"original\":{\"fileId\":\"" + original + "\"},\"batches\":[{\"fileId\":\"" + batch
                + "\",\"firstPage\":1,\"lastPage\":" + total + "}],\"next\":[" + next + "]}");
        StepRow triage = offeredStep(uploaded.versionId());
        report(triage, claim(triage.jobId()), triage(triage, 1, total, List.of()));
        StepRow layout = offeredStep(uploaded.versionId());
        JsonNode layoutClaim = claim(layout.jobId());
        report(layout, layoutClaim, layout(layout, layoutClaim, 1, total, List.of()));
        StepRow passages = offeredStep(uploaded.versionId());
        assertThat(passages.stage()).isEqualTo("passages");
        return passages;
    }

    private static String passagesReport(String... passages) {
        return "{\"pipelineVersion\":\"2\",\"passages\":[" + String.join(",", passages) + "],\"outline\":[],"
                + "\"next\":[]}";
    }

    private static String passage(int page, String text, String... path) {
        StringBuilder titles = new StringBuilder();
        for (String title : path) {
            titles.append(titles.isEmpty() ? "" : ",").append('"').append(title).append('"');
        }
        return "{\"page\":" + page + ",\"sectionPath\":[" + titles + "],\"text\":\"" + text.replace("\n", "\\n")
                + "\",\"boxes\":[[0.1,0.2,0.9,0.3]]}";
    }

    private static String section(int level, String title, int page) {
        return "{\"level\":" + level + ",\"title\":\"" + title + "\",\"page\":" + page + "}";
    }

    private List<PassageRow> passages(UUID versionId) {
        return materials.passages.values().stream().filter(row -> row.versionId().equals(versionId))
                .sorted(Comparator.comparingInt(PassageRow::ordinal)).toList();
    }

    /** A stored passage's place, decrypted as the owner. */
    private JsonNode place(Gm gm, PassageRow row) {
        return JSON.readTree(vault.decrypt(new Binding("passage", "place", row.id(), gm.id()), row.place()));
    }

    // --- Story 1.19: page views

    @Test
    void aDigitalPagesViewIsServedOnTheReviewGroundWithItsLinesItsPassagesAndItsWebpPicture() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        cleanReport(uploaded.jobId(), claim(uploaded.jobId()), 13, "2", true);
        StepRow triage = offeredStep(uploaded.versionId());
        report(triage, claim(triage.jobId()), triage(triage, 1, 13, List.of()));
        StepRow layout = offeredStep(uploaded.versionId());
        JsonNode layoutClaim = claim(layout.jobId());
        List<String> views = new ArrayList<>();
        byte[] page13 = webp(3000);
        for (int page = 1; page <= 13; page++) {
            views.add(pictureView(layout, layoutClaim, page, page == 13 ? page13 : webp(1000 + page),
                    "Plate | 1,500 gp | " + page));
        }
        report(layout, layoutClaim, withViews(layout(layout, layoutClaim, 1, 13, List.of()), views));

        // Matrix row "Not reviewable": the version is still processing (its passages step runs).
        assertThat(state(gm, uploaded)).isEqualTo("processing");
        assertThat(pageOf(gm, uploaded, 13, "review").statusCode()).isEqualTo(404);
        assertThat(pictureOf(gm, uploaded, 13, "review").statusCode()).isEqualTo(404);
        StepRow passages = offeredStep(uploaded.versionId());
        report(passages, claim(passages.jobId()), passagesReport(passage(12, "Doze", "Armor"),
                passage(13, "Armor\nArmor | Cost | AC", "Equipment", "Armor"), passage(13, "Plate", "Armor")));
        assertThat(state(gm, uploaded)).isEqualTo("needs_review");

        // Matrix row "Digital page": page 13's picture size, its lines with boxes and its own passages, in order.
        HttpResponse<String> read = pageOf(gm, uploaded, 13, "review");
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode page = json(read);
        assertThat(page.path("page").asInt()).isEqualTo(13);
        assertThat(page.path("pageTotal").asInt()).isEqualTo(13);
        assertThat(page.path("picture").toString()).isEqualTo("{\"width\":1545,\"height\":2000}");
        assertThat(page.path("lines").toString())
                .isEqualTo("[{\"text\":\"Plate | 1,500 gp | 13\",\"box\":[0.1,0.2,0.9,0.25]}]");
        assertThat(page.path("text").isNull()).isTrue();
        List<PassageRow> stored = passages(uploaded.versionId());
        assertThat(page.path("passages").toString()).isEqualTo("[{\"id\":\"" + stored.get(1).id()
                + "\",\"boxes\":[[0.1,0.2,0.9,0.3]]},{\"id\":\"" + stored.get(2).id() + "\",\"boxes\":[[0.1,0.2,0.9,0.3]]}]");
        HttpResponse<byte[]> picture = pictureOf(gm, uploaded, 13, "review");
        assertThat(picture.statusCode()).isEqualTo(200);
        assertThat(picture.body()).isEqualTo(page13);
        assertThat(picture.headers().firstValue("Content-Type").orElseThrow()).isEqualTo("image/webp");
        assertThat(picture.headers().firstValue("Content-Length").orElseThrow()).isEqualTo("3000");
        assertThat(picture.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(picture.headers().firstValue("X-Content-Type-Options").orElseThrow()).isEqualTo("nosniff");
        assertThat(json(pageOf(gm, uploaded, 1, "review")).path("passages").size()).isZero();

        // Each picture is a file of its page; each view's lines are stored only encrypted.
        assertThat(OwnerContext.callAs(gm.id(), () -> materials.versionFiles(uploaded.versionId())))
                .filteredOn(file -> file.role().equals("picture")).hasSize(13)
                .allSatisfy(file -> assertThat(file.firstPage()).isEqualTo(file.lastPage()));
        assertThat(pageViews(uploaded.versionId())).hasSize(13).allSatisfy(view -> {
            assertThat(new String(view.content(), StandardCharsets.UTF_8)).doesNotContain("Plate");
            assertThat(view.width()).isEqualTo(1545);
        });
        assertThat(logged.list).noneMatch(event -> event.getFormattedMessage().contains("Plate")
                || String.valueOf(event.getKeyValuePairs()).contains("Plate"));

        // Matrix row "Ground": none or another one is page.ground_unknown; a page outside 1..13 is not found.
        for (String ground : new String[] {null, "scope", "reference", "REVIEW", ""}) {
            HttpResponse<String> refused = pageOf(gm, uploaded, 13, ground);
            assertThat(refused.statusCode()).as(ground).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).isEqualTo("page.ground_unknown");
            assertThat(pictureOf(gm, uploaded, 13, ground).statusCode()).as(ground).isEqualTo(400);
        }
        for (String number : List.of("0", "14", "-1", "013", "1.0", "x", "99999999")) {
            HttpResponse<String> missing = get(gm, "/api/v1/materials/" + uploaded.materialId() + "/pages/" + number
                    + "?ground=review");
            assertThat(missing.statusCode()).as(number).isEqualTo(404);
            assertThat(json(missing).path("code").asString()).isEqualTo("request.not_found");
        }
    }

    @Test
    void aBlankScanHasAPictureWithNoLinesAndACrashedPageHasNoViewAtAll() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Scan.pdf", null);
        clean(uploaded, 10, "2");
        StepRow triage = offeredStep(uploaded.versionId());
        report(triage, claim(triage.jobId()), triage(triage, 1, 10, List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));
        StepRow ocr = offeredStep(uploaded.versionId());
        assertThat(outcome(ocr.jobId(), claim(ocr.jobId()), "{\"outcome\":\"failed\",\"code\":\"ingestion.step_failed\"}")
                .statusCode()).isEqualTo(204);
        // The retried step reads page by page: page 9's child crashed (unreadable, no view), page 7 is a blank scan.
        JsonNode second = claim(ocr.jobId());
        List<String> views = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            if (page == 7) {
                UUID blank = output(ocr.jobId(), second, webp(900));
                views.add("{\"page\":7,\"picture\":{\"fileId\":\"" + blank + "\",\"width\":1545,\"height\":2000},"
                        + "\"lines\":[]}");
            } else if (page != 9) {
                views.add(pictureView(ocr, second, page, webp(1200), "Capítulo " + page));
            }
        }
        report(ocr, second, withViews(ocr(ocr, second, 1, 10, List.of(7, 9)), views));
        assertThat(state(gm, uploaded)).isEqualTo("needs_review");

        // Matrix row "Blank scan": its picture, no lines.
        JsonNode blank = json(pageOf(gm, uploaded, 7, "review"));
        assertThat(blank.path("picture").path("width").asInt()).isEqualTo(1545);
        assertThat(blank.path("lines").size()).isZero();
        assertThat(pictureOf(gm, uploaded, 7, "review").statusCode()).isEqualTo(200);
        // Matrix row "Crashed page": no view, so no picture; from Story 1.20 the page answers as unreadable, empty.
        HttpResponse<String> crashed = pageOf(gm, uploaded, 9, "review");
        assertThat(crashed.statusCode()).isEqualTo(200);
        assertThat(json(crashed).path("picture").isNull()).isTrue();
        assertThat(json(crashed).path("text").isNull()).isTrue();
        assertThat(json(crashed).path("lines").size()).isZero();
        assertThat(json(crashed).path("passages").size()).isZero();
        assertThat(json(crashed).path("outcome").asString()).isEqualTo("unreadable");
        assertThat(blank.path("outcome").asString()).isEqualTo("unreadable");
        assertThat(json(pageOf(gm, uploaded, 10, "review")).path("outcome").asString()).isEqualTo("read_by_ocr");
        assertThat(pictureOf(gm, uploaded, 9, "review").statusCode()).isEqualTo(404);
        // Matrix row "Retried batch": one view per page, the retry's.
        assertThat(pageViews(uploaded.versionId())).extracting(PageViewRow::page)
                .containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8, 10);
        assertThat(json(pageOf(gm, uploaded, 10, "review")).path("lines").get(0).path("text").asString())
                .isEqualTo("Capítulo 10");
    }

    @Test
    void aTextPageIsServedAsItsTextWithNoPictureAndItsPassagesRanges() throws Exception {
        Gm gm = signUp();
        byte[] text = "# A\n\nUnder A.\n\n## B\n\nUnder B.\n".getBytes(StandardCharsets.UTF_8);
        Uploaded uploaded = upload(gm, text, "regras.md", null);
        JsonNode claim = claim(uploaded.jobId());
        UUID original = output(uploaded.jobId(), claim, text);
        UUID pages = output(uploaded.jobId(), claim, "{\"pages\":[]}".getBytes(StandardCharsets.UTF_8));
        report(steps(uploaded.versionId()).getFirst(), claim, "{\"pipelineVersion\":\"2\",\"pageTotal\":2,"
                + "\"original\":{\"fileId\":\"" + original + "\"},\"pages\":[" + range(1, 2, "outcome", "read_directly")
                + "],\"texts\":[{\"fileId\":\"" + pages + "\",\"firstPage\":1,\"lastPage\":2}],"
                + "\"pageViews\":[{\"page\":1,\"text\":\"# A\\n\\nUnder A.\\n\\n\"},"
                + "{\"page\":2,\"text\":\"## B\\n\\nUnder B.\\n\"}],\"next\":[" + passagesStep(2) + "]}");
        StepRow passages = offeredStep(uploaded.versionId());
        report(passages, claim(passages.jobId()), passagesReport(
                "{\"page\":2,\"sectionPath\":[\"A\",\"B\"],\"text\":\"## B\\n\\nUnder B.\",\"range\":[0,14]}"));
        assertThat(state(gm, uploaded)).isEqualTo("needs_review");

        // Matrix row "Text page": no picture, its text, and its passage's range.
        JsonNode page = json(pageOf(gm, uploaded, 2, "review"));
        assertThat(page.path("picture").isNull()).isTrue();
        assertThat(page.path("text").asString()).isEqualTo("## B\n\nUnder B.\n");
        assertThat(page.path("lines").size()).isZero();
        assertThat(page.path("pageTotal").asInt()).isEqualTo(2);
        assertThat(page.path("passages").toString()).isEqualTo("[{\"id\":\""
                + passages(uploaded.versionId()).getFirst().id() + "\",\"range\":[0,14]}]");
        HttpResponse<byte[]> picture = pictureOf(gm, uploaded, 2, "review");
        assertThat(picture.statusCode()).isEqualTo(404);
        assertThat(new String(picture.body(), StandardCharsets.UTF_8)).contains("request.not_found");
        assertThat(pageViews(uploaded.versionId())).allSatisfy(view -> assertThat(view.pictureFileId()).isNull());
    }

    @Test
    void aReportWithAnInvalidPageViewIsRefusedAndKeepsNothing() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        clean(uploaded, 4, "2");
        StepRow triage = offeredStep(uploaded.versionId());
        report(triage, claim(triage.jobId()), triage(triage, 1, 4, List.of()));
        StepRow layout = offeredStep(uploaded.versionId());
        JsonNode claim = claim(layout.jobId());
        UUID picture = output(layout.jobId(), claim, webp(1000));
        String layoutReport = layout(layout, claim, 1, 4, List.of());
        // A picture another job sent back (the triage step's), never this claim's.
        UUID otherJobs = OwnerContext.callAs(gm.id(), () -> materials.versionFiles(uploaded.versionId())).stream()
                .filter(file -> file.role().equals("batch")).findFirst().orElseThrow().fileId();
        String good = "\"picture\":{\"fileId\":\"" + picture + "\",\"width\":10,\"height\":10}";
        String line = "{\"text\":\"a\",\"box\":[0,0,1,1]}";
        List<String> refused = List.of(
                // Matrix row "Bad report": a box outside 0..1, and a picture file not this claim's.
                "{\"page\":1," + good + ",\"lines\":[{\"text\":\"a\",\"box\":[0,0,1.2,1]}]}",
                "{\"page\":1,\"picture\":{\"fileId\":\"" + otherJobs + "\",\"width\":10,\"height\":10},\"lines\":[]}",
                "{\"page\":1,\"picture\":{\"fileId\":\"" + UUID.randomUUID() + "\",\"width\":10,\"height\":10},"
                        + "\"lines\":[]}",
                "{\"page\":5," + good + ",\"lines\":[]}",
                "{\"page\":0," + good + ",\"lines\":[]}",
                "{\"page\":1," + good + ",\"lines\":[]},{\"page\":1,\"text\":\"b\"}",
                "{\"page\":1,\"picture\":{\"fileId\":\"" + picture + "\",\"width\":0,\"height\":10},\"lines\":[]}",
                "{\"page\":1,\"picture\":{\"fileId\":\"" + picture + "\",\"width\":10,\"height\":10001},\"lines\":[]}",
                "{\"page\":1," + good + "}",
                "{\"page\":1," + good + ",\"lines\":[{\"text\":\"\",\"box\":[0,0,1,1]}]}",
                "{\"page\":1," + good + ",\"lines\":[{\"text\":\"" + "a".repeat(4097) + "\",\"box\":[0,0,1,1]}]}",
                "{\"page\":1," + good + ",\"lines\":[{\"text\":\"a\",\"box\":[0,0,1]}]}",
                "{\"page\":1," + good + ",\"lines\":[" + String.join(",", java.util.Collections.nCopies(10_001, line))
                        + "]}",
                "{\"page\":1," + good + ",\"lines\":[],\"text\":\"a\"}",
                "{\"page\":1}",
                "{\"page\":1,\"text\":\"" + "a".repeat(16_385) + "\"}",
                "{\"page\":1,\"text\":3}",
                "\"page\"");
        for (String views : refused) {
            HttpResponse<String> answer = outcome(layout.jobId(), claim, "{\"outcome\":\"done\",\"result\":"
                    + layoutReport.replace("\"next\":[]", "\"pageViews\":[" + views + "],\"next\":[]") + "}");
            assertThat(answer.statusCode()).as(views.length() > 200 ? views.substring(0, 200) : views).isEqualTo(400);
            assertThat(json(answer).path("code").asString()).isEqualTo("job.result_invalid");
        }
        assertThat(pageViews(uploaded.versionId())).isEmpty();
        assertThat(outcomes(uploaded.versionId())).isEmpty();
        assertThat(OwnerContext.callAs(gm.id(), () -> materials.versionFiles(uploaded.versionId())))
                .noneMatch(file -> file.role().equals("picture") || file.role().equals("text"));
        assertThat(logged.list).anyMatch(event -> String.valueOf(event.getKeyValuePairs())
                .contains("ingestion.page_view_invalid"));

        // The same report with valid views is recorded: page 4 a text page is fine, at the limits.
        report(layout, claim, layoutReport.replace("\"next\":[]", "\"pageViews\":[{\"page\":1," + good
                + ",\"lines\":[" + line + "]},{\"page\":4,\"text\":\"" + "a".repeat(16_384) + "\"}],\"next\":[]"));
        assertThat(pageViews(uploaded.versionId())).extracting(PageViewRow::page).containsExactlyInAnyOrder(1, 4);
    }

    @Test
    void aRestartRemovesThePageViewsAndTheirPictures() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        Cleaned cleaned = clean(uploaded, 25, "2");
        StepRow triage = offeredStep(uploaded.versionId());
        report(triage, claim(triage.jobId()), triage(triage, 1, 20, List.of()));
        StepRow layout = offeredStep(uploaded.versionId());
        JsonNode claim = claim(layout.jobId());
        report(layout, claim, withViews(layout(layout, claim, 1, 20, List.of()),
                List.of(pictureView(layout, claim, 3, webp(1500), "Três"))));
        assertThat(pageViews(uploaded.versionId())).hasSize(1);
        UUID picture = pageViews(uploaded.versionId()).getFirst().pictureFileId();
        StepRow second = offeredStep(uploaded.versionId());

        assertThat(outcome(second.jobId(), claim(second.jobId()),
                "{\"outcome\":\"failed\",\"code\":\"ingestion.pipeline_changed\",\"retry\":false}").statusCode())
                .isEqualTo(204);

        assertThat(pageViews(uploaded.versionId())).isEmpty();
        assertThat(OwnerContext.callAs(gm.id(), () -> materials.versionFiles(uploaded.versionId())))
                .extracting(VersionFileRow::fileId).containsExactly(cleaned.original());
        assertThat(materials.files.values()).noneMatch(file -> file.fileId().equals(picture));
        assertThat(logged.list).anyMatch(event -> event.getFormattedMessage().equals("ingestion.restarted")
                && event.getKeyValuePairs().stream().anyMatch(pair -> pair.key.equals("pageViews")
                        && Integer.valueOf(1).equals(pair.value)));
    }

    @Test
    void aPictureIsNeverTheInputOfANextStep() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        clean(uploaded, 25, "2");
        StepRow triage = offeredStep(uploaded.versionId());
        report(triage, claim(triage.jobId()), triage(triage, 1, 20, List.of()));
        StepRow layout = offeredStep(uploaded.versionId());
        JsonNode claim = claim(layout.jobId());
        report(layout, claim, withViews(layout(layout, claim, 1, 20, List.of()),
                List.of(pictureView(layout, claim, 3, webp(1500), "Três"))));
        UUID picture = pageViews(uploaded.versionId()).getFirst().pictureFileId();
        StepRow second = offeredStep(uploaded.versionId());
        int steps = steps(uploaded.versionId()).size();

        HttpResponse<String> refused = outcome(second.jobId(), claim(second.jobId()), "{\"outcome\":\"done\",\"result\":"
                + "{\"pipelineVersion\":\"2\",\"next\":[{\"stage\":\"layout\",\"firstPage\":21,\"lastPage\":25,"
                + "\"input\":\"" + picture + "\"}]}}");

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
        assertThat(json(refused).path("code").asString()).isEqualTo("job.result_invalid");
        assertThat(steps(uploaded.versionId())).hasSize(steps);
    }

    @Test
    void anotherOwnersPageAndPictureAreNotFoundAndLeakNoId() throws Exception {
        Gm a = signUp();
        Gm b = signUp();
        Uploaded uploaded = upload(a, pdf(MIB), "Segredo.pdf", null);
        clean(uploaded, 2, "2");
        StepRow triage = offeredStep(uploaded.versionId());
        report(triage, claim(triage.jobId()), triage(triage, 1, 2, List.of()));
        StepRow layout = offeredStep(uploaded.versionId());
        JsonNode claim = claim(layout.jobId());
        report(layout, claim, withViews(layout(layout, claim, 1, 2, List.of()),
                List.of(pictureView(layout, claim, 1, webp(1000), "Segredo"))));
        assertThat(state(a, uploaded)).isEqualTo("needs_review");
        assertThat(pageOf(a, uploaded, 1, "review").statusCode()).isEqualTo(200);
        PageViewRow theirs = pageViews(uploaded.versionId()).getFirst();

        // Matrix row "Two owners": B asks A's page and picture: the same not found as an unknown or malformed id.
        JsonNode expected = json(get(b, "/api/v1/materials/" + UUID.randomUUID() + "/pages/1?ground=review"));
        for (String material : List.of(uploaded.materialId(), UUID.randomUUID().toString(), "not-a-uuid")) {
            for (String path : List.of("/api/v1/materials/" + material + "/pages/1?ground=review",
                    "/api/v1/materials/" + material + "/pages/1/picture?ground=review")) {
                HttpResponse<String> answer = get(b, path);
                assertThat(answer.statusCode()).as(path).isEqualTo(404);
                assertThat(json(answer).path("code").asString()).isEqualTo(expected.path("code").asString());
                assertThat(answer.body()).doesNotContain(uploaded.materialId()).doesNotContain("Segredo")
                        .doesNotContain(theirs.id().toString()).doesNotContain(uploaded.versionId().toString());
            }
        }
        OwnerContext.runAs(b.id(), () -> {
            assertThat(materials.findPageView(uploaded.versionId(), 1)).isEmpty();
            assertThat(reads.page(UUID.fromString(uploaded.materialId()), 1, Materials.Ground.REVIEW)).isEmpty();
            assertThat(reads.picture(UUID.fromString(uploaded.materialId()), 1, Materials.Ground.REVIEW)).isEmpty();
        });
        // B cannot write a page view naming A's version, nor remove A's.
        assertThatThrownBy(() -> OwnerContext.runAs(b.id(), () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> materials.insertPageView(new PageViewRow(UUID.randomUUID(), b.id(),
                        uploaded.versionId(), 2, null, null, null, new byte[] {1}, Instant.now())))))
                .isInstanceOf(IllegalStateException.class);
        OwnerContext.runAs(b.id(), () -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                assertThat(materials.deletePageViews(uploaded.versionId())).isZero()));
        assertThat(pageViews(uploaded.versionId())).hasSize(1);
    }

    @Test
    void aFailedVersionServesNoPage() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        clean(uploaded, 25, "2");
        StepRow triage = offeredStep(uploaded.versionId());
        report(triage, claim(triage.jobId()), triage(triage, 1, 20, List.of()));
        StepRow layout = offeredStep(uploaded.versionId());
        JsonNode claim = claim(layout.jobId());
        report(layout, claim, withViews(layout(layout, claim, 1, 20, List.of()),
                List.of(pictureView(layout, claim, 1, webp(1000), "Um"))));
        StepRow next = offeredStep(uploaded.versionId());
        assertThat(outcome(next.jobId(), claim(next.jobId()),
                "{\"outcome\":\"failed\",\"code\":\"ingestion.step_failed\",\"retry\":false}").statusCode())
                .isEqualTo(204);

        assertThat(state(gm, uploaded)).isEqualTo("failed");
        assertThat(pageViews(uploaded.versionId())).hasSize(1);
        assertThat(pageOf(gm, uploaded, 1, "review").statusCode()).isEqualTo(404);
        assertThat(pictureOf(gm, uploaded, 1, "review").statusCode()).isEqualTo(404);
    }

    // --- Story 1.19 helpers

    /** A WebP file's first bytes, then zeros: the backend never parses a picture. */
    private static byte[] webp(int size) {
        byte[] content = new byte[size];
        byte[] head = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
        System.arraycopy(head, 0, content, 0, head.length);
        return content;
    }

    /** A picture page's view: a picture sent back on this claim, 1545 by 2000 pixels, and one line. */
    private String pictureView(StepRow step, JsonNode claim, int page, byte[] picture, String line) throws Exception {
        UUID file = output(step.jobId(), claim, picture);
        return "{\"page\":" + page + ",\"picture\":{\"fileId\":\"" + file + "\",\"width\":1545,\"height\":2000},"
                + "\"lines\":[{\"text\":\"" + line + "\",\"box\":[0.1,0.2,0.9,0.25]}]}";
    }

    /** A reading report (which ends with an empty {@code next}) with these page views. */
    private static String withViews(String report, List<String> views) {
        return report.replace("\"next\":[]", "\"pageViews\":[" + String.join(",", views) + "],\"next\":[]");
    }

    private HttpResponse<String> pageOf(Gm gm, Uploaded uploaded, int page, String ground) throws Exception {
        return get(gm, "/api/v1/materials/" + uploaded.materialId() + "/pages/" + page
                + (ground == null ? "" : "?ground=" + ground));
    }

    private HttpResponse<byte[]> pictureOf(Gm gm, Uploaded uploaded, int page, String ground) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/materials/" + uploaded.materialId() + "/pages/" + page
                        + "/picture" + (ground == null ? "" : "?ground=" + ground)))
                .header("Authorization", "Bearer " + CREDENTIAL).header("X-Session-Token", gm.session())
                .header("Accept", "image/avif,image/webp,*/*").GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private List<PageViewRow> pageViews(UUID versionId) {
        return materials.pageViews.values().stream().filter(row -> row.versionId().equals(versionId))
                .sorted(Comparator.comparingInt(PageViewRow::page)).toList();
    }

    // --- Story 1.20: the quality report and the GM's actions

    @Test
    void acceptMakesTheVersionReadyOnceAndOnlyThenAreItsPassagesRead() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        StepRow passages = passagesStepAfterReading(uploaded, 2);
        report(passages, claim(passages.jobId()), passagesReport(passage(1, "Texto", "A"))
                .replace("\"outline\":[]", "\"outline\":[" + section(1, "A", 1) + "]"));
        assertThat(state(gm, uploaded)).isEqualTo("needs_review");
        UUID passageId = passages(uploaded.versionId()).getFirst().id();

        // Matrix row "Nothing to reprocess": no page is unreadable, so no reprocess, and a post of it is refused.
        assertThat(actions(gm, uploaded)).containsExactly("accept", "discard");
        refused(act(gm, uploaded, "reprocess"), "material.action_unavailable");
        refused(act(gm, uploaded, "retry"), "material.action_unavailable");
        assertThat(state(gm, uploaded)).isEqualTo("needs_review");
        // Matrix row "Passage gate": a version that needs review gives no passage.
        assertThat(OwnerContext.callAs(gm.id(), () -> reads.passage(passageId))).isEmpty();

        // Matrix row "Accept".
        HttpResponse<String> accepted = act(gm, uploaded, "accept");
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(accepted.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(json(accepted).path("state").asString()).isEqualTo("ready");
        assertThat(json(accepted).path("actions").size()).isZero();
        assertThat(json(accepted).path("id").asString()).isEqualTo(uploaded.materialId());
        assertThat(OwnerContext.callAs(gm.id(), () -> reads.passage(passageId))).isPresent();
        // The report stays readable, and so do the pages.
        assertThat(get(gm, "/api/v1/materials/" + uploaded.materialId() + "/report").statusCode()).isEqualTo(200);

        // Matrix row "Accept twice"; and a ready version is never reprocessed nor discarded in place (AD-15).
        for (String action : List.of("accept", "reprocess", "retry", "discard")) {
            refused(act(gm, uploaded, action), "material.action_unavailable");
        }
        assertThat(state(gm, uploaded)).isEqualTo("ready");
        assertThat(logged.list).noneMatch(event -> event.getFormattedMessage().contains("Texto")
                || String.valueOf(event.getKeyValuePairs()).contains("Texto"));
    }

    @Test
    void acceptingANewerVersionSupersedesTheReadyOneBeforeIt() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        clean(uploaded, 1, "2");
        readUntilReview(gm, uploaded, List.of(), List.of());
        accept(gm, uploaded);
        // A newer version of the same material that needs review (only Story 3.x creates one through the API).
        UUID newer = UUID.randomUUID();
        Instant later = materials.versions.get(uploaded.versionId()).createdAt().plusSeconds(60);
        OwnerContext.runAs(gm.id(), () -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                materials.insertVersion(new VersionRow(newer, gm.id(), UUID.fromString(uploaded.materialId()),
                        "needs_review", "pdf", 1L, later, later, 1, "2", null))));
        assertThat(actions(gm, uploaded)).containsExactly("accept", "discard");

        accept(gm, uploaded);

        assertThat(materials.versions.get(newer).state()).isEqualTo("ready");
        assertThat(materials.versions.get(uploaded.versionId()).state()).isEqualTo("superseded");
    }

    @Test
    void reprocessReadsTheUnreadablePagesAgainByCopiesOfTheStepsThatReadThem() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        List<UUID> batches = cleanIn(uploaded, 45, true, 30, 45);
        readUntilReview(gm, uploaded, List.of(9, 40, 41), List.of(8, 9));
        assertThat(actions(gm, uploaded)).containsExactly("accept", "reprocess", "discard");
        UUID pictureOf9 = pageViews(uploaded.versionId()).stream().filter(view -> view.page() == 9).findFirst()
                .orElseThrow().pictureFileId();

        // The report: the outline, the unreadable pages as runs, and the facts.
        HttpResponse<String> answer = get(gm, "/api/v1/materials/" + uploaded.materialId() + "/report");
        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(json(answer).toString()).isEqualTo("{\"outline\":[{\"level\":1,\"title\":\"A\",\"page\":1}],"
                + "\"unreadable\":[{\"firstPage\":9,\"lastPage\":9},{\"firstPage\":40,\"lastPage\":41}],"
                + "\"warnings\":[{\"code\":\"report.pages_unreadable\",\"count\":3},"
                + "{\"code\":\"report.pages_unopened\",\"count\":2}]}");
        int stepsBefore = steps(uploaded.versionId()).size();

        // Matrix row "Reprocess": pages 9 and 40-41, read by two layout steps, give two new steps.
        HttpResponse<String> reprocessed = act(gm, uploaded, "reprocess");
        assertThat(reprocessed.statusCode()).as(reprocessed.body()).isEqualTo(200);
        assertThat(json(reprocessed).path("state").asString()).isEqualTo("processing");
        assertThat(json(reprocessed).path("actions").size()).isZero();
        List<StepRow> added = steps(uploaded.versionId()).stream().filter(step -> step.endedAt() == null)
                .sorted(Comparator.comparing(StepRow::firstPage)).toList();
        assertThat(steps(uploaded.versionId())).hasSize(stepsBefore + 3);
        assertThat(added).extracting(StepRow::stage, StepRow::firstPage, StepRow::lastPage, StepRow::inputFileId,
                        StepRow::inputTexts)
                .containsExactly(tuple("passages", 1, 45, null, true), tuple("layout", 9, 9, batches.get(0), false),
                        tuple("layout", 40, 41, batches.get(1), false));
        assertThat(added.getFirst().jobId()).isNull();
        // Its unreadable outcomes go, with those pages' views and pictures, and the passages and outline (held).
        assertThat(outcomes(uploaded.versionId())).noneMatch(range -> range.value().equals("unreadable"));
        assertThat(pageViews(uploaded.versionId())).extracting(PageViewRow::page).containsExactly(8);
        assertThat(materials.files.values()).noneMatch(file -> file.fileId().equals(pictureOf9));
        assertThat(files.fileExists(gm.id(), pictureOf9)).isFalse();
        assertThat(passages(uploaded.versionId())).isEmpty();
        assertThat(materials.outlines.values()).noneMatch(row -> row.versionId().equals(uploaded.versionId()));
        assertThat(get(gm, "/api/v1/materials/" + uploaded.materialId() + "/report").statusCode()).isEqualTo(404);

        // The copies read the pages in one child (attempt 1), and the chain's end brings the version back.
        StepRow nine = offeredStep(uploaded.versionId());
        JsonNode nineClaim = claim(nine.jobId());
        assertThat(nineClaim.path("attempt").asInt()).isEqualTo(1);
        assertThat(nineClaim.path("input").path("firstPage").asInt()).isEqualTo(9);
        assertThat(nineClaim.path("input").path("inputFileId").asString()).isEqualTo(batches.get(0).toString());
        report(nine, nineClaim, withViews(layout(nine, nineClaim, 9, 9, List.of()),
                List.of(pictureView(nine, nineClaim, 9, webp(1000), "Nove"))));
        readUntilReview(gm, uploaded, List.of(), List.of());
        assertThat(json(get(gm, "/api/v1/materials/" + uploaded.materialId() + "/report")).path("unreadable").size())
                .isZero();
        assertThat(actions(gm, uploaded)).containsExactly("accept", "discard");
        assertThat(json(pageOf(gm, uploaded, 9, "review")).path("lines").get(0).path("text").asString())
                .isEqualTo("Nove");
        assertThat(passages(uploaded.versionId())).hasSize(1);
    }

    @Test
    void reprocessAfterATriageCrashCopiesTheTriageStep() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Scan.pdf", null);
        Cleaned cleaned = clean(uploaded, 10, "2");
        StepRow triage = offeredStep(uploaded.versionId());
        // The retried triage read page by page, and page 7's child crashed: no layer, unreadable.
        report(triage, claim(triage.jobId()), "{\"pipelineVersion\":\"2\",\"layers\":[" + range(1, 6, "layer", "text")
                + "," + range(8, 10, "layer", "text") + "],\"pages\":[" + range(7, 7, "outcome", "unreadable") + "],"
                + "\"next\":[" + nextStep(triage, "layout", 1, 6) + "," + nextStep(triage, "layout", 8, 10) + "]}");
        readUntilReview(gm, uploaded, List.of(), List.of());
        assertThat(json(get(gm, "/api/v1/materials/" + uploaded.materialId() + "/report")).path("warnings").toString())
                .isEqualTo("[{\"code\":\"report.pages_unreadable\",\"count\":1},"
                        + "{\"code\":\"report.pages_unopened\",\"count\":1},"
                        + "{\"code\":\"report.outline_empty\",\"count\":0}]");

        // Matrix row "Reprocess, triage crash": page 7 has no layer, so only triage covers it.
        assertThat(act(gm, uploaded, "reprocess").statusCode()).isEqualTo(200);

        assertThat(steps(uploaded.versionId())).filteredOn(step -> step.endedAt() == null).singleElement()
                .satisfies(step -> {
                    assertThat(step.stage()).isEqualTo("triage");
                    assertThat(step.firstPage()).isEqualTo(7);
                    assertThat(step.lastPage()).isEqualTo(7);
                    assertThat(step.inputFileId()).isEqualTo(cleaned.batches().getFirst());
                });
        readUntilReview(gm, uploaded, List.of(), List.of());
        assertThat(layers(uploaded.versionId())).extracting(PageRangeRow::firstPage).contains(7);
        assertThat(actions(gm, uploaded)).containsExactly("accept", "discard");
    }

    @Test
    void aBlankPageComesBackUnreadableAndReprocessPastTheStepCapIsRefusedAndChangesNothing() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Branco.pdf", null);
        clean(uploaded, 1, "2");
        readUntilReview(gm, uploaded, List.of(1), List.of());
        // One page: at most 4 x 1 + 1 = 5 steps. Clean, triage and layout are 3; each reprocess adds one.
        for (int round = 0; round < 2; round++) {
            assertThat(act(gm, uploaded, "reprocess").statusCode()).isEqualTo(200);
            readUntilReview(gm, uploaded, List.of(1), List.of());
            assertThat(json(get(gm, "/api/v1/materials/" + uploaded.materialId() + "/report")).path("unreadable")
                    .toString()).isEqualTo("[{\"firstPage\":1,\"lastPage\":1}]");
        }
        assertThat(steps(uploaded.versionId())).hasSize(5);

        HttpResponse<String> answer = act(gm, uploaded, "reprocess");

        refused(answer, "ingestion.too_many_steps");
        assertThat(steps(uploaded.versionId())).hasSize(5);
        assertThat(state(gm, uploaded)).isEqualTo("needs_review");
        assertThat(outcomes(uploaded.versionId())).extracting(PageRangeRow::value).containsExactly("unreadable");
    }

    @Test
    void retryStartsAFailedVersionAgainFromItsOriginal() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        Cleaned cleaned = clean(uploaded, 25, "2");
        StepRow triage = offeredStep(uploaded.versionId());
        report(triage, claim(triage.jobId()), triage(triage, triage.firstPage(), triage.lastPage(), List.of()));
        StepRow next = offeredStep(uploaded.versionId());
        assertThat(outcome(next.jobId(), claim(next.jobId()),
                "{\"outcome\":\"failed\",\"code\":\"ingestion.step_failed\",\"retry\":false}").statusCode())
                .isEqualTo(204);
        assertThat(state(gm, uploaded)).isEqualTo("failed");
        assertThat(actions(gm, uploaded)).containsExactly("retry", "discard");
        assertThat(get(gm, "/api/v1/materials/" + uploaded.materialId() + "/report").statusCode()).isEqualTo(404);

        // Matrix row "Retry": processing, from the original.
        HttpResponse<String> retried = act(gm, uploaded, "retry");

        assertThat(retried.statusCode()).as(retried.body()).isEqualTo(200);
        assertThat(json(retried).path("state").asString()).isEqualTo("processing");
        assertThat(json(retried).path("actions").size()).isZero();
        assertThat(json(retried).has("failure")).isFalse();
        StepRow first = offeredStep(uploaded.versionId());
        assertThat(first.stage()).isEqualTo("clean");
        assertThat(first.inputFileId()).isEqualTo(cleaned.original());
        assertThat(layers(uploaded.versionId())).isEmpty();
        assertThat(OwnerContext.callAs(gm.id(), () -> materials.versionFiles(uploaded.versionId())))
                .extracting(VersionFileRow::role).containsExactly("original");
        assertThat(materials.versions.get(uploaded.versionId()).pipelineVersion()).isNull();
        JsonNode claim = claim(first.jobId());
        assertThat(claim.path("input").path("inputFileId").asString()).isEqualTo(cleaned.original().toString());
        cleanReport(first.jobId(), claim, 25, "3");
        assertThat(state(gm, uploaded)).isEqualTo("processing");
        refused(act(gm, uploaded, "retry"), "material.action_unavailable");
    }

    @Test
    void retryWithoutAnOriginalStartsAgainFromTheRawUpload() throws Exception {
        Gm gm = signUp();
        byte[] content = pdf(MIB + 5);
        Uploaded uploaded = upload(gm, content, "Livro.pdf", null);
        assertThat(outcome(uploaded.jobId(), claim(uploaded.jobId()),
                "{\"outcome\":\"failed\",\"code\":\"ingestion.step_failed\",\"retry\":false}").statusCode())
                .isEqualTo(204);
        assertThat(actions(gm, uploaded)).containsExactly("retry", "discard");

        HttpResponse<String> retried = act(gm, uploaded, "retry");
        assertThat(retried.statusCode()).as(retried.body()).isEqualTo(200);

        StepRow first = offeredStep(uploaded.versionId());
        assertThat(first.stage()).isEqualTo("clean");
        assertThat(first.inputFileId()).isEqualTo(uploaded.uploadId());
        JsonNode claim = claim(first.jobId());
        HttpResponse<byte[]> source = input(first.jobId(), claim, "source");
        assertThat(source.statusCode()).isEqualTo(200);
        assertThat(source.body()).isEqualTo(content);
    }

    @Test
    void aVersionThatNeedsAPasswordCanOnlyBeDiscarded() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Fechado.pdf", null);
        assertThat(outcome(uploaded.jobId(), claim(uploaded.jobId()),
                "{\"outcome\":\"failed\",\"code\":\"ingestion.password_required\",\"retry\":false}").statusCode())
                .isEqualTo(204);

        // Matrix row "Retry impossible": nothing remains to start from.
        assertThat(actions(gm, uploaded)).containsExactly("discard");
        refused(act(gm, uploaded, "retry"), "material.action_unavailable");
        assertThat(state(gm, uploaded)).isEqualTo("failed");

        HttpResponse<String> discarded = act(gm, uploaded, "discard");
        assertThat(discarded.statusCode()).isEqualTo(200);
        assertThat(json(discarded).path("state").asString()).isEqualTo("discarded");
        assertThat(json(discarded).path("actions").size()).isZero();
    }

    @Test
    void discardRemovesEverythingTheVersionHeldButItsSteps() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        cleanReport(uploaded.jobId(), claim(uploaded.jobId()), 3, "2", true);
        readUntilReview(gm, uploaded, List.of(3), List.of(1, 2, 3));
        List<VersionFileRow> stored = OwnerContext.callAs(gm.id(), () -> materials.versionFiles(uploaded.versionId()));
        assertThat(stored).extracting(VersionFileRow::role).contains("original", "batch", "text", "picture");
        UUID passageId = passages(uploaded.versionId()).getFirst().id();
        int steps = steps(uploaded.versionId()).size();

        // Matrix row "Discard": nothing of the version is left but its row and its steps.
        HttpResponse<String> discarded = act(gm, uploaded, "discard");

        assertThat(discarded.statusCode()).as(discarded.body()).isEqualTo(200);
        assertThat(json(discarded).path("state").asString()).isEqualTo("discarded");
        assertNothingLeft(gm, uploaded, stored);
        assertThat(steps(uploaded.versionId())).hasSize(steps);
        // Matrix row "Passage gate": empty after discard.
        assertThat(OwnerContext.callAs(gm.id(), () -> reads.passage(passageId))).isEmpty();
        assertThat(get(gm, "/api/v1/materials/" + uploaded.materialId() + "/report").statusCode()).isEqualTo(404);
        assertThat(pageOf(gm, uploaded, 1, "review").statusCode()).isEqualTo(404);
        for (String action : List.of("accept", "reprocess", "retry", "discard")) {
            refused(act(gm, uploaded, action), "material.action_unavailable");
        }
        assertThat(logged.list).noneMatch(event -> event.getFormattedMessage().contains("Livro")
                || String.valueOf(event.getKeyValuePairs()).contains("Livro"));
    }

    @Test
    void discardingAFailedVersionRemovesItsRawUpload() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        assertThat(outcome(uploaded.jobId(), claim(uploaded.jobId()),
                "{\"outcome\":\"failed\",\"code\":\"ingestion.step_failed\",\"retry\":false}").statusCode())
                .isEqualTo(204);
        assertThat(files.fileExists(gm.id(), uploaded.uploadId())).isTrue();

        assertThat(act(gm, uploaded, "discard").statusCode()).isEqualTo(200);

        assertThat(uploads.rows).doesNotContainKey(uploaded.uploadId());
        assertThat(files.fileExists(gm.id(), uploaded.uploadId())).isFalse();
        assertThat(materials.versions.get(uploaded.versionId()).removalPending()).isFalse();
    }

    @Test
    void aDiscardInterruptedBeforeItsRemovalIsFinishedByTheSweep() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        cleanReport(uploaded.jobId(), claim(uploaded.jobId()), 2, "2", true);
        readUntilReview(gm, uploaded, List.of(), List.of(1, 2));
        List<VersionFileRow> stored = OwnerContext.callAs(gm.id(), () -> materials.versionFiles(uploaded.versionId()));
        // The discard's transaction committed, and the backend stopped before the removal ran.
        OwnerContext.runAs(gm.id(), () -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                materials.discardVersion(uploaded.versionId(), Instant.now())));
        assertThat(materials.versions.get(uploaded.versionId()).removalPending()).isTrue();
        assertThat(pageViews(uploaded.versionId())).hasSize(2);
        assertThat(state(gm, uploaded)).isEqualTo("discarded");

        // Matrix row "Discard interrupted": the sweep finishes it, once.
        assertThat(removals.sweep()).isEqualTo(1);

        assertNothingLeft(gm, uploaded, stored);
        assertThat(removals.sweep()).isZero();
    }

    @Test
    void anotherOwnerCannotActOnAMaterialNorReadItsReport() throws Exception {
        Gm a = signUp();
        Gm b = signUp();
        Uploaded uploaded = upload(a, pdf(MIB), "Segredo.pdf", null);
        clean(uploaded, 2, "2");
        // Matrix row "Report state": a version still processing has no report.
        assertThat(get(a, "/api/v1/materials/" + uploaded.materialId() + "/report").statusCode()).isEqualTo(404);
        readUntilReview(a, uploaded, List.of(2), List.of());
        JsonNode expected = json(get(b, "/api/v1/materials/" + UUID.randomUUID() + "/report"));

        // Matrix row "Two owners": B posts each action on A's material, and reads A's report: not found, as an
        // unknown or a malformed id; nothing changes.
        for (String material : List.of(uploaded.materialId(), UUID.randomUUID().toString(), "not-a-uuid")) {
            for (String path : List.of("/report", "/accept", "/reprocess", "/retry", "/discard")) {
                HttpResponse<String> answer = send(path.equals("/report") ? "GET" : "POST",
                        "/api/v1/materials/" + material + path, null, session(b));
                assertThat(answer.statusCode()).as(material + path).isEqualTo(404);
                assertThat(json(answer).path("code").asString()).isEqualTo(expected.path("code").asString())
                        .isEqualTo("request.not_found");
                assertThat(answer.body()).doesNotContain(uploaded.materialId()).doesNotContain("Segredo")
                        .doesNotContain(uploaded.versionId().toString());
            }
        }
        assertThat(state(a, uploaded)).isEqualTo("needs_review");
        assertThat(actions(a, uploaded)).containsExactly("accept", "reprocess", "discard");
        assertThat(steps(uploaded.versionId())).allSatisfy(step -> assertThat(step.endedAt()).isNotNull());
        OwnerContext.runAs(b.id(), () -> assertThat(reads.report(UUID.fromString(uploaded.materialId()))).isEmpty());
    }

    @Test
    void aFailedVersionAtItsStepCapCanOnlyBeDiscardedAndARetryIsRefusedChangingNothing() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        clean(uploaded, 1, "2");
        StepRow triage = offeredStep(uploaded.versionId());
        assertThat(outcome(triage.jobId(), claim(triage.jobId()),
                "{\"outcome\":\"failed\",\"code\":\"ingestion.step_failed\",\"retry\":false}").statusCode())
                .isEqualTo(204);
        assertThat(actions(gm, uploaded)).containsExactly("retry", "discard");
        // One page: at most 4 x 1 + 1 = 5 steps. Clean and triage are 2; three more ended steps reach the cap.
        endedSteps(gm, uploaded, 3, 1, 1, UUID.randomUUID(), Instant.now());
        assertThat(steps(uploaded.versionId())).hasSize(5);
        Map<UUID, JobRow> jobsBefore = Map.copyOf(jobs.rows);

        assertThat(actions(gm, uploaded)).containsExactly("discard");
        refused(act(gm, uploaded, "retry"), "ingestion.too_many_steps");

        assertThat(state(gm, uploaded)).isEqualTo("failed");
        assertThat(steps(uploaded.versionId())).hasSize(5);
        assertThat(jobs.rows).isEqualTo(jobsBefore);
    }

    @Test
    void reprocessCopiesTheNewerCoveringStepWhenANarrowerOnesInputIsGone() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        Cleaned cleaned = clean(uploaded, 20, "2");
        readUntilReview(gm, uploaded, List.of(9), List.of());
        // A step of an earlier chain over page 9 alone, whose input batch a restart removed: never copied.
        endedSteps(gm, uploaded, 1, 9, 9, UUID.randomUUID(), Instant.parse("2026-01-01T00:00:00Z"));

        HttpResponse<String> reprocessed = act(gm, uploaded, "reprocess");

        assertThat(reprocessed.statusCode()).as(reprocessed.body()).isEqualTo(200);
        assertThat(steps(uploaded.versionId())).filteredOn(step -> step.endedAt() == null).singleElement()
                .satisfies(step -> {
                    assertThat(step.stage()).isEqualTo("layout");
                    assertThat(step.firstPage()).isEqualTo(9);
                    assertThat(step.lastPage()).isEqualTo(9);
                    assertThat(step.inputFileId()).isEqualTo(cleaned.batches().getFirst());
                });
    }

    @Test
    void aDiscardWhoseRemovalFailsStillAnswersDiscardedAndTheSweepFinishesIt() throws Exception {
        Gm gm = signUp();
        Uploaded uploaded = upload(gm, pdf(MIB), "Livro.pdf", null);
        cleanReport(uploaded.jobId(), claim(uploaded.jobId()), 3, "2", true);
        readUntilReview(gm, uploaded, List.of(), List.of(1, 2));
        List<VersionFileRow> stored = OwnerContext.callAs(gm.id(), () -> materials.versionFiles(uploaded.versionId()));
        Path blocker = block(gm, stored.getFirst());

        HttpResponse<String> discarded = act(gm, uploaded, "discard");

        assertThat(discarded.statusCode()).as(discarded.body()).isEqualTo(200);
        assertThat(json(discarded).path("state").asString()).isEqualTo("discarded");
        assertThat(materials.versions.get(uploaded.versionId()).removalPending()).isTrue();
        assertThat(materials.files.values()).anyMatch(file -> file.versionId().equals(uploaded.versionId()));
        assertThat(logged.list).anyMatch(event -> event.getFormattedMessage().contains("material.removal_deferred"));

        unblock(blocker);
        removals.sweep();

        assertNothingLeft(gm, uploaded, stored);
    }

    @Test
    void aSweepOverTwoPendingVersionsRemovesOneWhenTheOtherFails() throws Exception {
        Gm gm = signUp();
        Uploaded failing = upload(gm, pdf(MIB), "Um.pdf", null);
        cleanReport(failing.jobId(), claim(failing.jobId()), 3, "2", true);
        readUntilReview(gm, failing, List.of(), List.of(1));
        Uploaded removed = upload(gm, pdf(MIB), "Dois.pdf", null);
        cleanReport(removed.jobId(), claim(removed.jobId()), 3, "2", true);
        readUntilReview(gm, removed, List.of(), List.of(1));
        List<VersionFileRow> failingFiles = OwnerContext.callAs(gm.id(), () -> materials.versionFiles(failing.versionId()));
        List<VersionFileRow> removedFiles = OwnerContext.callAs(gm.id(), () -> materials.versionFiles(removed.versionId()));
        // Both discards committed, and the backend stopped before either removal.
        for (Uploaded uploaded : List.of(failing, removed)) {
            OwnerContext.runAs(gm.id(), () -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                    materials.discardVersion(uploaded.versionId(), Instant.now())));
        }
        Path blocker = block(gm, failingFiles.getFirst());

        removals.sweep();

        assertNothingLeft(gm, removed, removedFiles);
        assertThat(materials.versions.get(failing.versionId()).removalPending()).isTrue();
        assertThat(materials.files.values()).anyMatch(file -> file.versionId().equals(failing.versionId()));
        assertThat(logged.list).anyMatch(event -> event.getFormattedMessage().contains("material.removal_failed"));

        unblock(blocker);
        removals.sweep();

        assertNothingLeft(gm, failing, failingFiles);
    }

    @Value("${gmshield.files.dir}")
    private Path filesDir;

    /** {@code count} ended steps of the version over pages {@code first} to {@code last} of {@code input}. */
    private void endedSteps(Gm gm, Uploaded uploaded, int count, int first, int last, UUID input, Instant at) {
        OwnerContext.runAs(gm.id(), () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                materials.insertStep(new StepRow(UUID.randomUUID(), gm.id(), uploaded.versionId(), UUID.randomUUID(),
                        "ocr", first, last, input, at, at));
            }
        }));
    }

    /** A folder with a file inside the stored file's folder: the file store cannot remove it until it is unblocked. */
    private Path block(Gm gm, VersionFileRow file) throws Exception {
        Path blocker = filesDir.resolve(gm.id().toString()).resolve(file.fileId().toString()).resolve("blocker");
        Files.createDirectories(blocker);
        Files.write(blocker.resolve("x"), new byte[] {1});
        return blocker;
    }

    private static void unblock(Path blocker) throws Exception {
        Files.delete(blocker.resolve("x"));
        Files.delete(blocker);
    }

    // --- Story 1.20 helpers

    @Autowired
    private io.github.gmshield.materials.service.VersionRemovalSweep removals;

    private HttpResponse<String> act(Gm gm, Uploaded uploaded, String action) throws Exception {
        return send("POST", "/api/v1/materials/" + uploaded.materialId() + "/" + action, null, session(gm));
    }

    private void accept(Gm gm, Uploaded uploaded) throws Exception {
        HttpResponse<String> accepted = act(gm, uploaded, "accept");
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
    }

    private List<String> actions(Gm gm, Uploaded uploaded) throws Exception {
        List<String> found = new ArrayList<>();
        json(get(gm, "/api/v1/materials/" + uploaded.materialId())).path("actions")
                .forEach(action -> found.add(action.asString()));
        return found;
    }

    private static void refused(HttpResponse<String> answer, String code) {
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(409);
        assertThat(json(answer).path("code").asString()).isEqualTo(code);
    }

    /** No page view, passage, outline, range, version file (row or pieces) or raw upload of the version is left. */
    private void assertNothingLeft(Gm gm, Uploaded uploaded, List<VersionFileRow> stored) {
        UUID version = uploaded.versionId();
        assertThat(materials.versions.get(version).state()).isEqualTo("discarded");
        assertThat(materials.versions.get(version).removalPending()).isFalse();
        assertThat(pageViews(version)).isEmpty();
        assertThat(passages(version)).isEmpty();
        assertThat(materials.outlines.values()).noneMatch(row -> row.versionId().equals(version));
        assertThat(layers(version)).isEmpty();
        assertThat(outcomes(version)).isEmpty();
        assertThat(materials.files.values()).noneMatch(file -> file.versionId().equals(version));
        assertThat(stored).isNotEmpty().allSatisfy(file -> assertThat(files.fileExists(gm.id(), file.fileId()))
                .as(file.role()).isFalse());
        assertThat(uploads.rows.values()).noneMatch(row -> row.versionId().equals(version));
        assertThat(files.fileExists(gm.id(), uploaded.uploadId())).isFalse();
    }

    /** A clean report of {@code total} pages in batches ending at {@code lasts}, a triage step per batch: the batches. */
    private List<UUID> cleanIn(Uploaded uploaded, int total, boolean passages, int... lasts) throws Exception {
        JsonNode claim = claim(uploaded.jobId());
        UUID original = output(uploaded.jobId(), claim, pdf(4000));
        List<UUID> batches = new ArrayList<>();
        List<String> batchJson = new ArrayList<>();
        List<String> next = new ArrayList<>();
        int first = 1;
        for (int last : lasts) {
            UUID batch = output(uploaded.jobId(), claim, pdf(3000));
            batches.add(batch);
            batchJson.add("{\"fileId\":\"" + batch + "\",\"firstPage\":" + first + ",\"lastPage\":" + last + "}");
            next.add("{\"stage\":\"triage\",\"firstPage\":" + first + ",\"lastPage\":" + last + ",\"input\":\""
                    + batch + "\"}");
            first = last + 1;
        }
        if (passages) {
            next.add(passagesStep(total));
        }
        report(steps(uploaded.versionId()).getFirst(), claim, "{\"pipelineVersion\":\"2\",\"pageTotal\":" + total
                + ",\"original\":{\"fileId\":\"" + original + "\"},\"batches\":[" + String.join(",", batchJson)
                + "],\"next\":[" + String.join(",", next) + "]}");
        return batches;
    }

    /**
     * Runs the version's offered steps until it needs review: triage finds every page a text page, layout reads every
     * page but {@code unreadable}, with a picture view of each page of {@code views}, and passages reports one passage
     * on page 1 and one section.
     */
    private void readUntilReview(Gm gm, Uploaded uploaded, List<Integer> unreadable, List<Integer> views)
            throws Exception {
        for (int round = 0; round < 20 && !state(gm, uploaded).equals("needs_review"); round++) {
            StepRow step = offeredStep(uploaded.versionId());
            JsonNode claim = claim(step.jobId());
            String result;
            if (step.stage().equals("triage")) {
                result = triage(step, step.firstPage(), step.lastPage(), List.of());
            } else if (step.stage().equals("layout")) {
                List<String> pictures = new ArrayList<>();
                for (int page : views) {
                    if (page >= step.firstPage() && page <= step.lastPage()) {
                        pictures.add(pictureView(step, claim, page, webp(1000), "Linha " + page));
                    }
                }
                result = withViews(layout(step, claim, step.firstPage(), step.lastPage(), unreadable.stream()
                        .filter(page -> page >= step.firstPage() && page <= step.lastPage()).toList()), pictures);
            } else {
                assertThat(step.stage()).isEqualTo("passages");
                result = passagesReport(passage(1, "Um", "A")).replace("\"outline\":[]", "\"outline\":["
                        + section(1, "A", 1) + "]");
            }
            report(step, claim, result);
        }
        assertThat(state(gm, uploaded)).isEqualTo("needs_review");
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

    // --- Story 1.16 helpers

    /** A clean step's report: the original and its batches of 20 pages, and one triage step per batch. */
    record Cleaned(UUID original, int originalSize, List<UUID> batches) {
    }

    private Cleaned clean(Uploaded uploaded, int total, String pipeline) throws Exception {
        return cleanReport(uploaded.jobId(), claim(uploaded.jobId()), total, pipeline);
    }

    private Cleaned cleanReport(UUID jobId, JsonNode claim, int total, String pipeline) throws Exception {
        return cleanReport(jobId, claim, total, pipeline, false);
    }

    /** {@link #cleanReport(UUID, JsonNode, int, String)}, with the held passages step last when {@code passages}. */
    private Cleaned cleanReport(UUID jobId, JsonNode claim, int total, String pipeline, boolean passages)
            throws Exception {
        byte[] cleaned = pdf(4000 + total);
        UUID original = output(jobId, claim, cleaned);
        List<UUID> batches = new ArrayList<>();
        StringBuilder batchJson = new StringBuilder();
        StringBuilder next = new StringBuilder();
        for (int first = 1; first <= total; first += 20) {
            int last = Math.min(total, first + 19);
            UUID batch = output(jobId, claim, pdf(3000));
            batches.add(batch);
            String comma = first == 1 ? "" : ",";
            batchJson.append(comma).append("{\"fileId\":\"").append(batch).append("\",\"firstPage\":").append(first)
                    .append(",\"lastPage\":").append(last).append('}');
            next.append(comma).append("{\"stage\":\"triage\",\"firstPage\":").append(first).append(",\"lastPage\":")
                    .append(last).append(",\"input\":\"").append(batch).append("\"}");
        }
        if (passages) {
            next.append(',').append(passagesStep(total));
        }
        HttpResponse<String> reported = outcome(jobId, claim, "{\"outcome\":\"done\",\"result\":{\"pipelineVersion\":\""
                + pipeline + "\",\"pageTotal\":" + total + ",\"original\":{\"fileId\":\"" + original + "\"},"
                + "\"batches\":[" + batchJson + "],\"next\":[" + next + "]}}");
        assertThat(reported.statusCode()).as(reported.body()).isEqualTo(204);
        return new Cleaned(original, cleaned.length, batches);
    }

    /**
     * A triage report: every page of the run a text page but {@code images}, a layout step per run of text pages, then
     * an OCR step per run of at most 10 image pages.
     */
    private static String triage(StepRow step, int first, int last, List<Integer> images) {
        StringBuilder layers = new StringBuilder();
        StringBuilder next = new StringBuilder();
        StringBuilder ocrSteps = new StringBuilder();
        int start = first;
        for (int page = first; page <= last + 1; page++) {
            boolean boundary = page > last || images.contains(page) != images.contains(start);
            if (!boundary) {
                continue;
            }
            boolean image = images.contains(start);
            layers.append(layers.isEmpty() ? "" : ",").append(range(start, page - 1, "layer", image ? "image" : "text"));
            if (!image) {
                next.append(next.isEmpty() ? "" : ",").append(nextStep(step, "layout", start, page - 1));
            }
            for (int ocr = start; image && ocr < page; ocr += 10) {
                ocrSteps.append(ocrSteps.isEmpty() ? "" : ",").append(nextStep(step, "ocr", ocr,
                        Math.min(ocr + 9, page - 1)));
            }
            start = page;
        }
        next.append(next.isEmpty() || ocrSteps.isEmpty() ? "" : ",").append(ocrSteps);
        return "{\"pipelineVersion\":\"2\",\"layers\":[" + layers + "],\"next\":[" + next + "]}";
    }

    private static String nextStep(StepRow step, String stage, int first, int last) {
        return "{\"stage\":\"" + stage + "\",\"firstPage\":" + first + ",\"lastPage\":" + last + ",\"input\":\""
                + step.inputFileId() + "\"}";
    }

    /** A layout report: every page read directly but {@code unreadable}, one text file per run of read pages. */
    private String layout(StepRow step, JsonNode claim, int first, int last, List<Integer> unreadable) throws Exception {
        return read(step, claim, first, last, unreadable, "read_directly");
    }

    /** An OCR report: every page read by OCR but {@code unreadable}, one text file per run of read pages. */
    private String ocr(StepRow step, JsonNode claim, int first, int last, List<Integer> unreadable) throws Exception {
        return read(step, claim, first, last, unreadable, "read_by_ocr");
    }

    private String read(StepRow step, JsonNode claim, int first, int last, List<Integer> unreadable, String outcome)
            throws Exception {
        StringBuilder pages = new StringBuilder();
        StringBuilder texts = new StringBuilder();
        int start = first;
        for (int page = first; page <= last + 1; page++) {
            boolean boundary = page > last || unreadable.contains(page) != unreadable.contains(start);
            if (!boundary) {
                continue;
            }
            boolean lost = unreadable.contains(start);
            pages.append(pages.isEmpty() ? "" : ",").append(range(start, page - 1, "outcome",
                    lost ? "unreadable" : outcome));
            if (!lost) {
                UUID text = output(step.jobId(), claim, "{\"pages\":[]}".getBytes(StandardCharsets.UTF_8));
                texts.append(texts.isEmpty() ? "" : ",").append("{\"fileId\":\"").append(text)
                        .append("\",\"firstPage\":").append(start).append(",\"lastPage\":").append(page - 1).append('}');
            }
            start = page;
        }
        return "{\"pipelineVersion\":\"2\",\"pages\":[" + pages + "],\"texts\":[" + texts + "],\"next\":[]}";
    }

    private static String range(int first, int last, String field, String value) {
        return "{\"firstPage\":" + first + ",\"lastPage\":" + last + ",\"" + field + "\":\"" + value + "\"}";
    }

    private void report(StepRow step, JsonNode claim, String result) throws Exception {
        HttpResponse<String> reported = outcome(step.jobId(), claim, "{\"outcome\":\"done\",\"result\":" + result + "}");
        assertThat(reported.statusCode()).as(reported.body()).isEqualTo(204);
    }

    /** The version's one step whose job is offered: one ingestion job at a time. */
    private StepRow offeredStep(UUID versionId) {
        return offeredStep(List.of(versionId));
    }

    /** The one step of these versions whose job is offered. */
    private StepRow offeredStep(List<UUID> versionIds) {
        return versionIds.stream().flatMap(versionId -> steps(versionId).stream())
                .filter(step -> step.jobId() != null && jobs.row(step.jobId()).state().equals("offered"))
                .reduce((first, second) -> {
                    throw new AssertionError("two offered steps");
                }).orElseThrow(() -> new AssertionError("no offered step"));
    }

    private VersionFileRow batchOf(Gm gm, Uploaded uploaded, UUID fileId) {
        return OwnerContext.callAs(gm.id(), () -> materials.versionFiles(uploaded.versionId())).stream()
                .filter(file -> file.fileId().equals(fileId)).findFirst().orElseThrow();
    }

    private List<PageRangeRow> layers(UUID versionId) {
        return materials.layers.values().stream().filter(row -> row.versionId().equals(versionId))
                .sorted(Comparator.comparing(PageRangeRow::firstPage)).toList();
    }

    private List<PageRangeRow> outcomes(UUID versionId) {
        return materials.outcomes.values().stream().filter(row -> row.versionId().equals(versionId))
                .sorted(Comparator.comparing(PageRangeRow::firstPage)).toList();
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
