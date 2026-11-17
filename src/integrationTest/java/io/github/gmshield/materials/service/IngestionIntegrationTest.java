package io.github.gmshield.materials.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.jobs.service.JobClaims;
import io.github.gmshield.jobs.service.JobFiles;
import io.github.gmshield.jobs.service.JobSweep;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.PageRangeRow;
import io.github.gmshield.materials.data.StepRow;
import io.github.gmshield.materials.data.VersionFileRow;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.FileStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stories 1.15 and 1.16 against a real PostgreSQL and Redis (test mode): V8, V9 and V10 and their checks, the chain
 * from a completed PDF through the claim, the input, the outputs and the report into {@code version_file}, the raw
 * upload's removal, a password's failure and the sweep of an output nobody adopted; then triage and layout reports
 * into {@code page_layer} and {@code page_outcome} (the JPQL overlap checks, {@code pages.done} and the end of the
 * chain), the restart transaction on a pipeline change, and the cross-owner tests of {@code ingestion_step},
 * {@code version_file}, {@code page_layer} and {@code page_outcome}.
 *
 * <p>The raw rows are read here with SQL to prove what is stored; the application never reads the library tables with
 * native SQL.
 */
@SpringBootTest(properties = {"GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
    "gmshield.materials.upload-sweep.enabled=false", "gmshield.files.dir=${java.io.tmpdir}/gmshield-ingestion-it"})
@ActiveProfiles("test-mode")
class IngestionIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MIB = 1024 * 1024;

    @Autowired
    private Uploads uploads;
    @Autowired
    private Materials materials;
    @Autowired
    private MaterialStore store;
    @Autowired
    private JobClaims claims;
    @Autowired
    private JobFiles jobFiles;
    @Autowired
    private JobSweep sweep;
    @Autowired
    private FileStore files;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private Jobs jobs;
    @Value("${gmshield.files.dir}")
    private Path filesDir;

    /** A completed PDF: its owner, version, raw upload and first job. */
    private record Chain(UUID owner, UUID materialId, UUID versionId, UUID uploadId, UUID jobId) {
    }

    @BeforeEach
    void freeTheIngestionLane() {
        // One ingestion job at a time: whatever another test left offered or running would hold this test's job back.
        jdbc.sql("update job set state = 'cancelled', grant_hash = null, claim_renew_at = null, claim_lapses_at = null"
                + " where lane = 'ingestion' and state in ('waiting', 'offered', 'running')").update();
    }

    @Test
    void aCleanStepIsRecordedIntoVersionFilesAndTheRawUploadIsRemoved() throws Exception {
        byte[] content = pdf(5 * MIB + 9);
        Chain chain = completed(content);
        Map<String, Object> job = jdbc.sql("select kind, lane, state, owner_id from job where id = ?").param(chain.jobId())
                .query().singleRow();
        assertThat(job).containsEntry("kind", "ingestion").containsEntry("lane", "ingestion")
                .containsEntry("state", "offered").containsEntry("owner_id", chain.owner());
        assertThat(jdbc.sql("select stage, first_page, input_file_id, owner_id from ingestion_step where job_id = ?")
                .param(chain.jobId()).query().singleRow())
                .containsEntry("stage", "clean").containsEntry("input_file_id", chain.uploadId())
                .containsEntry("owner_id", chain.owner()).containsEntry("first_page", null);
        assertThat(state(chain.versionId())).isEqualTo("waiting");

        JobClaims.Claim claim = claims.claim(chain.jobId());
        assertThat(state(chain.versionId())).isEqualTo("processing");
        assertThat(claim.input().path("stage").asString()).isEqualTo("clean");
        ByteArrayOutputStream read = new ByteArrayOutputStream();
        JobFiles.Opened opened = jobFiles.open(chain.jobId(), claim.claimId(), claim.grant(), "source");
        assertThat(opened.input().size()).isEqualTo(content.length);
        opened.input().writeTo(read);
        assertThat(read.toByteArray()).isEqualTo(content);

        byte[] cleaned = pdf(4 * MIB + 1);
        JobFiles.Stored original = store(chain, claim, cleaned);
        JobFiles.Stored batch1 = store(chain, claim, pdf(2000));
        JobFiles.Stored batch2 = store(chain, claim, pdf(1000));
        assertThat(original.sha256()).isEqualTo(java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(cleaned)));
        assertThat(jdbc.sql("select state, size_bytes, piece_count, owner_id from job_output where id = ?")
                .param(original.fileId()).query().singleRow())
                .containsEntry("state", "stored").containsEntry("size_bytes", (long) cleaned.length)
                .containsEntry("piece_count", 2).containsEntry("owner_id", chain.owner());

        claims.report(chain.jobId(), claim.claimId(), claim.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"1\",\"pageTotal\":23,\"original\":{\"fileId\":\"" + original.fileId() + "\"},"
                        + "\"batches\":[{\"fileId\":\"" + batch1.fileId() + "\",\"firstPage\":1,\"lastPage\":20},"
                        + "{\"fileId\":\"" + batch2.fileId() + "\",\"firstPage\":21,\"lastPage\":23}],\"next\":[]}"));

        Map<String, Object> version = jdbc.sql("select state, page_total, pipeline_version, failure_code"
                + " from material_version where id = ?").param(chain.versionId()).query().singleRow();
        assertThat(version).containsEntry("state", "processing").containsEntry("page_total", 23)
                .containsEntry("pipeline_version", "1").containsEntry("failure_code", null);
        List<Map<String, Object>> stored = jdbc.sql("select role, file_id, first_page, last_page, size_bytes, piece_count,"
                + " owner_id from version_file where version_id = ? order by role desc, first_page")
                .param(chain.versionId()).query().listOfRows();
        assertThat(stored).extracting(row -> row.get("role")).containsExactly("original", "batch", "batch");
        assertThat(stored.getFirst()).containsEntry("file_id", original.fileId()).containsEntry("first_page", 1)
                .containsEntry("last_page", 23).containsEntry("size_bytes", (long) cleaned.length)
                .containsEntry("piece_count", 2).containsEntry("owner_id", chain.owner());
        assertThat(jdbc.sql("select count(*) from job_output where job_id = ?").param(chain.jobId()).query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("select count(*) from upload where id = ?").param(chain.uploadId()).query(Long.class)
                .single()).isZero();
        assertThat(files.fileExists(chain.owner(), chain.uploadId())).isFalse();
        assertThat(readBack(chain.owner(), original.fileId(), 2)).isEqualTo(cleaned);
        // Stored only sealed: the cleaned bytes' marker is nowhere in the file store.
        assertThat(storedBytesContain(chain.owner(), original.fileId(), "%PDF-1.7")).isFalse();
        assertThat(as(chain.owner(), () -> materials.find(chain.materialId())).orElseThrow().pages())
                .isEqualTo(new Materials.Pages(0, 23));
        sweep.sweep();
        assertThat(files.fileExists(chain.owner(), batch1.fileId())).isTrue();
    }

    @Test
    void aPasswordFailsTheVersionRemovesTheRawUploadAndTheSweepRemovesAnOutputNobodyAdopted() throws Exception {
        Chain chain = completed(pdf(MIB));
        JobClaims.Claim claim = claims.claim(chain.jobId());
        JobFiles.Stored stray = store(chain, claim, pdf(3000));

        claims.report(chain.jobId(), claim.claimId(), claim.grant(), "failed", "ingestion.password_required", false, null);

        assertThat(jdbc.sql("select state, attempt, outcome_code from job where id = ?").param(chain.jobId()).query()
                .singleRow()).containsEntry("state", "failed").containsEntry("attempt", 1)
                .containsEntry("outcome_code", "ingestion.password_required");
        assertThat(jdbc.sql("select state, failure_code from material_version where id = ?").param(chain.versionId())
                .query().singleRow()).containsEntry("state", "failed")
                .containsEntry("failure_code", "ingestion.password_required");
        assertThat(jdbc.sql("select count(*) from upload where id = ?").param(chain.uploadId()).query(Long.class)
                .single()).isZero();
        assertThat(files.fileExists(chain.owner(), chain.uploadId())).isFalse();
        assertThat(jdbc.sql("select count(*) from material where id = ?").param(chain.materialId()).query(Long.class)
                .single()).isEqualTo(1);

        sweep.sweep();

        assertThat(jdbc.sql("select count(*) from job_output where id = ?").param(stray.fileId()).query(Long.class)
                .single()).isZero();
        assertThat(files.fileExists(chain.owner(), stray.fileId())).isFalse();
        assertThat(as(chain.owner(), () -> materials.find(chain.materialId())).orElseThrow().failure())
                .isEqualTo("ingestion.password_required");
    }

    @Test
    void theNewTablesCheckTheirValuesAndKeepEveryReferenceWithinTheOwner() throws Exception {
        Chain a = completed(pdf(MIB));
        Chain b = completed(pdf(MIB));
        UUID aFile = Ids.newId();
        jdbc.sql("insert into version_file (id, owner_id, version_id, file_id, role, first_page, last_page, size_bytes,"
                        + " piece_count, created_at) values (?, ?, ?, ?, 'original', 1, 3, 10, 1, now())")
                .params(Ids.newId(), a.owner(), a.versionId(), aFile).update();
        Map<String, String> violations = Map.of(
                "update version_file set role = 'copy' where file_id = '" + aFile + "'", "version_file_role_check",
                "update version_file set last_page = 0 where file_id = '" + aFile + "'", "version_file_pages_check",
                "update material_version set page_total = 0 where id = '" + a.versionId() + "'",
                "material_version_page_total_check",
                "update ingestion_step set last_page = 2 where job_id = '" + a.jobId() + "'", "ingestion_step_pages_check",
                "update ingestion_step set version_id = '" + b.versionId() + "' where job_id = '" + a.jobId() + "'",
                "ingestion_step_version_fkey",
                "update version_file set version_id = '" + b.versionId() + "' where file_id = '" + aFile + "'",
                "version_file_version_fkey",
                "insert into job_output (id, job_id, owner_id, claim_id, state, created_at, updated_at) values ('"
                        + Ids.newId() + "', '" + Ids.newId() + "', '" + a.owner() + "', '" + Ids.newId()
                        + "', 'stored', now(), now())", "job_output_job_id_fkey",
                "insert into job_output (id, job_id, owner_id, claim_id, state, created_at, updated_at) values ('"
                        + Ids.newId() + "', '" + a.jobId() + "', '" + a.owner() + "', '" + Ids.newId()
                        + "', 'lost', now(), now())", "job_output_state_check");
        violations.forEach((sql, constraint) -> assertThatThrownBy(() -> jdbc.sql(sql).update())
                .as(sql).satisfies(failure -> assertThat(messages(failure)).anyMatch(text -> text.contains(constraint))));
        // The same file id twice for one owner is refused.
        assertThatThrownBy(() -> jdbc.sql("insert into version_file (id, owner_id, version_id, file_id, role, first_page,"
                        + " last_page, size_bytes, piece_count, created_at) values (?, ?, ?, ?, 'batch', 1, 1, 10, 1, now())")
                .params(Ids.newId(), a.owner(), a.versionId(), aFile).update())
                .satisfies(failure -> assertThat(messages(failure)).anyMatch(text -> text.contains("version_file_owner_file_key")));
    }

    @Test
    void anotherOwnersStepsAndVersionFilesAreNeverSeenOrWritten() throws Exception {
        Chain a = completed(pdf(MIB));
        Chain b = completed(pdf(MIB));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        write(a.owner(), () -> store.insertVersionFile(new VersionFileRow(Ids.newId(), a.owner(), a.versionId(), Ids.newId(),
                VersionFileRow.ORIGINAL, 1, 2, 10, 1, now)));

        as(b.owner(), () -> {
            assertThat(store.findStepByJob(a.jobId())).isEmpty();
            assertThat(store.countSteps(a.versionId())).isZero();
            assertThat(store.versionFiles(a.versionId())).isEmpty();
            assertThat(store.findStepByJob(b.jobId())).isPresent();
            return null;
        });
        as(a.owner(), () -> {
            assertThat(store.findStepByJob(a.jobId())).map(StepRow::stage).contains("clean");
            assertThat(store.countSteps(a.versionId())).isEqualTo(1);
            assertThat(store.versionFiles(a.versionId())).hasSize(1);
            return null;
        });
        // B cannot write a step or a file naming A's version: the owner-scoped keys refuse it.
        assertThatThrownBy(() -> write(b.owner(), () -> store.insertStep(new StepRow(Ids.newId(), b.owner(), a.versionId(),
                Ids.newId(), "clean", null, null, a.uploadId(), now)))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> write(b.owner(), () -> store.insertVersionFile(new VersionFileRow(Ids.newId(), b.owner(),
                a.versionId(), Ids.newId(), VersionFileRow.BATCH, 1, 1, 10, 1, now)))).isInstanceOf(RuntimeException.class);
        // Nor as A for B's owner id: Hibernate fills the owner from the work's owner and refuses another.
        assertThatThrownBy(() -> write(a.owner(), () -> store.insertStep(new StepRow(Ids.newId(), b.owner(), b.versionId(),
                Ids.newId(), "clean", null, null, b.uploadId(), now)))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.sql("select count(*) from ingestion_step where version_id = ?").param(a.versionId())
                .query(Long.class).single()).isEqualTo(1);
    }

    // --- Story 1.16

    @Test
    void triageAndLayoutReportsRecordPageRangesCountPagesDoneAndEndTheChainInNeedsReview() throws Exception {
        Chain chain = completed(pdf(MIB));
        UUID batch = cleanReport(chain, 25, "2").get(1);
        List<UUID> triage = unendedJobs(chain.versionId());
        assertThat(triage).hasSize(2);

        // The oldest step first: triage of pages 1 to 20, all text.
        JobClaims.Claim whole = claimOffered(chain);
        assertThat(whole.input().path("firstPage").asInt()).isEqualTo(1);
        assertThat(whole.input().path("attempt").asInt()).isEqualTo(1);
        UUID firstBatch = UUID.fromString(whole.input().path("inputFileId").asString());
        claims.report(whole.jobId(), whole.claimId(), whole.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"2\",\"layers\":[" + range(1, 20, "layer", "text") + "],\"next\":["
                        + layoutStep(1, 20, firstBatch) + "]}"));
        // Then pages 21 to 25 of the second batch: 23 is an image page.
        JobClaims.Claim second = claimOffered(chain);
        assertThat(second.input().path("inputFileId").asString()).isEqualTo(batch.toString());
        assertThat(second.input().path("inputFirstPage").asInt()).isEqualTo(21);
        assertThat(second.input().path("inputLastPage").asInt()).isEqualTo(25);
        claims.report(second.jobId(), second.claimId(), second.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"2\",\"layers\":[" + range(21, 22, "layer", "text") + ","
                        + range(23, 23, "layer", "image") + "," + range(24, 25, "layer", "text") + "],\"next\":["
                        + layoutStep(21, 22, batch) + "," + layoutStep(24, 25, batch) + "]}"));
        assertThat(jdbc.sql("select layer, first_page, last_page, owner_id from page_layer where version_id = ?"
                + " order by first_page").param(chain.versionId()).query().listOfRows())
                .extracting(row -> row.get("layer") + " " + row.get("first_page") + "-" + row.get("last_page"))
                .containsExactly("text 1-20", "text 21-22", "image 23-23", "text 24-25");

        // The three layout steps. The first one's report gives its last page two outcomes: it is refused.
        for (int i = 0; i < 3; i++) {
            JobClaims.Claim layout = claimOffered(chain);
            int first = layout.input().path("firstPage").asInt();
            int last = layout.input().path("lastPage").asInt();
            JobFiles.Stored text = jobFiles.store(layout.jobId(), layout.claimId(), layout.grant(), -1,
                    new ByteArrayInputStream("{\"pages\":[]}".getBytes(StandardCharsets.UTF_8)));
            if (i == 0) {
                String twice = "{\"pipelineVersion\":\"2\",\"pages\":[" + range(first, last, "outcome", "read_directly")
                        + "," + range(last, last, "outcome", "unreadable") + "]}";
                assertThatThrownBy(() -> claims.report(layout.jobId(), layout.claimId(), layout.grant(), "done", null,
                        null, JSON.readTree(twice))).isInstanceOf(ApiException.class);
                assertThat(jdbc.sql("select count(*) from page_outcome where version_id = ?").param(chain.versionId())
                        .query(Long.class).single()).isZero();
            }
            claims.report(layout.jobId(), layout.claimId(), layout.grant(), "done", null, null, JSON.readTree(
                    "{\"pipelineVersion\":\"2\",\"pages\":[" + range(first, last, "outcome", "read_directly") + "],"
                            + "\"texts\":[{\"fileId\":\"" + text.fileId() + "\",\"firstPage\":" + first
                            + ",\"lastPage\":" + last + "}]}"));
        }

        assertThat(as(chain.owner(), () -> materials.find(chain.materialId())).orElseThrow().pages())
                .isEqualTo(new Materials.Pages(24, 25));
        // Every step ended, but page 23 waits for OCR: the version stays processing.
        assertThat(state(chain.versionId())).isEqualTo("processing");
        assertThat(jdbc.sql("select count(*) from ingestion_step where version_id = ? and ended_at is null")
                .param(chain.versionId()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from version_file where version_id = ? and role = 'text'")
                .param(chain.versionId()).query(Long.class).single()).isEqualTo(3);

        // Once page 23 has an outcome, a report that leaves no step unended ends the chain.
        UUID last = as(chain.owner(), () -> new TransactionTemplate(transactions).execute(status ->
                jobsCreate(chain, "ocr", 23)));
        JobClaims.Claim ocr = claims.claim(last);
        claims.report(last, ocr.claimId(), ocr.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"2\",\"pages\":[" + range(23, 23, "outcome", "unreadable") + "]}"));
        assertThat(state(chain.versionId())).isEqualTo("needs_review");
        assertThat(as(chain.owner(), () -> materials.find(chain.materialId())).orElseThrow().pages())
                .isEqualTo(new Materials.Pages(25, 25));
    }

    @Test
    void aPipelineChangeRestartsTheChainInOneTransaction() throws Exception {
        Chain chain = completed(pdf(MIB));
        List<UUID> batches = cleanReport(chain, 25, "1");
        UUID original = jdbc.sql("select file_id from version_file where version_id = ? and role = 'original'")
                .param(chain.versionId()).query(UUID.class).single();
        JobClaims.Claim triage = claimOffered(chain);
        UUID input = UUID.fromString(triage.input().path("inputFileId").asString());
        int first = triage.input().path("firstPage").asInt();
        int lastPage = triage.input().path("lastPage").asInt();
        claims.report(triage.jobId(), triage.claimId(), triage.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"1\",\"layers\":[" + range(first, lastPage, "layer", "text") + "],\"next\":["
                        + layoutStep(first, lastPage, input) + "]}"));
        List<UUID> waiting = unendedJobs(chain.versionId());
        assertThat(waiting).hasSize(2);

        JobClaims.Claim stale = claimOffered(chain);
        claims.report(stale.jobId(), stale.claimId(), stale.grant(), "failed", "ingestion.pipeline_changed", false, null);

        assertThat(jdbc.sql("select state, page_total, pipeline_version, failure_code from material_version where id = ?")
                .param(chain.versionId()).query().singleRow()).containsEntry("state", "processing")
                .containsEntry("page_total", 25).containsEntry("pipeline_version", null).containsEntry("failure_code", null);
        assertThat(jdbc.sql("select count(*) from page_layer where version_id = ?").param(chain.versionId())
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select role, file_id from version_file where version_id = ?").param(chain.versionId())
                .query().listOfRows()).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("role", "original").containsEntry("file_id", original));
        for (UUID batch : batches) {
            assertThat(files.fileExists(chain.owner(), batch)).isFalse();
        }
        for (UUID job : waiting) {
            String expected = job.equals(stale.jobId()) ? "failed" : "cancelled";
            assertThat(jdbc.sql("select state from job where id = ?").param(job).query(String.class).single())
                    .isEqualTo(expected);
        }
        Map<String, Object> restart = jdbc.sql("select stage, input_file_id, job_id from ingestion_step"
                + " where version_id = ? and ended_at is null").param(chain.versionId()).query().singleRow();
        assertThat(restart).containsEntry("stage", "clean").containsEntry("input_file_id", original);
        JobClaims.Claim clean = claims.claim((UUID) restart.get("job_id"));
        assertThat(clean.input().has("pipelineVersion")).isFalse();
        assertThat(clean.input().path("pageTotal").asInt()).isEqualTo(25);
    }

    @Test
    void theNewRangeTablesCheckTheirValuesAndKeepTheirVersionWithinTheOwner() throws Exception {
        Chain a = completed(pdf(MIB));
        Chain b = completed(pdf(MIB));
        jdbc.sql("update material_version set page_total = 5 where id in (?, ?)").params(a.versionId(), b.versionId())
                .update();
        UUID layer = Ids.newId();
        jdbc.sql("insert into page_layer (id, owner_id, version_id, first_page, last_page, layer, created_at)"
                + " values (?, ?, ?, 1, 2, 'text', now())").params(layer, a.owner(), a.versionId()).update();
        UUID outcome = Ids.newId();
        jdbc.sql("insert into page_outcome (id, owner_id, version_id, first_page, last_page, outcome, created_at)"
                + " values (?, ?, ?, 1, 2, 'read_directly', now())").params(outcome, a.owner(), a.versionId()).update();
        jdbc.sql("insert into version_file (id, owner_id, version_id, file_id, role, first_page, last_page, size_bytes,"
                + " piece_count, created_at) values (?, ?, ?, ?, 'text', 1, 2, 10, 1, now())")
                .params(Ids.newId(), a.owner(), a.versionId(), Ids.newId()).update();
        jdbc.sql("update ingestion_step set ended_at = now() where job_id = ?").param(a.jobId()).update();
        Map<String, String> violations = Map.of(
                "update page_layer set layer = 'scan' where id = '" + layer + "'", "page_layer_layer_check",
                "update page_layer set last_page = 0 where id = '" + layer + "'", "page_layer_pages_check",
                "update page_layer set first_page = 0 where id = '" + layer + "'", "page_layer_first_page_check",
                "update page_layer set version_id = '" + b.versionId() + "' where id = '" + layer + "'",
                "page_layer_version_fkey",
                "update page_outcome set outcome = 'read' where id = '" + outcome + "'", "page_outcome_outcome_check",
                "update page_outcome set last_page = 0 where id = '" + outcome + "'", "page_outcome_pages_check",
                "update page_outcome set version_id = '" + b.versionId() + "' where id = '" + outcome + "'",
                "page_outcome_version_fkey");
        violations.forEach((sql, constraint) -> assertThatThrownBy(() -> jdbc.sql(sql).update())
                .as(sql).satisfies(failure -> assertThat(messages(failure)).anyMatch(text -> text.contains(constraint))));
    }

    @Test
    void anotherOwnersPageLayersAndOutcomesAreNeverSeenOrWritten() throws Exception {
        Chain a = completed(pdf(MIB));
        Chain b = completed(pdf(MIB));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        write(a.owner(), () -> {
            store.insertPageLayer(new PageRangeRow(Ids.newId(), a.owner(), a.versionId(), 1, 3, "text", now));
            store.insertPageOutcome(new PageRangeRow(Ids.newId(), a.owner(), a.versionId(), 1, 3, "read_directly", now));
        });

        as(b.owner(), () -> {
            assertThat(store.pageLayers(a.versionId())).isEmpty();
            assertThat(store.layerOverlaps(a.versionId(), 1, 3)).isFalse();
            assertThat(store.outcomeOverlaps(a.versionId(), 1, 3)).isFalse();
            assertThat(store.outcomesWithin(a.versionId(), 1, 3)).isEmpty();
            assertThat(store.pagesDone(List.of(a.versionId()))).isEmpty();
            return null;
        });
        write(b.owner(), () -> assertThat(store.deletePageRanges(a.versionId())).isZero());
        as(a.owner(), () -> {
            assertThat(store.pageLayers(a.versionId())).hasSize(1);
            assertThat(store.layerOverlaps(a.versionId(), 3, 9)).isTrue();
            assertThat(store.outcomeOverlaps(a.versionId(), 4, 9)).isFalse();
            assertThat(store.pagesDone(List.of(a.versionId(), b.versionId()))).containsExactly(Map.entry(a.versionId(), 3L));
            return null;
        });
        // B cannot write a range naming A's version, nor A a range under B's owner id.
        assertThatThrownBy(() -> write(b.owner(), () -> store.insertPageLayer(new PageRangeRow(Ids.newId(), b.owner(),
                a.versionId(), 4, 4, "image", now)))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> write(b.owner(), () -> store.insertPageOutcome(new PageRangeRow(Ids.newId(), b.owner(),
                a.versionId(), 4, 4, "unreadable", now)))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> write(a.owner(), () -> store.insertPageOutcome(new PageRangeRow(Ids.newId(), b.owner(),
                b.versionId(), 1, 1, "unreadable", now)))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.sql("select count(*) from page_outcome where version_id in (?, ?)")
                .params(a.versionId(), b.versionId()).query(Long.class).single()).isEqualTo(1);
    }

    /** Claims the clean step, sends its original and its batches of 20 pages, and reports a triage step per batch. */
    private List<UUID> cleanReport(Chain chain, int total, String pipeline) throws Exception {
        JobClaims.Claim claim = claims.claim(chain.jobId());
        JobFiles.Stored original = store(chain, claim, pdf(4000));
        List<UUID> batches = new ArrayList<>();
        StringBuilder batchJson = new StringBuilder();
        StringBuilder next = new StringBuilder();
        for (int first = 1; first <= total; first += 20) {
            int last = Math.min(total, first + 19);
            UUID batch = store(chain, claim, pdf(3000)).fileId();
            batches.add(batch);
            String comma = first == 1 ? "" : ",";
            batchJson.append(comma).append("{\"fileId\":\"").append(batch).append("\",\"firstPage\":").append(first)
                    .append(",\"lastPage\":").append(last).append('}');
            next.append(comma).append("{\"stage\":\"triage\",\"firstPage\":").append(first).append(",\"lastPage\":")
                    .append(last).append(",\"input\":\"").append(batch).append("\"}");
        }
        claims.report(chain.jobId(), claim.claimId(), claim.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"" + pipeline + "\",\"pageTotal\":" + total + ",\"original\":{\"fileId\":\""
                        + original.fileId() + "\"},\"batches\":[" + batchJson + "],\"next\":[" + next + "]}"));
        return batches;
    }

    /** The version's step job that is offered (one ingestion job at a time), claimed. */
    private JobClaims.Claim claimOffered(Chain chain) {
        UUID job = jdbc.sql("select s.job_id from ingestion_step s join job j on j.id = s.job_id"
                + " where s.version_id = ? and j.state = 'offered'").param(chain.versionId()).query(UUID.class).single();
        return claims.claim(job);
    }

    private List<UUID> unendedJobs(UUID versionId) {
        return jdbc.sql("select job_id from ingestion_step where version_id = ? and ended_at is null order by created_at")
                .param(versionId).query(UUID.class).list();
    }

    /** A step of the version over one page, recorded as the worker's next steps are (the backend never names one). */
    private UUID jobsCreate(Chain chain, String stage, int page) {
        UUID batch = jdbc.sql("select file_id from version_file where version_id = ? and role = 'batch'"
                + " and first_page <= ? and last_page >= ?").params(chain.versionId(), page, page).query(UUID.class).single();
        return jobs.create("ingestion", created -> store.insertStep(new StepRow(Ids.newId(), chain.owner(),
                chain.versionId(), created.id(), stage, page, page, batch,
                Instant.now().truncatedTo(ChronoUnit.MICROS)))).id();
    }

    private static String range(int first, int last, String field, String value) {
        return "{\"firstPage\":" + first + ",\"lastPage\":" + last + ",\"" + field + "\":\"" + value + "\"}";
    }

    private static String layoutStep(int first, int last, UUID input) {
        return "{\"stage\":\"layout\",\"firstPage\":" + first + ",\"lastPage\":" + last + ",\"input\":\"" + input + "\"}";
    }

    // --- helpers

    private Chain completed(byte[] content) throws Exception {
        UUID owner = Ids.newId();
        UUID id = as(owner, () -> uploads.create(content.length, "Livro.pdf")).uploadId();
        for (int offset = 0; offset < content.length; offset += 4 * MIB) {
            byte[] part = java.util.Arrays.copyOfRange(content, offset, Math.min(content.length, offset + 4 * MIB));
            long start = offset;
            as(owner, () -> uploads.patch(id, start, part.length, sha256(part), new ByteArrayInputStream(part)));
        }
        Uploads.Completed completed = as(owner, () -> uploads.complete(id, sha256(content)));
        UUID jobId = jdbc.sql("select job_id from ingestion_step where version_id = ?").param(completed.versionId())
                .query(UUID.class).single();
        return new Chain(owner, completed.materialId(), completed.versionId(), id, jobId);
    }

    private JobFiles.Stored store(Chain chain, JobClaims.Claim claim, byte[] content) {
        return jobFiles.store(chain.jobId(), claim.claimId(), claim.grant(), -1, new ByteArrayInputStream(content));
    }

    private String state(UUID versionId) {
        return jdbc.sql("select state from material_version where id = ?").param(versionId).query(String.class).single();
    }

    private byte[] readBack(UUID owner, UUID fileId, int pieces) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < pieces; i++) {
            out.writeBytes(files.readPiece(owner, fileId, i));
        }
        return out.toByteArray();
    }

    private boolean storedBytesContain(UUID owner, UUID fileId, String marker) throws Exception {
        byte[] needle = marker.getBytes(StandardCharsets.US_ASCII);
        try (Stream<Path> pieces = Files.list(filesDir.resolve(owner.toString()).resolve(fileId.toString()))) {
            for (Path piece : pieces.toList()) {
                byte[] sealed = Files.readAllBytes(piece);
                for (int i = 0; i + needle.length <= sealed.length; i++) {
                    if (java.util.Arrays.equals(sealed, i, i + needle.length, needle, 0, needle.length)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void write(UUID owner, Runnable work) throws Exception {
        as(owner, () -> new TransactionTemplate(transactions).execute(status -> {
            work.run();
            return null;
        }));
    }

    private static <T> T as(UUID owner, Callable<T> work) throws Exception {
        return OwnerContext.callAs(owner, work::call);
    }

    private static List<String> messages(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.add(String.valueOf(cause.getMessage()));
        }
        return messages;
    }

    private static byte[] sha256(byte[] content) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(content);
    }

    private static byte[] pdf(int size) {
        byte[] content = new byte[size];
        byte[] head = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(head, 0, content, 0, head.length);
        for (int i = head.length; i < size; i++) {
            content[i] = (byte) (i * 7 + (i >>> 9));
        }
        return content;
    }
}
