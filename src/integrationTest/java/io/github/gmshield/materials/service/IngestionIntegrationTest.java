package io.github.gmshield.materials.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.jobs.service.JobClaims;
import io.github.gmshield.jobs.service.JobFiles;
import io.github.gmshield.jobs.service.JobSweep;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.OutlineRow;
import io.github.gmshield.materials.data.PageRangeRow;
import io.github.gmshield.materials.data.PageViewRow;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.PendingRemovals;
import io.github.gmshield.materials.data.StepRow;
import io.github.gmshield.materials.data.VersionFileRow;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.FileStore;
import io.github.gmshield.vault.service.Vault;
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
 * {@code version_file}, {@code page_layer} and {@code page_outcome}. Story 1.18: V11, the held passages step and its
 * job, its text inputs, passages and the outline stored encrypted and replaced by a second report, a failure ending
 * the held step, and the cross-owner tests of {@code passage} and {@code version_outline}. Story 1.19: V12, page views
 * stored encrypted with their pictures and served on the review ground, a restart removing them before their pictures,
 * and the cross-owner tests of {@code page_view}. Story 1.20: V13, reprocess and accept through their JPQL, the
 * passage gate, a discard that leaves no row or file of its version, and the sweep finishing an interrupted one.
 *
 * <p>The raw rows are read here with SQL to prove what is stored; the application never reads the library tables with
 * native SQL, but for the removal sweep's list of the ids of versions whose removal is pending.
 */
@SpringBootTest(properties = {"GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
    "gmshield.materials.upload-sweep.enabled=false",
    "gmshield.materials.version-removal-sweep.enabled=false",
    "gmshield.files.dir=${java.io.tmpdir}/gmshield-ingestion-it"})
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
    @Autowired
    private Vault vault;
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
                .isEqualTo(new Materials.Pages(24, 25, new Materials.Count(24, 24), new Materials.Count(0, 1), 24, 0,
                        0));
        // Every step ended, but page 23 waits for OCR: the version stays processing.
        assertThat(state(chain.versionId())).isEqualTo("processing");
        assertThat(jdbc.sql("select count(*) from ingestion_step where version_id = ? and ended_at is null")
                .param(chain.versionId()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from version_file where version_id = ? and role = 'text'")
                .param(chain.versionId()).query(Long.class).single()).isEqualTo(3);

        // Once page 23 is read by OCR (Story 1.17), a report that leaves no step unended ends the chain.
        UUID last = as(chain.owner(), () -> new TransactionTemplate(transactions).execute(status ->
                jobsCreate(chain, "ocr", 23)));
        // Offered, then running, the version is reading its image page: the job's state and the step's pages, on
        // Postgres.
        assertThat(jdbc.sql("select state from job where id = ?").param(last).query(String.class).single())
                .isEqualTo("offered");
        assertThat(as(chain.owner(), () -> materials.find(chain.materialId())).orElseThrow().pages().reading())
                .isEqualTo("image");
        JobClaims.Claim ocr = claims.claim(last);
        assertThat(as(chain.owner(), () -> materials.find(chain.materialId())).orElseThrow().pages().reading())
                .isEqualTo("image");
        JobFiles.Stored scanned = jobFiles.store(last, ocr.claimId(), ocr.grant(), -1,
                new ByteArrayInputStream("{\"pages\":[]}".getBytes(StandardCharsets.UTF_8)));
        claims.report(last, ocr.claimId(), ocr.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"2\",\"pages\":[" + range(23, 23, "outcome", "read_by_ocr") + "],"
                        + "\"texts\":[{\"fileId\":\"" + scanned.fileId() + "\",\"firstPage\":23,\"lastPage\":23}]}"));
        assertThat(state(chain.versionId())).isEqualTo("needs_review");
        assertThat(jdbc.sql("select outcome from page_outcome where version_id = ? and first_page = 23")
                .param(chain.versionId()).query(String.class).single()).isEqualTo("read_by_ocr");
        // The counts the GM reads, derived from the ranges Postgres holds.
        assertThat(as(chain.owner(), () -> materials.find(chain.materialId())).orElseThrow().pages())
                .isEqualTo(new Materials.Pages(25, 25, new Materials.Count(24, 24), new Materials.Count(1, 1), 24, 1,
                        0));
        assertThat(as(chain.owner(), () -> materials.list(List.of("needs_review"), null, 50)).items())
                .filteredOn(item -> item.id().equals(chain.materialId())).singleElement()
                .satisfies(item -> assertThat(item.pages().readByOcr()).isEqualTo(1));
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
            assertThat(store.pageLayers(List.of(a.versionId(), b.versionId()))).isEmpty();
            assertThat(store.pageOutcomes(List.of(a.versionId(), b.versionId()))).isEmpty();
            return null;
        });
        write(b.owner(), () -> assertThat(store.deletePageRanges(a.versionId())).isZero());
        as(a.owner(), () -> {
            assertThat(store.pageLayers(a.versionId())).hasSize(1);
            assertThat(store.layerOverlaps(a.versionId(), 3, 9)).isTrue();
            assertThat(store.outcomeOverlaps(a.versionId(), 4, 9)).isFalse();
            assertThat(store.pagesDone(List.of(a.versionId(), b.versionId()))).containsExactly(Map.entry(a.versionId(), 3L));
            assertThat(store.pageLayers(List.of(a.versionId(), b.versionId()))).singleElement()
                    .satisfies(row -> assertThat(row.value()).isEqualTo("text"));
            assertThat(store.pageOutcomes(List.of(a.versionId(), b.versionId()))).singleElement()
                    .satisfies(row -> assertThat(row.value()).isEqualTo("read_directly"));
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

    // --- Story 1.18

    @Test
    void theHeldPassagesStepsGetTheirJobsOnceEveryPageIsReadAndTheirPassagesAreStoredEncryptedAndReplaced()
            throws Exception {
        Chain chain = completed(pdf(MIB));
        JobClaims.Claim clean = claims.claim(chain.jobId());
        JobFiles.Stored original = store(chain, clean, pdf(4000));
        JobFiles.Stored batch = store(chain, clean, pdf(3000));
        String held = "{\"stage\":\"passages\",\"firstPage\":1,\"lastPage\":3,\"input\":\"texts\"}";
        claims.report(chain.jobId(), clean.claimId(), clean.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"4\",\"pageTotal\":3,\"original\":{\"fileId\":\"" + original.fileId() + "\"},"
                        + "\"batches\":[{\"fileId\":\"" + batch.fileId() + "\",\"firstPage\":1,\"lastPage\":3}],"
                        + "\"next\":[" + layoutStep(1, 3, batch.fileId()) + "," + held + "," + held + "]}"));
        List<Map<String, Object>> heldRows = jdbc.sql("select job_id, input_file_id, input_texts, first_page, last_page"
                + " from ingestion_step where version_id = ? and stage = 'passages'").param(chain.versionId()).query()
                .listOfRows();
        assertThat(heldRows).hasSize(2).allSatisfy(row -> assertThat(row).containsEntry("job_id", null)
                .containsEntry("input_file_id", null).containsEntry("input_texts", true).containsEntry("first_page", 1)
                .containsEntry("last_page", 3));

        JobClaims.Claim layout = claimOffered(chain);
        JobFiles.Stored text = jobFiles.store(layout.jobId(), layout.claimId(), layout.grant(), -1,
                new ByteArrayInputStream("{\"pages\":[]}".getBytes(StandardCharsets.UTF_8)));
        claims.report(layout.jobId(), layout.claimId(), layout.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"4\",\"pages\":[" + range(1, 3, "outcome", "read_directly") + "],"
                        + "\"texts\":[{\"fileId\":\"" + text.fileId() + "\",\"firstPage\":1,\"lastPage\":3}]}"));

        // The report that read the last page gave both held steps their jobs; the version still processes.
        assertThat(jdbc.sql("select count(*) from ingestion_step s join job j on j.id = s.job_id where s.version_id = ?"
                + " and s.stage = 'passages' and j.state in ('offered', 'waiting')").param(chain.versionId())
                .query(Long.class).single()).isEqualTo(2);
        assertThat(state(chain.versionId())).isEqualTo("processing");
        assertThat(as(chain.owner(), () -> materials.find(chain.materialId())).orElseThrow().pages().reading())
                .isNull();
        JobClaims.Claim first = claimOffered(chain);
        assertThat(first.input().path("input").asString()).isEqualTo("texts");
        assertThat(first.input().path("texts").get(0).path("name").asString()).isEqualTo("text-" + text.fileId());
        ByteArrayOutputStream read = new ByteArrayOutputStream();
        jobFiles.open(first.jobId(), first.claimId(), first.grant(), "text-" + text.fileId()).input().writeTo(read);
        assertThat(read.toString(StandardCharsets.UTF_8)).isEqualTo("{\"pages\":[]}");
        assertThatThrownBy(() -> jobFiles.open(first.jobId(), first.claimId(), first.grant(), "source"))
                .isInstanceOf(ApiException.class);
        claims.report(first.jobId(), first.claimId(), first.grant(), "done", null, null, JSON.readTree(
                passagesReport("Wyvern sting", "Bestiary", "Wyvern")));
        assertThat(jdbc.sql("select count(*) from passage where version_id = ?").param(chain.versionId())
                .query(Long.class).single()).isEqualTo(2);

        // The second report replaces the first set, in its transaction: one set, with the same ordinals.
        JobClaims.Claim second = claimOffered(chain);
        claims.report(second.jobId(), second.claimId(), second.grant(), "done", null, null, JSON.readTree(
                passagesReport("Vorpal blade", "Magic Items", "Vorpal Sword")));

        assertThat(state(chain.versionId())).isEqualTo("needs_review");
        List<Map<String, Object>> rows = jdbc.sql("select id, owner_id, ordinal, page, text, place from passage"
                + " where version_id = ? order by ordinal").param(chain.versionId()).query().listOfRows();
        assertThat(rows).extracting(row -> row.get("ordinal") + "@" + row.get("page")).containsExactly("0@1", "1@3");
        // Nothing of the text, the path or the outline is stored in the clear.
        for (Map<String, Object> row : rows) {
            assertThat(row).containsEntry("owner_id", chain.owner());
            for (String column : List.of("text", "place")) {
                String stored = new String((byte[]) row.get(column), StandardCharsets.ISO_8859_1);
                assertThat(stored).doesNotContain("Vorpal").doesNotContain("Magic").doesNotContain("Wyvern");
            }
        }
        byte[] outline = jdbc.sql("select outline from version_outline where version_id = ?").param(chain.versionId())
                .query(byte[].class).single();
        assertThat(new String(outline, StandardCharsets.ISO_8859_1)).doesNotContain("Magic").doesNotContain("Vorpal");
        UUID id = (UUID) rows.get(1).get("id");
        // Story 1.20: a passage is read only once its version is accepted.
        assertThat(as(chain.owner(), () -> materials.passage(id))).isEmpty();
        assertThat(state(chain.versionId())).isEqualTo("needs_review");
        as(chain.owner(), () -> actions.act(chain.materialId(), "accept"));
        Materials.Passage passage = as(chain.owner(), () -> materials.passage(id)).orElseThrow();
        assertThat(passage.sectionPath()).containsExactly("Magic Items", "Vorpal Sword");
        assertThat(passage.text()).isEqualTo("Vorpal blade 3");
        assertThat(passage.page()).isEqualTo(3);
        assertThat(passage.materialId()).isEqualTo(chain.materialId());
        assertThat(passage.materialName()).isEqualTo("Livro.pdf");
        assertThat(as(chain.owner(), () -> materials.outline(chain.versionId()))).hasValue(List.of(
                new Materials.Section(1, "Magic Items", 1), new Materials.Section(2, "Vorpal Sword", 3)));
        // A ciphertext moved to another row fails its integrity check rather than being shown.
        jdbc.sql("update passage set text = (select text from passage where id = ?) where version_id = ? and id <> ?")
                .params(rows.get(0).get("id"), chain.versionId(), rows.get(0).get("id")).update();
        assertThatThrownBy(() -> as(chain.owner(), () -> materials.passage(id))).isInstanceOf(ApiException.class);
    }

    @Test
    void aFailureEndsTheHeldStepWithoutAJobOnPostgres() throws Exception {
        Chain chain = completed(pdf(MIB));
        JobClaims.Claim clean = claims.claim(chain.jobId());
        JobFiles.Stored original = store(chain, clean, pdf(4000));
        JobFiles.Stored batch = store(chain, clean, pdf(3000));
        claims.report(chain.jobId(), clean.claimId(), clean.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"4\",\"pageTotal\":3,\"original\":{\"fileId\":\"" + original.fileId() + "\"},"
                        + "\"batches\":[{\"fileId\":\"" + batch.fileId() + "\",\"firstPage\":1,\"lastPage\":3}],"
                        + "\"next\":[" + layoutStep(1, 3, batch.fileId()) + ",{\"stage\":\"passages\",\"firstPage\":1,"
                        + "\"lastPage\":3,\"input\":\"texts\"}]}"));
        JobClaims.Claim layout = claimOffered(chain);

        claims.report(layout.jobId(), layout.claimId(), layout.grant(), "failed", "ingestion.step_failed", false, null);

        assertThat(state(chain.versionId())).isEqualTo("failed");
        assertThat(jdbc.sql("select job_id, ended_at is not null as ended from ingestion_step where version_id = ?"
                + " and stage = 'passages'").param(chain.versionId()).query().singleRow())
                .containsEntry("job_id", null).containsEntry("ended", true);
        assertThat(jdbc.sql("select count(*) from ingestion_step where version_id = ? and ended_at is null")
                .param(chain.versionId()).query(Long.class).single()).isZero();
    }

    @Test
    void theStory118TablesCheckTheirValuesAndKeepTheirVersionWithinTheOwner() throws Exception {
        Chain a = completed(pdf(MIB));
        Chain b = completed(pdf(MIB));
        UUID passage = Ids.newId();
        jdbc.sql("insert into passage (id, owner_id, version_id, ordinal, page, text, place, created_at)"
                + " values (?, ?, ?, 0, 1, ?, ?, now())").params(passage, a.owner(), a.versionId(), new byte[] {1},
                new byte[] {2}).update();
        UUID outline = Ids.newId();
        jdbc.sql("insert into version_outline (id, owner_id, version_id, outline, created_at) values (?, ?, ?, ?, now())")
                .params(outline, a.owner(), a.versionId(), new byte[] {3}).update();
        Map<String, String> violations = Map.of(
                "update passage set page = 0 where id = '" + passage + "'", "passage_page_check",
                "update passage set ordinal = -1 where id = '" + passage + "'", "passage_ordinal_check",
                "update passage set version_id = '" + b.versionId() + "' where id = '" + passage + "'",
                "passage_version_fkey",
                "update version_outline set version_id = '" + b.versionId() + "' where id = '" + outline + "'",
                "version_outline_version_fkey",
                "insert into version_outline (id, owner_id, version_id, outline, created_at) values ('" + Ids.newId()
                        + "', '" + a.owner() + "', '" + a.versionId() + "', '\\x01', now())",
                "version_outline_owner_version_key",
                "insert into passage (id, owner_id, version_id, ordinal, page, text, place, created_at) values ('"
                        + Ids.newId() + "', '" + a.owner() + "', '" + a.versionId() + "', 0, 1, '\\x01', '\\x01', now())",
                "passage_owner_version_ordinal_key",
                "update ingestion_step set job_id = null where job_id = '" + a.jobId() + "'", "ingestion_step_held_check",
                "update ingestion_step set input_texts = true where job_id = '" + a.jobId() + "'",
                "ingestion_step_input_check");
        violations.forEach((sql, constraint) -> assertThatThrownBy(() -> jdbc.sql(sql).update())
                .as(sql).satisfies(failure -> assertThat(messages(failure)).anyMatch(text -> text.contains(constraint))));
    }

    @Test
    void anotherOwnersPassagesAndOutlineAreNeverSeenOrWritten() throws Exception {
        Chain a = completed(pdf(MIB));
        Chain b = completed(pdf(MIB));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID passage = Ids.newId();
        write(a.owner(), () -> {
            store.insertPassages(List.of(new PassageRow(passage, a.owner(), a.versionId(), 0, 1, new byte[] {1},
                    new byte[] {2}, now)));
            store.insertOutline(new OutlineRow(Ids.newId(), a.owner(), a.versionId(), new byte[] {3}, now));
        });

        // Matrix row "Two owners": empty for B, through the store and the service.
        as(b.owner(), () -> {
            assertThat(store.findPassage(passage)).isEmpty();
            assertThat(store.passages(a.versionId())).isEmpty();
            assertThat(store.findOutline(a.versionId())).isEmpty();
            assertThat(materials.passage(passage)).isEmpty();
            assertThat(materials.outline(a.versionId())).isEmpty();
            return null;
        });
        write(b.owner(), () -> {
            assertThat(store.deletePassages(a.versionId())).isZero();
            assertThat(store.deleteOutline(a.versionId())).isFalse();
        });
        as(a.owner(), () -> {
            assertThat(store.findPassage(passage)).isPresent();
            assertThat(store.passages(a.versionId())).hasSize(1);
            assertThat(store.findOutline(a.versionId())).isPresent();
            return null;
        });
        // B cannot write a passage or an outline naming A's version, nor A one under B's owner id.
        assertThatThrownBy(() -> write(b.owner(), () -> store.insertPassages(List.of(new PassageRow(Ids.newId(),
                b.owner(), a.versionId(), 1, 1, new byte[] {1}, new byte[] {2}, now))))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> write(b.owner(), () -> store.insertOutline(new OutlineRow(Ids.newId(), b.owner(),
                a.versionId(), new byte[] {3}, now)))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> write(a.owner(), () -> store.insertPassages(List.of(new PassageRow(Ids.newId(),
                b.owner(), b.versionId(), 0, 1, new byte[] {1}, new byte[] {2}, now))))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.sql("select count(*) from passage where version_id in (?, ?)").params(a.versionId(),
                b.versionId()).query(Long.class).single()).isEqualTo(1);
    }

    // --- Story 1.19: page views

    @Test
    void pageViewsAreStoredEncryptedWithTheirPicturesAndServedOnTheReviewGroundOnPostgres() throws Exception {
        Chain chain = completed(pdf(MIB));
        JobClaims.Claim clean = claims.claim(chain.jobId());
        JobFiles.Stored original = store(chain, clean, pdf(4000));
        JobFiles.Stored batch = store(chain, clean, pdf(3000));
        claims.report(chain.jobId(), clean.claimId(), clean.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"5\",\"pageTotal\":3,\"original\":{\"fileId\":\"" + original.fileId() + "\"},"
                        + "\"batches\":[{\"fileId\":\"" + batch.fileId() + "\",\"firstPage\":1,\"lastPage\":3}],"
                        + "\"next\":[" + layoutStep(1, 3, batch.fileId()) + "]}"));
        JobClaims.Claim layout = claimOffered(chain);
        JobFiles.Stored text = jobFiles.store(layout.jobId(), layout.claimId(), layout.grant(), -1,
                new ByteArrayInputStream("{\"pages\":[]}".getBytes(StandardCharsets.UTF_8)));
        List<byte[]> pictures = new ArrayList<>();
        StringBuilder views = new StringBuilder();
        for (int page = 1; page <= 3; page++) {
            byte[] picture = webp(2 * MIB + page);
            pictures.add(picture);
            UUID file = jobFiles.store(layout.jobId(), layout.claimId(), layout.grant(), -1,
                    new ByteArrayInputStream(picture)).fileId();
            views.append(page == 1 ? "" : ",").append("{\"page\":").append(page).append(",\"picture\":{\"fileId\":\"")
                    .append(file).append("\",\"width\":1545,\"height\":2000},\"lines\":[{\"text\":\"Plate | 1,500 gp | ")
                    .append(page).append("\",\"box\":[0.1,0.2,0.9,0.25]}]}");
        }
        claims.report(layout.jobId(), layout.claimId(), layout.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"5\",\"pages\":[" + range(1, 3, "outcome", "read_directly") + "],\"texts\":"
                        + "[{\"fileId\":\"" + text.fileId() + "\",\"firstPage\":1,\"lastPage\":3}],\"pageViews\":["
                        + views + "],\"next\":[]}"));
        assertThat(state(chain.versionId())).isEqualTo("needs_review");

        List<Map<String, Object>> rows = jdbc.sql("select id, owner_id, page, picture_file_id, width, height, content"
                + " from page_view where version_id = ? order by page").param(chain.versionId()).query().listOfRows();
        assertThat(rows).extracting(row -> row.get("page")).containsExactly(1, 2, 3);
        for (Map<String, Object> row : rows) {
            assertThat(row).containsEntry("owner_id", chain.owner()).containsEntry("width", 1545)
                    .containsEntry("height", 2000);
            assertThat(new String((byte[]) row.get("content"), StandardCharsets.ISO_8859_1)).doesNotContain("Plate")
                    .doesNotContain("1,500");
            assertThat(jdbc.sql("select role, first_page, last_page, piece_count from version_file where file_id = ?")
                    .param(row.get("picture_file_id")).query().singleRow()).containsEntry("role", "picture")
                    .containsEntry("first_page", row.get("page")).containsEntry("last_page", row.get("page"))
                    .containsEntry("piece_count", 1);
        }
        Materials.VersionPage page = as(chain.owner(), () -> materials.page(chain.materialId(), 2,
                Materials.Ground.REVIEW)).orElseThrow();
        assertThat(page.lines()).extracting(Materials.Line::text).containsExactly("Plate | 1,500 gp | 2");
        assertThat(page.picture()).isEqualTo(new Materials.PictureSize(1545, 2000));
        // The page's passages, read through the page index: page 2's only, in reading order (inserted out of it,
        // their ids minted in the reverse order).
        UUID third = Ids.newId();
        UUID first = Ids.newId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        write(chain.owner(), () -> store.insertPassages(List.of(passageRow(chain, third, 3, 2, now),
                passageRow(chain, Ids.newId(), 0, 1, now), passageRow(chain, Ids.newId(), 2, 3, now),
                passageRow(chain, first, 1, 2, now))));
        assertThat(as(chain.owner(), () -> materials.page(chain.materialId(), 2, Materials.Ground.REVIEW))
                .orElseThrow().passages()).extracting(Materials.PagePassage::id).containsExactly(first, third);
        assertThat(as(chain.owner(), () -> materials.page(chain.materialId(), 3, Materials.Ground.REVIEW))
                .orElseThrow().passages()).hasSize(1).allSatisfy(passage ->
                        assertThat(passage.boxes()).containsExactly(List.of(0.1, 0.2, 0.9, 0.3)));
        Materials.Picture picture = as(chain.owner(), () -> materials.picture(chain.materialId(), 2,
                Materials.Ground.REVIEW)).orElseThrow();
        ByteArrayOutputStream read = new ByteArrayOutputStream();
        picture.writeTo(read);
        assertThat(read.toByteArray()).isEqualTo(pictures.get(1));
        assertThat(as(chain.owner(), () -> materials.page(chain.materialId(), 4, Materials.Ground.REVIEW))).isEmpty();

        // A picture a view shows cannot go before its view; the views of a range go together, answered for removal.
        UUID shown = (UUID) rows.get(0).get("picture_file_id");
        assertThatThrownBy(() -> jdbc.sql("delete from version_file where file_id = ?").param(shown).update())
                .satisfies(failure -> assertThat(messages(failure)).anyMatch(m -> m.contains("page_view_picture_fkey")));
        write(chain.owner(), () -> {
            assertThat(store.deletePageViews(chain.versionId(), 2, 3)).extracting(row -> row.page())
                    .containsExactly(2, 3);
            assertThat(store.deleteVersionFile(chain.versionId(), (UUID) rows.get(1).get("picture_file_id")))
                    .isPresent();
        });
        assertThat(jdbc.sql("select count(*) from page_view where version_id = ?").param(chain.versionId())
                .query(Long.class).single()).isEqualTo(1);
        // A ciphertext moved to another row fails its integrity check rather than being shown.
        jdbc.sql("insert into page_view (id, owner_id, version_id, page, content, created_at) values (?, ?, ?, 3, ?,"
                + " now())").params(Ids.newId(), chain.owner(), chain.versionId(), rows.get(0).get("content")).update();
        assertThatThrownBy(() -> as(chain.owner(), () -> materials.page(chain.materialId(), 3,
                Materials.Ground.REVIEW))).isInstanceOf(ApiException.class);
    }

    @Test
    void aRestartRemovesThePageViewsBeforeTheirPicturesOnPostgres() throws Exception {
        Chain chain = completed(pdf(MIB));
        JobClaims.Claim clean = claims.claim(chain.jobId());
        JobFiles.Stored original = store(chain, clean, pdf(4000));
        JobFiles.Stored batch = store(chain, clean, pdf(3000));
        claims.report(chain.jobId(), clean.claimId(), clean.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"5\",\"pageTotal\":3,\"original\":{\"fileId\":\"" + original.fileId() + "\"},"
                        + "\"batches\":[{\"fileId\":\"" + batch.fileId() + "\",\"firstPage\":1,\"lastPage\":3}],"
                        + "\"next\":[" + layoutStep(1, 2, batch.fileId()) + "," + layoutStep(3, 3, batch.fileId())
                        + "]}"));
        JobClaims.Claim first = claimOffered(chain);
        UUID picture = jobFiles.store(first.jobId(), first.claimId(), first.grant(), -1,
                new ByteArrayInputStream(webp(5000))).fileId();
        claims.report(first.jobId(), first.claimId(), first.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"5\",\"pages\":[" + range(1, 2, "outcome", "unreadable") + "],\"pageViews\":"
                        + "[{\"page\":1,\"picture\":{\"fileId\":\"" + picture + "\",\"width\":10,\"height\":10},"
                        + "\"lines\":[]}],\"next\":[]}"));
        assertThat(jdbc.sql("select count(*) from page_view where version_id = ?").param(chain.versionId())
                .query(Long.class).single()).isEqualTo(1);
        JobClaims.Claim second = claimOffered(chain);

        claims.report(second.jobId(), second.claimId(), second.grant(), "failed", "ingestion.pipeline_changed", false,
                null);

        assertThat(state(chain.versionId())).isEqualTo("processing");
        assertThat(jdbc.sql("select count(*) from page_view where version_id = ?").param(chain.versionId())
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select role from version_file where version_id = ?").param(chain.versionId())
                .query(String.class).list()).containsExactly("original");
    }

    @Test
    void theStory119TableChecksItsValuesAndKeepsItsVersionAndPictureWithinTheOwner() throws Exception {
        Chain a = completed(pdf(MIB));
        Chain b = completed(pdf(MIB));
        UUID pictureA = Ids.newId();
        UUID pictureB = Ids.newId();
        jdbc.sql("insert into version_file (id, owner_id, version_id, file_id, role, first_page, last_page, size_bytes,"
                + " piece_count, created_at) values (?, ?, ?, ?, 'picture', 1, 1, 10, 1, now())")
                .params(Ids.newId(), a.owner(), a.versionId(), pictureA).update();
        jdbc.sql("insert into version_file (id, owner_id, version_id, file_id, role, first_page, last_page, size_bytes,"
                + " piece_count, created_at) values (?, ?, ?, ?, 'picture', 1, 1, 10, 1, now())")
                .params(Ids.newId(), b.owner(), b.versionId(), pictureB).update();
        UUID view = Ids.newId();
        jdbc.sql("insert into page_view (id, owner_id, version_id, page, picture_file_id, width, height, content,"
                + " created_at) values (?, ?, ?, 1, ?, 10, 20, ?, now())").params(view, a.owner(), a.versionId(),
                pictureA, new byte[] {1}).update();
        Map<String, String> violations = Map.of(
                "update page_view set page = 0 where id = '" + view + "'", "page_view_page_check",
                "update page_view set width = 0 where id = '" + view + "'", "page_view_width_check",
                "update page_view set height = 10001 where id = '" + view + "'", "page_view_height_check",
                "update page_view set width = null where id = '" + view + "'", "page_view_picture_check",
                "update page_view set picture_file_id = '" + pictureB + "' where id = '" + view + "'",
                "page_view_picture_fkey",
                "update page_view set version_id = '" + b.versionId() + "' where id = '" + view + "'",
                "page_view_version_fkey",
                "insert into page_view (id, owner_id, version_id, page, content, created_at) values ('" + Ids.newId()
                        + "', '" + a.owner() + "', '" + a.versionId() + "', 1, '\\x01', now())",
                "page_view_owner_version_page_key",
                "update version_file set role = 'thumbnail' where file_id = '" + pictureA + "'",
                "version_file_role_check");
        violations.forEach((sql, constraint) -> assertThatThrownBy(() -> jdbc.sql(sql).update())
                .as(sql).satisfies(failure -> assertThat(messages(failure)).anyMatch(text -> text.contains(constraint))));
        assertThat(jdbc.sql("select count(*) from pg_indexes where indexname = 'passage_owner_version_page'")
                .query(Long.class).single()).isEqualTo(1);

        // Matrix row "Two owners": B sees and removes none of A's page views, and cannot write one naming A's version.
        as(b.owner(), () -> {
            assertThat(store.findPageView(a.versionId(), 1)).isEmpty();
            assertThat(materials.page(a.materialId(), 1, Materials.Ground.REVIEW)).isEmpty();
            assertThat(materials.picture(a.materialId(), 1, Materials.Ground.REVIEW)).isEmpty();
            return null;
        });
        write(b.owner(), () -> assertThat(store.deletePageViews(a.versionId())).isZero());
        assertThatThrownBy(() -> write(b.owner(), () -> store.insertPageView(new PageViewRow(Ids.newId(), b.owner(),
                a.versionId(), 2, null, null, null, new byte[] {1}, Instant.now().truncatedTo(ChronoUnit.MICROS)))))
                .isInstanceOf(RuntimeException.class);
        as(a.owner(), () -> {
            assertThat(store.findPageView(a.versionId(), 1)).hasValueSatisfying(row ->
                    assertThat(row.pictureFileId()).isEqualTo(pictureA));
            return null;
        });
    }

    // --- Story 1.20: the review on Postgres

    @Test
    void reprocessAndAcceptRunTheirJpqlAndOnlyTheAcceptedVersionsPassagesAreReadOnPostgres() throws Exception {
        Chain chain = reviewed(4, List.of(2));
        assertThat(as(chain.owner(), () -> materials.find(chain.materialId())).orElseThrow().actions())
                .containsExactly("accept", "reprocess", "discard");
        Materials.Report report = as(chain.owner(), () -> materials.report(chain.materialId())).orElseThrow();
        assertThat(report.unreadable()).containsExactly(new Materials.PageRun(2, 2));
        assertThat(report.outline()).extracting(Materials.Section::title).containsExactly("Capítulo", "Seção");
        assertThat(report.warnings()).containsExactly(new Materials.Warning("report.pages_unreadable", 1));
        UUID passage = jdbc.sql("select id from passage where version_id = ? order by ordinal limit 1")
                .param(chain.versionId()).query(UUID.class).single();
        assertThat(as(chain.owner(), () -> materials.passage(passage))).isEmpty();
        UUID batch = jdbc.sql("select file_id from version_file where version_id = ? and role = 'batch'")
                .param(chain.versionId()).query(UUID.class).single();

        as(chain.owner(), () -> actions.act(chain.materialId(), "reprocess"));

        assertThat(state(chain.versionId())).isEqualTo("processing");
        assertThat(jdbc.sql("select stage, first_page, last_page, input_file_id, input_texts, job_id is null as held"
                        + " from ingestion_step where version_id = ? and ended_at is null order by input_texts")
                .param(chain.versionId()).query().listOfRows()).satisfiesExactly(
                        reread -> assertThat(reread).containsEntry("stage", "layout").containsEntry("first_page", 2)
                                .containsEntry("last_page", 2).containsEntry("input_file_id", batch)
                                .containsEntry("held", false),
                        held -> assertThat(held).containsEntry("stage", "passages").containsEntry("first_page", 1)
                                .containsEntry("last_page", 4).containsEntry("input_texts", true)
                                .containsEntry("held", true));
        assertThat(jdbc.sql("select count(*) from page_outcome where version_id = ? and outcome = 'unreadable'")
                .param(chain.versionId()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select page from page_view where version_id = ? order by page").param(chain.versionId())
                .query(Integer.class).list()).containsExactly(1, 3, 4);
        assertThat(jdbc.sql("select count(*) from version_file where version_id = ? and role = 'picture'")
                .param(chain.versionId()).query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("select count(*) from passage where version_id = ?").param(chain.versionId())
                .query(Long.class).single()).isZero();

        JobClaims.Claim reread = claimOffered(chain);
        assertThat(reread.attempt()).isEqualTo(1);
        layoutReport(chain, reread, 2, 2, List.of(), List.of(2));
        passagesStep(chain);
        assertThat(state(chain.versionId())).isEqualTo("needs_review");
        assertThat(as(chain.owner(), () -> materials.find(chain.materialId())).orElseThrow().actions())
                .containsExactly("accept", "discard");

        Materials.Material accepted = as(chain.owner(), () -> actions.act(chain.materialId(), "accept")).orElseThrow();

        assertThat(accepted.state()).isEqualTo("ready");
        UUID newPassage = jdbc.sql("select id from passage where version_id = ? order by ordinal limit 1")
                .param(chain.versionId()).query(UUID.class).single();
        assertThat(as(chain.owner(), () -> materials.passage(newPassage))).isPresent();
        assertThatThrownBy(() -> as(chain.owner(), () -> actions.act(chain.materialId(), "accept")))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.problem().code())
                        .isEqualTo("material.action_unavailable"));
        // Another owner: not found, and nothing changes.
        assertThat(as(Ids.newId(), () -> actions.act(chain.materialId(), "discard"))).isEmpty();
        assertThat(as(Ids.newId(), () -> materials.report(chain.materialId()))).isEmpty();
        assertThat(state(chain.versionId())).isEqualTo("ready");
    }

    @Test
    void aDiscardLeavesNoRowOrFileOfItsVersionAndTheSweepFinishesAnInterruptedOneOnPostgres() throws Exception {
        Chain chain = reviewed(3, List.of(3));
        List<UUID> stored = jdbc.sql("select file_id from version_file where version_id = ?").param(chain.versionId())
                .query(UUID.class).list();
        assertThat(stored).hasSizeGreaterThan(4);
        long steps = jdbc.sql("select count(*) from ingestion_step where version_id = ?").param(chain.versionId())
                .query(Long.class).single();

        Materials.Material discarded = as(chain.owner(), () -> actions.act(chain.materialId(), "discard"))
                .orElseThrow();

        assertThat(discarded.state()).isEqualTo("discarded");
        assertThat(discarded.actions()).isEmpty();
        assertNothingLeft(chain, stored);
        assertThat(jdbc.sql("select count(*) from ingestion_step where version_id = ?").param(chain.versionId())
                .query(Long.class).single()).isEqualTo(steps);

        // Interrupted: the discard committed, and the backend stopped before the removal.
        Chain other = reviewed(3, List.of());
        List<UUID> otherFiles = jdbc.sql("select file_id from version_file where version_id = ?")
                .param(other.versionId()).query(UUID.class).list();
        write(other.owner(), () -> store.discardVersion(other.versionId(), Instant.now()));
        assertThat(pending.list(1000)).contains(new PendingRemovals.Pending(other.owner(), other.versionId()));
        assertThat(jdbc.sql("select count(*) from page_view where version_id = ?").param(other.versionId())
                .query(Long.class).single()).isEqualTo(3);

        assertThat(removals.sweep()).isGreaterThanOrEqualTo(1);

        assertNothingLeft(other, otherFiles);
        assertThat(pending.list(1000)).doesNotContain(new PendingRemovals.Pending(other.owner(), other.versionId()));
    }

    @Autowired
    private MaterialActions actions;
    @Autowired
    private VersionRemovalSweep removals;
    @Autowired
    private PendingRemovals pending;

    private void assertNothingLeft(Chain chain, List<UUID> stored) {
        Map<String, Object> version = jdbc.sql("select state, removal_pending from material_version where id = ?")
                .param(chain.versionId()).query().singleRow();
        assertThat(version).containsEntry("state", "discarded").containsEntry("removal_pending", false);
        for (String table : List.of("page_view", "passage", "version_outline", "page_layer", "page_outcome",
                "version_file", "upload")) {
            assertThat(jdbc.sql("select count(*) from " + table + " where version_id = ?").param(chain.versionId())
                    .query(Long.class).single()).as(table).isZero();
        }
        assertThat(stored).allSatisfy(file -> assertThat(files.fileExists(chain.owner(), file)).isFalse());
        assertThat(files.fileExists(chain.owner(), chain.uploadId())).isFalse();
    }

    /**
     * A PDF of {@code total} pages (at least 3: the passages are on pages 1 and 3) in one batch, cleaned with a held
     * passages step, read by one layout step per two pages with a picture view of each page, {@code unreadable} pages
     * unreadable, then its passages: it needs review.
     */
    private Chain reviewed(int total, List<Integer> unreadable) throws Exception {
        Chain chain = completed(pdf(MIB));
        JobClaims.Claim clean = claims.claim(chain.jobId());
        JobFiles.Stored original = store(chain, clean, pdf(4000));
        JobFiles.Stored batch = store(chain, clean, pdf(3000));
        StringBuilder next = new StringBuilder();
        for (int first = 1; first <= total; first += 2) {
            next.append(layoutStep(first, Math.min(total, first + 1), batch.fileId())).append(',');
        }
        claims.report(chain.jobId(), clean.claimId(), clean.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"5\",\"pageTotal\":" + total + ",\"original\":{\"fileId\":\""
                        + original.fileId() + "\"},\"batches\":[{\"fileId\":\"" + batch.fileId()
                        + "\",\"firstPage\":1,\"lastPage\":" + total + "}],\"next\":[" + next
                        + "{\"stage\":\"passages\",\"firstPage\":1,\"lastPage\":" + total + ",\"input\":\"texts\"}]}"));
        for (int first = 1; first <= total; first += 2) {
            JobClaims.Claim layout = claimOffered(chain);
            int last = layout.input().path("lastPage").asInt();
            int from = layout.input().path("firstPage").asInt();
            List<Integer> views = new ArrayList<>();
            for (int page = from; page <= last; page++) {
                views.add(page);
            }
            layoutReport(chain, layout, from, last, unreadable.stream().filter(page -> page >= from && page <= last)
                    .toList(), views);
        }
        passagesStep(chain);
        assertThat(state(chain.versionId())).isEqualTo("needs_review");
        return chain;
    }

    /** Reports a layout claim: pages read but {@code unreadable}, a text file of the read ones, a view of {@code views}. */
    private void layoutReport(Chain chain, JobClaims.Claim layout, int first, int last, List<Integer> unreadable,
                              List<Integer> views) throws Exception {
        StringBuilder pages = new StringBuilder();
        StringBuilder texts = new StringBuilder();
        for (int page = first; page <= last; page++) {
            boolean lost = unreadable.contains(page);
            pages.append(pages.isEmpty() ? "" : ",").append(range(page, page, "outcome",
                    lost ? "unreadable" : "read_directly"));
            if (!lost) {
                UUID text = jobFiles.store(layout.jobId(), layout.claimId(), layout.grant(), -1,
                        new ByteArrayInputStream("{\"pages\":[]}".getBytes(StandardCharsets.UTF_8))).fileId();
                texts.append(texts.isEmpty() ? "" : ",").append("{\"fileId\":\"").append(text)
                        .append("\",\"firstPage\":").append(page).append(",\"lastPage\":").append(page).append('}');
            }
        }
        StringBuilder pageViews = new StringBuilder();
        for (int page : views) {
            UUID picture = jobFiles.store(layout.jobId(), layout.claimId(), layout.grant(), -1,
                    new ByteArrayInputStream(webp(3000 + page))).fileId();
            pageViews.append(pageViews.isEmpty() ? "" : ",").append("{\"page\":").append(page)
                    .append(",\"picture\":{\"fileId\":\"").append(picture).append("\",\"width\":10,\"height\":10},")
                    .append("\"lines\":[]}");
        }
        claims.report(layout.jobId(), layout.claimId(), layout.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"5\",\"pages\":[" + pages + "],\"texts\":[" + texts + "],\"pageViews\":["
                        + pageViews + "],\"next\":[]}"));
    }

    /** Claims the offered passages step and reports its passages and outline. */
    private void passagesStep(Chain chain) throws Exception {
        JobClaims.Claim passages = claimOffered(chain);
        claims.report(passages.jobId(), passages.claimId(), passages.grant(), "done", null, null,
                JSON.readTree(passagesReport("Texto", "Capítulo", "Seção").replace("\"pipelineVersion\":\"4\"",
                        "\"pipelineVersion\":\"5\"")));
    }

    /** A passage of the chain's version, encrypted as a report stores it. */
    private PassageRow passageRow(Chain chain, UUID id, int ordinal, int page, Instant now) {
        return new PassageRow(id, chain.owner(), chain.versionId(), ordinal, page,
                vault.encryptText(Passages.text(id, chain.owner()), "Passagem " + ordinal),
                vault.encrypt(Passages.place(id, chain.owner()), Passages.placeJson(new Passages.Place(List.of("A"),
                        List.of(List.of(0.1, 0.2, 0.9, 0.3)), null))), now);
    }

    /** A WebP file's first bytes, then a pattern: the backend never parses a picture. */
    private static byte[] webp(int size) {
        byte[] content = new byte[size];
        byte[] head = "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(head, 0, content, 0, head.length);
        for (int i = head.length; i < size; i++) {
            content[i] = (byte) (i * 13 + (i >>> 7));
        }
        return content;
    }

    /** A passages report: page 1 under the first title, page 3 under both, and the outline of the two. */
    private static String passagesReport(String text, String chapter, String section) {
        return "{\"pipelineVersion\":\"4\",\"passages\":[{\"page\":1,\"sectionPath\":[\"" + chapter + "\"],\"text\":\""
                + text + " 1\",\"boxes\":[[0,0,1,0.5]]},{\"page\":3,\"sectionPath\":[\"" + chapter + "\",\"" + section
                + "\"],\"text\":\"" + text + " 3\",\"boxes\":[[0,0.5,1,1]]}],\"outline\":[{\"level\":1,\"title\":\""
                + chapter + "\",\"page\":1},{\"level\":2,\"title\":\"" + section + "\",\"page\":3}],\"next\":[]}";
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
