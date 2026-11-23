package io.github.gmshield.materials.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.gmshield.conversations.data.SearchResultRow;
import io.github.gmshield.conversations.data.SearchStore;
import io.github.gmshield.conversations.service.Searches;
import io.github.gmshield.jobs.service.CallUsage;
import io.github.gmshield.jobs.service.JobClaims;
import io.github.gmshield.jobs.service.JobFiles;
import io.github.gmshield.jobs.service.JobSweep;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.knowledge.data.RecordRow;
import io.github.gmshield.knowledge.data.RecordStore;
import io.github.gmshield.knowledge.service.Ontology;
import io.github.gmshield.knowledge.service.Records;
import io.github.gmshield.library.service.Language;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.StaleIndexes;
import io.github.gmshield.materials.data.OutlineRow;
import io.github.gmshield.materials.data.PageRangeRow;
import io.github.gmshield.materials.data.PageViewRow;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.PendingRemovals;
import io.github.gmshield.materials.data.StepRow;
import io.github.gmshield.materials.data.VersionFileRow;
import io.github.gmshield.scope.service.CampaignScopes;
import io.github.gmshield.scope.service.ResolvedScope;
import io.github.gmshield.scope.service.ScopeSettings;
import io.github.gmshield.scope.service.ScopedRecord;
import io.github.gmshield.scope.service.Scopes;
import io.github.gmshield.scope.service.Tier;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
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
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
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
 * Story 2.1: V15, an accept's index changes in its transaction (none when it rolls back), their claim input and
 * passages, the recipe recorded once all are done, the reconcile under another recipe, and a discard's removal.
 *
 * <p>The raw rows are read here with SQL to prove what is stored; the application never reads the library tables with
 * native SQL, but for the removal sweep's list of the ids of versions whose removal is pending.
 */
@SpringBootTest(properties = {"GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
    "gmshield.materials.upload-sweep.enabled=false",
    "gmshield.materials.version-removal-sweep.enabled=false",
    "gmshield.library.deletion-sweep.enabled=false",
    "gmshield.materials.index-retry-sweep.enabled=false",
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
    @Autowired
    private ImageSets imageSets;
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
    void aSetsImagesStepReadsEachImageInOrderAndItsReportRemovesEveryImagesRawUpload() throws Exception {
        UUID owner = Ids.newId();
        byte[] first = image(0xd8, 70_000);
        byte[] second = image(0x50, 9000);
        ImageSets.Created set = as(owner, () -> imageSets.create("Ficha", Uploads.Details.NONE, List.of(
                new ImageSets.Image("b.jpg", first.length), new ImageSets.Image("a.png", second.length))));
        UUID one = set.images().get(0).uploadId();
        UUID two = set.images().get(1).uploadId();
        as(owner, () -> uploads.patch(two, 0, second.length, sha(second), new java.io.ByteArrayInputStream(second)));
        as(owner, () -> uploads.complete(two, sha(second)));
        as(owner, () -> uploads.patch(one, 0, first.length, sha(first), new java.io.ByteArrayInputStream(first)));
        assertThat(as(owner, () -> uploads.complete(one, sha(first))).state()).isEqualTo("waiting");
        UUID jobId = jdbc.sql("select job_id from ingestion_step where version_id = ?").param(set.setId())
                .query(UUID.class).single();
        Chain chain = new Chain(owner, set.materialId(), set.setId(), one, jobId);

        JobClaims.Claim claim = claims.claim(jobId);
        assertThat(claim.input().path("stage").asString()).isEqualTo("images");
        assertThat(claim.input().path("format").asString()).isEqualTo("images");
        assertThat(claim.input().path("inputs")).extracting(item -> item.path("name").asString())
                .containsExactly("image-" + one, "image-" + two);
        for (Object[] expected : List.of(new Object[] {one, first}, new Object[] {two, second})) {
            ByteArrayOutputStream read = new ByteArrayOutputStream();
            jobFiles.open(jobId, claim.claimId(), claim.grant(), "image-" + expected[0]).input().writeTo(read);
            assertThat(read.toByteArray()).isEqualTo((byte[]) expected[1]);
        }
        JobFiles.Stored original = store(chain, claim, pdf(6000));
        JobFiles.Stored batch = store(chain, claim, pdf(5000));
        claims.report(jobId, claim.claimId(), claim.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"6\",\"pageTotal\":2,\"original\":{\"fileId\":\"" + original.fileId() + "\"},"
                        + "\"batches\":[{\"fileId\":\"" + batch.fileId() + "\",\"firstPage\":1,\"lastPage\":2}],"
                        + "\"next\":[{\"stage\":\"triage\",\"firstPage\":1,\"lastPage\":2,\"input\":\""
                        + batch.fileId() + "\"},{\"stage\":\"passages\",\"firstPage\":1,\"lastPage\":2,"
                        + "\"input\":\"texts\"}]}"));
        assertThat(jdbc.sql("select count(*) from upload where version_id = ?").param(set.setId()).query(Long.class)
                .single()).isZero();
        assertThat(files.fileExists(owner, one)).isFalse();
        assertThat(files.fileExists(owner, two)).isFalse();
        assertThat(jdbc.sql("select state, page_total from material_version where id = ?").param(set.setId()).query()
                .singleRow()).containsEntry("state", "processing").containsEntry("page_total", 2);
        assertThat(jdbc.sql("select stage, first_page, last_page from ingestion_step where version_id = ?"
                + " and stage = 'triage'").param(set.setId()).query().singleRow())
                .containsEntry("first_page", 1).containsEntry("last_page", 2);
        assertThat(jdbc.sql("select input_texts from ingestion_step where version_id = ? and stage = 'passages'")
                .param(set.setId()).query(Boolean.class).single()).isTrue();
    }

    private static byte[] image(int second, int size) {
        byte[] content = new byte[size];
        content[0] = (byte) (second == 0xd8 ? 0xff : 0x89);
        content[1] = (byte) second;
        if (second == 0xd8) {
            content[2] = (byte) 0xff;
        } else {
            System.arraycopy(new byte[] {'N', 'G', '\r', '\n', 0x1a, '\n'}, 0, content, 2, 6);
        }
        for (int i = 8; i < size; i++) {
            content[i] = (byte) (i * 11);
        }
        return content;
    }

    private static byte[] sha(byte[] content) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(content);
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

    // --- Story 3.4: a second version on Postgres

    @Test
    void aReplacementThroughTheRealFlowIsReviewedAndItsAcceptSupersedesTheOldVersionOnPostgres() throws Exception {
        Chain first = reviewed(4, List.of());
        as(first.owner(), () -> actions.act(first.materialId(), "accept"));
        freeTheIngestionLane();
        UUID oldPassage = jdbc.sql("select id from passage where version_id = ? order by ordinal limit 1")
                .param(first.versionId()).query(UUID.class).single();

        Chain second = reviewed(completed(first.owner(), pdf(MIB + 3),
                Uploads.Details.replacing(first.materialId().toString())), 4, List.of());

        assertThat(second.materialId()).isEqualTo(first.materialId());
        assertThat(state(first.versionId())).isEqualTo("ready");
        Materials.Material reviewing = as(first.owner(), () -> materials.find(first.materialId())).orElseThrow();
        assertThat(reviewing.state()).isEqualTo("needs_review");
        assertThat(reviewing.serving()).isEqualTo(new Materials.Serving(4, (long) MIB));
        assertThat(as(first.owner(), () -> materials.passage(oldPassage))).isPresent();

        // The accept's JPQL versionsOf finds the old version and supersedes it.
        Materials.Material accepted = as(first.owner(), () -> actions.act(first.materialId(), "accept")).orElseThrow();

        assertThat(accepted.state()).isEqualTo("ready");
        assertThat(accepted.serving()).isNull();
        assertThat(accepted.actions()).containsExactly("reprocess");
        assertThat(state(second.versionId())).isEqualTo("ready");
        assertThat(state(first.versionId())).isEqualTo("superseded");
        assertThat(jdbc.sql("select count(*) from material_version where material_id = ? and state = 'ready'")
                .param(first.materialId()).query(Long.class).single()).isOne();
        // The superseded version keeps its passages (part B removes them), and none is read as the material's.
        assertThat(jdbc.sql("select count(*) from passage where version_id = ?").param(first.versionId())
                .query(Long.class).single()).isPositive();
        assertThat(as(first.owner(), () -> materials.passage(oldPassage))).isEmpty();
    }

    @Test
    void reprocessingAnAcceptedVersionCopiesItsOriginalUnderANewFileIdOnPostgres() throws Exception {
        Chain chain = reviewed(4, List.of());
        as(chain.owner(), () -> actions.act(chain.materialId(), "accept"));
        freeTheIngestionLane();
        Map<String, Object> original = jdbc.sql("select file_id, size_bytes, piece_count from version_file"
                + " where version_id = ? and role = 'original'").param(chain.versionId()).query().singleRow();
        UUID originalFile = (UUID) original.get("file_id");
        List<Map<String, Object>> filesBefore = jdbc.sql("select * from version_file where version_id = ? order by id")
                .param(chain.versionId()).query().listOfRows();
        Map<String, Object> versionBefore = jdbc.sql("select * from material_version where id = ?")
                .param(chain.versionId()).query().singleRow();
        long passagesBefore = jdbc.sql("select count(*) from passage where version_id = ?").param(chain.versionId())
                .query(Long.class).single();

        Materials.Material reprocessed = as(chain.owner(), () -> actions.act(chain.materialId(), "reprocess"))
                .orElseThrow();

        assertThat(reprocessed.state()).isEqualTo("waiting");
        assertThat(reprocessed.serving()).isEqualTo(new Materials.Serving(4, (long) MIB));
        UUID fresh = jdbc.sql("select id from material_version where material_id = ? and id <> ?")
                .params(chain.materialId(), chain.versionId()).query(UUID.class).single();
        assertThat(jdbc.sql("select state, format, page_total, pipeline_version from material_version where id = ?")
                .param(fresh).query().singleRow()).containsEntry("state", "waiting").containsEntry("format", "pdf")
                .containsEntry("page_total", 4).containsEntry("pipeline_version", null);
        Map<String, Object> copy = jdbc.sql("select file_id, size_bytes, piece_count from version_file"
                + " where version_id = ? and role = 'original'").param(fresh).query().singleRow();
        UUID copied = (UUID) copy.get("file_id");
        assertThat(copied).isNotEqualTo(originalFile);
        assertThat(copy.get("size_bytes")).isEqualTo(original.get("size_bytes"));
        int pieces = (Integer) original.get("piece_count");
        assertThat(readBack(chain.owner(), copied, pieces)).isEqualTo(readBack(chain.owner(), originalFile, pieces));
        assertThat(jdbc.sql("select stage, input_file_id from ingestion_step where version_id = ?").param(fresh)
                .query().singleRow()).containsEntry("stage", "clean").containsEntry("input_file_id", copied);
        // The accepted version is not changed in any way.
        assertThat(jdbc.sql("select * from material_version where id = ?").param(chain.versionId()).query()
                .singleRow()).isEqualTo(versionBefore);
        assertThat(jdbc.sql("select * from version_file where version_id = ? order by id").param(chain.versionId())
                .query().listOfRows()).isEqualTo(filesBefore);
        assertThat(jdbc.sql("select count(*) from passage where version_id = ?").param(chain.versionId())
                .query(Long.class).single()).isEqualTo(passagesBefore);
        assertThatThrownBy(() -> as(chain.owner(), () -> actions.act(chain.materialId(), "reprocess")))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.problem().code())
                        .isEqualTo("material.action_unavailable"));
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

        // Interrupted: the discard committed, and the backend stopped before the removal. (The discard's index removal,
        // Story 2.1, holds the ingestion lane: it goes first.)
        freeTheIngestionLane();
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

    // --- Story 2.1: the index on Postgres

    @Autowired
    private StaleIndexes staleIndexes;
    @Autowired
    private IndexReconcile reconcile;

    @Test
    void anAcceptsIndexChangesAreWrittenInItsTransactionAndRecordTheirRecipeOnPostgres() throws Exception {
        Chain chain = reviewed(4, List.of());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        write(chain.owner(), () -> store.insertPassages(java.util.stream.IntStream.rangeClosed(2, 200)
                .mapToObj(ordinal -> passageRow(chain, Ids.newId(), ordinal, 4, now)).toList()));

        // Matrix row "Accept": rolled back with its transaction, the accept leaves no job and no index change.
        assertThatThrownBy(() -> as(chain.owner(), () -> new TransactionTemplate(transactions).execute(status -> {
            actions.act(chain.materialId(), "accept");
            throw new IllegalStateException("rolled back");
        }))).hasMessage("rolled back");
        assertThat(state(chain.versionId())).isEqualTo("needs_review");
        assertThat(jdbc.sql("select count(*) from index_change where version_id = ?").param(chain.versionId())
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from job where owner_id = ? and kind = 'index.change'")
                .param(chain.owner()).query(Long.class).single()).isZero();

        as(chain.owner(), () -> actions.act(chain.materialId(), "accept"));

        List<Map<String, Object>> changes = indexChanges(chain);
        assertThat(changes).satisfiesExactly(
                first -> assertThat(first).containsEntry("first_ordinal", 0).containsEntry("last_ordinal", 199)
                        .containsEntry("recipe", null).containsEntry("owner_id", chain.owner())
                        .containsEntry("kind", "index.change").containsEntry("lane", "ingestion"),
                second -> assertThat(second).containsEntry("first_ordinal", 200).containsEntry("last_ordinal", 200));
        assertThat(staleIndexes.owners("it-1")).contains(chain.owner());

        for (Map<String, Object> change : changes) {
            JobClaims.Claim claim = claims.claim((UUID) change.get("job_id"));
            assertThat(claim.input().path("source").asString()).isEqualTo(chain.versionId().toString());
            assertThat(claim.input().path("tokenKey").asString()).isEqualTo(java.util.Base64.getEncoder()
                    .encodeToString(vault.ownerTokenKey(chain.owner())));
            ByteArrayOutputStream read = new ByteArrayOutputStream();
            JobFiles.Opened opened = jobFiles.open(claim.jobId(), claim.claimId(), claim.grant(), "passages");
            opened.input().writeTo(read);
            List<JsonNode> lines = read.toString(StandardCharsets.UTF_8).lines()
                    .map(JSON::readTree).toList();
            assertThat(lines).hasSize((int) change.get("last_ordinal") - (int) change.get("first_ordinal") + 1);
            assertThat(lines.getFirst().path("ordinal").asInt()).isEqualTo(change.get("first_ordinal"));
            if ((int) change.get("first_ordinal") == 0) {
                assertThat(lines).extracting(line -> line.path("title").asString()).startsWith("Capítulo", "Seção", "A");
                assertThat(lines.getFirst().path("text").asString()).isEqualTo("Texto 1");
            }
            assertThat(versionRecipe(chain)).isNull();
            claims.report(claim.jobId(), claim.claimId(), claim.grant(), "done", null, null,
                    JSON.readTree("{\"indexRecipe\":\"it-1\"}"));
        }

        assertThat(versionRecipe(chain)).isEqualTo("it-1");
        assertThat(indexChanges(chain)).extracting(row -> row.get("recipe")).containsOnly("it-1");
        assertThat(staleIndexes.owners("it-1")).doesNotContain(chain.owner());

        // Matrix row "Recipe change": a new set under the new recipe, once.
        assertThat(reconcile.reconcile("it-2")).isPositive();
        List<Map<String, Object>> again = indexChanges(chain);
        assertThat(again).extracting(row -> row.get("first_ordinal"), row -> row.get("recipe"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, null), org.assertj.core.groups.Tuple.tuple(200, null));
        assertThat(again).extracting(row -> row.get("job_id")).doesNotContainAnyElementsOf(
                changes.stream().map(row -> row.get("job_id")).toList());
        assertThat(versionRecipe(chain)).isNull();
        reconcile.reconcile("it-3");
        assertThat(indexChanges(chain)).isEqualTo(again);
        // The new set (and every other owner's the reconciles made) would hold the shared ingestion lane.
        freeTheIngestionLane();
    }

    @Autowired
    private IndexRetrySweep retrySweep;

    @Test
    void theRetrySweepMovesOnlyAFailedRunToANewJobOnPostgres() throws Exception {
        Chain chain = reviewed(4, List.of());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        write(chain.owner(), () -> store.insertPassages(java.util.stream.IntStream.rangeClosed(2, 200)
                .mapToObj(ordinal -> passageRow(chain, Ids.newId(), ordinal, 4, now)).toList()));
        as(chain.owner(), () -> actions.act(chain.materialId(), "accept"));
        List<Map<String, Object>> changes = indexChanges(chain);
        assertThat(changes).hasSize(2);
        UUID failedJob = (UUID) changes.get(0).get("job_id");
        UUID doneJob = (UUID) changes.get(1).get("job_id");
        JobClaims.Claim failing = claims.claim(failedJob);
        claims.report(failing.jobId(), failing.claimId(), failing.grant(), "failed", "index.embedding_unavailable", false,
                null);
        JobClaims.Claim done = claims.claim(doneJob);
        claims.report(done.jobId(), done.claimId(), done.grant(), "done", null, null,
                JSON.readTree("{\"indexRecipe\":\"it-1\"}"));
        assertThat(jobs.ownersWithFailed("index.change")).contains(chain.owner());
        java.util.Set<UUID> failedJobs = as(chain.owner(), () -> new TransactionTemplate(transactions)
                .execute(status -> jobs.failed(List.of(failedJob, doneJob))));
        assertThat(failedJobs).containsExactly(failedJob);
        // Other tests' failed index changes are left out, so the sweep's count is this owner's alone.
        jdbc.sql("update job set state = 'cancelled' where kind = 'index.change' and state = 'failed'"
                + " and owner_id <> ?").param(chain.owner()).update();

        assertThat(retrySweep.sweep()).isEqualTo(1);

        List<Map<String, Object>> after = jdbc.sql("select c.job_id, c.first_ordinal, c.last_ordinal, c.recipe,"
                + " c.retries, j.kind, j.state from index_change c join job j on j.id = c.job_id where c.version_id = ?"
                + " order by c.first_ordinal").param(chain.versionId()).query().listOfRows();
        assertThat(after).satisfiesExactly(
                retried -> assertThat(retried).doesNotContainEntry("job_id", failedJob)
                        .containsEntry("first_ordinal", 0).containsEntry("last_ordinal", 199)
                        .containsEntry("recipe", null).containsEntry("retries", 1)
                        .containsEntry("kind", "index.change"),
                kept -> assertThat(kept).containsEntry("job_id", doneJob).containsEntry("recipe", "it-1")
                        .containsEntry("retries", 0));
        assertThat(jdbc.sql("select state from job where id = ?").param(failedJob).query(String.class).single())
                .isEqualTo("failed");
        // Matrix row "Retries used": a run at 3 retries whose job failed is not retried again.
        Object retriedJob = after.getFirst().get("job_id");
        jdbc.sql("update index_change set retries = 3 where job_id = ?").param(retriedJob).update();
        jdbc.sql("update job set state = 'failed', outcome_code = 'index.embedding_unavailable' where id = ?")
                .param(retriedJob).update();
        assertThat(retrySweep.sweep()).isZero();
        assertThat(jdbc.sql("select job_id, retries from index_change where version_id = ? and first_ordinal = 0")
                .param(chain.versionId()).query().singleRow())
                .containsEntry("job_id", retriedJob).containsEntry("retries", 3);
        // The retry check holds: a fourth retry is refused by the table.
        assertThatThrownBy(() -> jdbc.sql("update index_change set retries = 4 where job_id = ?")
                .param(retriedJob).update()).hasMessageContaining("index_change_retries_check");
        freeTheIngestionLane();
    }

    @Test
    void aDiscardCreatesTheRemovalOfItsVersionsPointsOnPostgres() throws Exception {
        Chain chain = reviewed(3, List.of());

        as(chain.owner(), () -> actions.act(chain.materialId(), "discard"));

        Map<String, Object> removal = jdbc.sql("select r.job_id, r.owner_id, j.kind, j.lane, j.state from index_removal r"
                + " join job j on j.id = r.job_id where r.version_id = ?").param(chain.versionId()).query().singleRow();
        assertThat(removal).containsEntry("owner_id", chain.owner()).containsEntry("kind", "index.removal")
                .containsEntry("lane", "ingestion").containsEntry("state", "offered");
        JobClaims.Claim claim = claims.claim((UUID) removal.get("job_id"));
        assertThat(claim.input().path("source").asString()).isEqualTo(chain.versionId().toString());
        assertThat(claim.input().path("owner").asString()).isEqualTo(chain.owner().toString());
        claims.report(claim.jobId(), claim.claimId(), claim.grant(), "done", null, null, JSON.readTree("{}"));
        assertThat(jdbc.sql("select state from job where id = ?").param(claim.jobId()).query(String.class).single())
                .isEqualTo("done");
        freeTheIngestionLane();
    }

    // --- Story 4.2 part B: the records runs on Postgres

    @Autowired
    private ExtractedRecords extracted;
    @Value("${gmshield.jobs.max-attempts}")
    private int maxAttempts;

    @Test
    void anAcceptWithRecordsCreatesRecordsRunsWhoseRecipeTheVersionWaitsForAndARetryKeepsThemOnPostgres()
            throws Exception {
        Chain chain = reviewed(4, List.of());
        UUID passageId = jdbc.sql("select id from passage where version_id = ? order by ordinal limit 1")
                .param(chain.versionId()).query(UUID.class).single();
        write(chain.owner(), () -> extracted.replaceRange(chain.versionId(), 1, 4, List.of(
                new ExtractedRecords.Extracted("spell", "Fireball", List.of(new ExtractedRecords.Field("Level", "3")),
                        List.of(passageId), 1),
                new ExtractedRecords.Extracted("item", "Rope", List.of(), List.of(passageId), 3))));
        List<UUID> recordIds = jdbc.sql("select id from record where version_id = ? order by first_page")
                .param(chain.versionId()).query(UUID.class).list();

        as(chain.owner(), () -> actions.act(chain.materialId(), "accept"));

        // One passages run, then one records run over pages 1 to 3: its first and last page in the ordinal columns.
        List<Map<String, Object>> changes = jdbc.sql("select c.job_id, c.first_ordinal, c.last_ordinal, c.records,"
                + " c.recipe, j.kind from index_change c join job j on j.id = c.job_id where c.version_id = ?"
                + " order by c.records, c.first_ordinal").param(chain.versionId()).query().listOfRows();
        assertThat(changes).satisfiesExactly(
                passages -> assertThat(passages).containsEntry("records", false).containsEntry("first_ordinal", 0)
                        .containsEntry("kind", "index.change"),
                records -> assertThat(records).containsEntry("records", true).containsEntry("first_ordinal", 1)
                        .containsEntry("last_ordinal", 3).containsEntry("kind", "index.change"));
        JobClaims.Claim passagesClaim = claims.claim((UUID) changes.get(0).get("job_id"));
        claims.report(passagesClaim.jobId(), passagesClaim.claimId(), passagesClaim.grant(), "done", null, null,
                JSON.readTree("{\"indexRecipe\":\"it-1\"}"));
        // The passages are indexed; the version still waits for its records run.
        assertThat(versionRecipe(chain)).isNull();

        UUID recordsJob = (UUID) changes.get(1).get("job_id");
        // Its last attempt: the worker reports Qdrant down with no retry flag, so the job ends failed.
        jdbc.sql("update job set attempt = ? where id = ?").params(maxAttempts - 1, recordsJob).update();
        JobClaims.Claim failing = claims.claim(recordsJob);
        assertThat(failing.input().path("records").asString()).isEqualTo("records");
        assertThat(failing.input().has("passages")).isFalse();
        claims.report(failing.jobId(), failing.claimId(), failing.grant(), "failed", "index.store_unavailable", null,
                null);
        assertThat(jdbc.sql("select state from job where id = ?").param(recordsJob).query(String.class).single())
                .isEqualTo("failed");
        jdbc.sql("update job set state = 'cancelled' where kind = 'index.change' and state = 'failed'"
                + " and owner_id <> ?").param(chain.owner()).update();

        assertThat(retrySweep.sweep()).isEqualTo(1);

        Map<String, Object> moved = jdbc.sql("select job_id, first_ordinal, last_ordinal, records, retries"
                + " from index_change where version_id = ? and records").param(chain.versionId()).query().singleRow();
        assertThat(moved).doesNotContainEntry("job_id", recordsJob).containsEntry("first_ordinal", 1)
                .containsEntry("last_ordinal", 3).containsEntry("retries", 1);
        JobClaims.Claim retried = claims.claim((UUID) moved.get("job_id"));
        ByteArrayOutputStream read = new ByteArrayOutputStream();
        jobFiles.open(retried.jobId(), retried.claimId(), retried.grant(), "records").input().writeTo(read);
        List<JsonNode> lines = read.toString(StandardCharsets.UTF_8).lines().map(JSON::readTree)
                .toList();
        assertThat(lines).extracting(line -> line.path("id").asString())
                .containsExactlyElementsOf(recordIds.stream().map(UUID::toString).toList());
        assertThat(lines.getFirst().toString()).isEqualTo("{\"id\":\"" + recordIds.getFirst() + "\",\"page\":1,"
                + "\"type\":\"spell\",\"name\":\"Fireball\",\"fields\":[{\"name\":\"Level\",\"value\":\"3\"}]}");
        claims.report(retried.jobId(), retried.claimId(), retried.grant(), "done", null, null,
                JSON.readTree("{\"indexRecipe\":\"it-1\"}"));

        assertThat(versionRecipe(chain)).isEqualTo("it-1");
        freeTheIngestionLane();
    }

    @Test
    void recordsWrittenLaterPagesFirstAreRunAndServedByFirstPageThenOrdinalAndAReconcileCutsOnlyThemAgainOnPostgres()
            throws Exception {
        Chain chain = reviewed(4, List.of());
        UUID passageId = jdbc.sql("select id from passage where version_id = ? order by ordinal limit 1")
                .param(chain.versionId()).query(UUID.class).single();
        // The records step of pages 11-20 reports before the one of pages 1-10, and neither lists its records by page:
        // record ids, minted in that order, follow neither the pages nor the ordinals.
        List<ExtractedRecords.Extracted> later = new ArrayList<>();
        later.add(record("Tarde 15", 15, passageId));
        IntStream.range(0, 200).forEach(i -> later.add(record("Página 12 n" + i, 12, passageId)));
        write(chain.owner(), () -> extracted.replaceRange(chain.versionId(), 11, 20, later));
        write(chain.owner(), () -> extracted.replaceRange(chain.versionId(), 1, 10, List.of(
                record("Cedo 3", 3, passageId), record("Cedo 1", 1, passageId))));
        List<UUID> byPage = jdbc.sql("select id from record where version_id = ? order by first_page, ordinal")
                .param(chain.versionId()).query(UUID.class).list();
        assertThat(jdbc.sql("select id from record where version_id = ? order by id").param(chain.versionId())
                .query(UUID.class).list()).isNotEqualTo(byPage);

        as(chain.owner(), () -> actions.act(chain.materialId(), "accept"));

        // The first run takes 200 records (pages 1, 3 and 198 of page 12), then the rest of page 12; then page 15.
        List<Map<String, Object>> changes = jdbc.sql("select job_id, first_ordinal, last_ordinal, records, recipe"
                + " from index_change where version_id = ? order by records, first_ordinal")
                .param(chain.versionId()).query().listOfRows();
        assertThat(changes).extracting(row -> row.get("records"), row -> row.get("first_ordinal"),
                row -> row.get("last_ordinal")).containsExactly(tuple(false, 0, 1), tuple(true, 1, 12),
                tuple(true, 15, 15));
        JobClaims.Claim passagesClaim = claims.claim((UUID) changes.get(0).get("job_id"));
        claims.report(passagesClaim.jobId(), passagesClaim.claimId(), passagesClaim.grant(), "done", null, null,
                JSON.readTree("{\"indexRecipe\":\"it-1\"}"));
        List<String> served = new ArrayList<>();
        for (Map<String, Object> change : changes.subList(1, 3)) {
            JobClaims.Claim claim = claims.claim((UUID) change.get("job_id"));
            ByteArrayOutputStream read = new ByteArrayOutputStream();
            jobFiles.open(claim.jobId(), claim.claimId(), claim.grant(), "records").input().writeTo(read);
            read.toString(StandardCharsets.UTF_8).lines().map(JSON::readTree)
                    .forEach(line -> served.add(line.path("id").asString()));
            claims.report(claim.jobId(), claim.claimId(), claim.grant(), "done", null, null,
                    JSON.readTree("{\"indexRecipe\":\"it-1\"}"));
        }
        // The records input lines follow "order by first_page, ordinal", across both steps.
        assertThat(served).containsExactlyElementsOf(byPage.stream().map(UUID::toString).toList());
        assertThat(versionRecipe(chain)).isEqualTo("it-1");

        // As if the page-15 run had failed for good instead (no recipe, its retries spent, the version none): the next
        // reconcile under it-1 cuts only the records runs again; the passages row stays, and the version's recipe waits
        // for the new runs.
        UUID lastRun = (UUID) changes.get(2).get("job_id");
        jdbc.sql("update material_version set index_recipe = null where id = ?").param(chain.versionId()).update();
        jdbc.sql("update index_change set recipe = null, retries = 3 where job_id = ?").param(lastRun).update();
        Map<String, Object> passagesRow = jdbc.sql("select job_id, first_ordinal, last_ordinal, recipe, retries"
                + " from index_change where version_id = ? and not records").param(chain.versionId()).query()
                .singleRow();

        assertThat(reconcile.reconcile("it-1")).isPositive();

        assertThat(jdbc.sql("select job_id, first_ordinal, last_ordinal, recipe, retries from index_change"
                + " where version_id = ? and not records").param(chain.versionId()).query().singleRow())
                .isEqualTo(passagesRow);
        List<Map<String, Object>> recut = jdbc.sql("select job_id, first_ordinal, last_ordinal, recipe, retries"
                + " from index_change where version_id = ? and records order by first_ordinal")
                .param(chain.versionId()).query().listOfRows();
        assertThat(recut).extracting(row -> row.get("first_ordinal"), row -> row.get("last_ordinal"),
                row -> row.get("recipe"), row -> row.get("retries")).containsExactly(tuple(1, 12, null, 0),
                tuple(15, 15, null, 0));
        assertThat(recut).extracting(row -> row.get("job_id")).doesNotContainAnyElementsOf(
                changes.stream().map(row -> row.get("job_id")).toList());
        assertThat(versionRecipe(chain)).isNull();
        freeTheIngestionLane();
    }

    private static ExtractedRecords.Extracted record(String name, int page, UUID passageId) {
        return new ExtractedRecords.Extracted("spell", name, List.of(new ExtractedRecords.Field("Nível", "1")),
                List.of(passageId), page);
    }

    private List<Map<String, Object>> indexChanges(Chain chain) {
        return jdbc.sql("select c.job_id, c.owner_id, c.first_ordinal, c.last_ordinal, c.recipe, j.kind, j.lane"
                + " from index_change c join job j on j.id = c.job_id where c.version_id = ? order by c.first_ordinal")
                .param(chain.versionId()).query().listOfRows();
    }

    private String versionRecipe(Chain chain) {
        return jdbc.sql("select index_recipe from material_version where id = ?").param(chain.versionId())
                .query((rs, row) -> rs.getString(1)).list().getFirst();
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
        return reviewed(completed(pdf(MIB)), total, unreadable);
    }

    /** {@link #reviewed(int, List)} for a chain already completed (Story 3.4: a replacement's). */
    private Chain reviewed(Chain chain, int total, List<Integer> unreadable) throws Exception {
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

    // --- Story 4.2: records

    @Autowired
    private Library library;
    @Autowired
    private Ontology ontology;
    @Autowired
    private RecordStore recordStore;

    @Test
    void recordsAreStoredEncryptedRemovedBeforeTheirPassagesClearedOnReprocessAndCountedOnceAccepted()
            throws Exception {
        UUID owner = Ids.newId();
        write(owner, () -> library.createStarter(Language.EN));
        UUID system = jdbc.sql("select id from system where owner_id = ?").param(owner).query(UUID.class).single();
        Chain chain = completed(owner, pdf(MIB), new Uploads.Details(null, null, null, List.of(system.toString())));
        JobClaims.Claim clean = claims.claim(chain.jobId());
        JobFiles.Stored original = store(chain, clean, pdf(4000));
        JobFiles.Stored batch = store(chain, clean, pdf(3000));
        claims.report(chain.jobId(), clean.claimId(), clean.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"5\",\"pageTotal\":3,\"original\":{\"fileId\":\"" + original.fileId()
                        + "\"},\"batches\":[{\"fileId\":\"" + batch.fileId() + "\",\"firstPage\":1,\"lastPage\":3}],"
                        + "\"next\":[" + layoutStep(1, 3, batch.fileId()) + ",{\"stage\":\"passages\",\"firstPage\":1,"
                        + "\"lastPage\":3,\"input\":\"texts\"}]}"));
        JobClaims.Claim layout = claimOffered(chain);
        layoutReport(chain, layout, 1, 3, List.of(2), List.of(1, 2, 3));
        recordsOf(chain, "Fireball");

        // Encrypted on PostgreSQL: the name and fields columns hold vault ciphertexts, bound to the record.
        Map<String, Object> row = jdbc.sql("select id, owner_id, type, first_page, ordinal, name, fields from record"
                + " where version_id = ?").param(chain.versionId()).query().singleRow();
        UUID recordId = (UUID) row.get("id");
        assertThat(row).containsEntry("owner_id", owner).containsEntry("type", "spell").containsEntry("first_page", 3)
                .containsEntry("ordinal", 0);
        for (String column : List.of("name", "fields")) {
            byte[] stored = (byte[]) row.get(column);
            assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("Fireball")
                    .doesNotContain("Evocation");
            assertThat(new String(stored, StandardCharsets.UTF_16BE)).doesNotContain("Fireball");
        }
        assertThat(vault.decryptText(new Binding("record", "name", recordId, owner), (byte[]) row.get("name")))
                .isEqualTo("Fireball");
        assertThat(new String(vault.decrypt(new Binding("record", "fields", recordId, owner),
                (byte[]) row.get("fields")), StandardCharsets.UTF_8))
                .isEqualTo("[{\"name\":\"School\",\"value\":\"Evocation\"}]");
        UUID passageOnThree = jdbc.sql("select id from passage where version_id = ? and page = 3")
                .param(chain.versionId()).query(UUID.class).single();
        assertThat(jdbc.sql("select owner_id, passage_id from record_passage where record_id = ?").param(recordId)
                .query().singleRow()).containsEntry("owner_id", owner).containsEntry("passage_id", passageOnThree);
        assertThat(jdbc.sql("select model_provider from job j join ingestion_step s on s.job_id = j.id"
                + " where s.version_id = ? and s.input_passages").param(chain.versionId()).query(String.class).single())
                .isEqualTo("azure-openai");
        assertThat(jdbc.sql("select count(*) from model_call_usage where owner_id = ?").param(owner).query(Long.class)
                .single()).isEqualTo(1L);

        // Nothing cascades: the passages cannot go while a record links to them, and another owner's record cannot
        // link to them at all.
        assertThatThrownBy(() -> write(owner, () -> store.deletePassages(chain.versionId())))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("record", chain.versionId())).isEqualTo(1);
        UUID other = Ids.newId();
        UUID foreign = Ids.newId();
        assertThatThrownBy(() -> write(other, () -> recordStore.insert(List.of(new RecordRow(foreign, other,
                chain.versionId(), 0, 1, "spell", new byte[] {1}, null, new byte[] {1}, List.of(passageOnThree),
                Instant.now().truncatedTo(ChronoUnit.MICROS)))))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.sql("select count(*) from record where id = ?").param(foreign).query(Long.class).single())
                .isZero();

        // Not accepted: the records tab counts nothing yet.
        assertThat(spellCount(owner, system)).isZero();

        // Reprocess: the records and their links go before the passages, in the reprocess's transaction.
        as(owner, () -> actions.act(chain.materialId(), "reprocess"));
        assertThat(count("record", chain.versionId())).isZero();
        assertThat(jdbc.sql("select count(*) from record_passage where record_id = ?").param(recordId).query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("select count(*) from ingestion_step where version_id = ? and records_unread")
                .param(chain.versionId()).query(Long.class).single()).isZero();
        JobClaims.Claim reread = claimOffered(chain);
        layoutReport(chain, reread, 2, 2, List.of(), List.of(2));
        recordsOf(chain, "Shield");
        assertThat(count("record", chain.versionId())).isEqualTo(1);

        // Accepted: the records of its ready version count under their core type.
        as(owner, () -> actions.act(chain.materialId(), "accept"));
        assertThat(spellCount(owner, system)).isEqualTo(1);

        // A version's removal (a replacement's discard) takes its records with it, before its passages. The accept's
        // index changes would hold the ingestion lane.
        freeTheIngestionLane();
        Chain replacement = completed(owner, pdf(MIB), new Uploads.Details(null, null, null, null,
                chain.materialId().toString()));
        reviewedWithRecords(replacement);
        assertThat(count("record", replacement.versionId())).isEqualTo(1);
        as(owner, () -> actions.act(replacement.materialId(), "discard"));
        assertThat(count("record", replacement.versionId())).isZero();
        assertThat(count("passage", replacement.versionId())).isZero();
        assertThat(spellCount(owner, system)).isEqualTo(1);
    }

    @Test
    void aRestartRemovesTheVersionsRecordsAndTheirLinksBeforeItsPassagesOnPostgres() throws Exception {
        Chain chain = completed(pdf(MIB));
        JobClaims.Claim clean = claims.claim(chain.jobId());
        JobFiles.Stored original = store(chain, clean, pdf(4000));
        JobFiles.Stored batch = store(chain, clean, pdf(3000));
        claims.report(chain.jobId(), clean.claimId(), clean.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"5\",\"pageTotal\":3,\"original\":{\"fileId\":\"" + original.fileId()
                        + "\"},\"batches\":[{\"fileId\":\"" + batch.fileId() + "\",\"firstPage\":1,\"lastPage\":3}],"
                        + "\"next\":[" + layoutStep(1, 3, batch.fileId()) + ",{\"stage\":\"passages\",\"firstPage\":1,"
                        + "\"lastPage\":3,\"input\":\"texts\"}]}"));
        layoutReport(chain, claimOffered(chain), 1, 3, List.of(), List.of(1, 2, 3));
        JobClaims.Claim passages = claimOffered(chain);
        claims.report(passages.jobId(), passages.claimId(), passages.grant(), "done", null, null,
                JSON.readTree(passagesReport("Texto", "Capítulo", "Seção").replace("\"pipelineVersion\":\"4\"",
                        "\"pipelineVersion\":\"5\"").replace("\"next\":[]", "\"next\":[{\"stage\":\"records\","
                        + "\"firstPage\":1,\"lastPage\":1,\"input\":\"passages\"},{\"stage\":\"records\","
                        + "\"firstPage\":2,\"lastPage\":3,\"input\":\"passages\"}]")));
        UUID page1 = jdbc.sql("select id from passage where version_id = ? and page = 1").param(chain.versionId())
                .query(UUID.class).single();
        JobClaims.Claim first = claimOfferedWithModel(chain);
        assertThat(first.input().path("firstPage").asInt()).isEqualTo(1);
        claims.report(first.jobId(), first.claimId(), first.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"5\",\"records\":[{\"type\":\"rule\",\"name\":\"Agarrar\",\"fields\":[],"
                        + "\"passageIds\":[\"" + page1 + "\"]}],\"next\":[]}"));
        assertThat(count("record", chain.versionId())).isEqualTo(1);
        UUID recordId = jdbc.sql("select id from record where version_id = ?").param(chain.versionId())
                .query(UUID.class).single();

        // The other records step reports under another pipeline: the chain restarts from the stored original.
        JobClaims.Claim second = claimOfferedWithModel(chain);
        claims.report(second.jobId(), second.claimId(), second.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"6\",\"records\":[],\"next\":[]}"));

        assertThat(count("record", chain.versionId())).isZero();
        assertThat(jdbc.sql("select count(*) from record_passage where record_id = ?").param(recordId).query(Long.class)
                .single()).isZero();
        assertThat(count("passage", chain.versionId())).isZero();
        assertThat(state(chain.versionId())).isEqualTo("processing");
        assertThat(jdbc.sql("select s.stage, s.input_file_id, j.state from ingestion_step s join job j on j.id = s.job_id"
                + " where s.version_id = ? and s.ended_at is null").param(chain.versionId()).query().singleRow())
                .containsEntry("stage", "clean").containsEntry("input_file_id", original.fileId())
                .containsEntry("state", "offered");
    }

    /** The version's step job that is offered, claimed stating the provider a records step's model needs. */
    private JobClaims.Claim claimOfferedWithModel(Chain chain) {
        UUID job = jdbc.sql("select s.job_id from ingestion_step s join job j on j.id = s.job_id"
                + " where s.version_id = ? and j.state = 'offered'").param(chain.versionId()).query(UUID.class).single();
        return claims.claim(job, null, List.of("azure-openai"));
    }

    /** A replacement read to review with a passage on each of its 3 pages and one spell record from page 3. */
    private void reviewedWithRecords(Chain chain) throws Exception {
        JobClaims.Claim clean = claims.claim(chain.jobId());
        JobFiles.Stored original = store(chain, clean, pdf(4000));
        JobFiles.Stored batch = store(chain, clean, pdf(3000));
        claims.report(chain.jobId(), clean.claimId(), clean.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"5\",\"pageTotal\":3,\"original\":{\"fileId\":\"" + original.fileId()
                        + "\"},\"batches\":[{\"fileId\":\"" + batch.fileId() + "\",\"firstPage\":1,\"lastPage\":3}],"
                        + "\"next\":[" + layoutStep(1, 3, batch.fileId()) + ",{\"stage\":\"passages\",\"firstPage\":1,"
                        + "\"lastPage\":3,\"input\":\"texts\"}]}"));
        layoutReport(chain, claimOffered(chain), 1, 3, List.of(), List.of(1, 2, 3));
        recordsOf(chain, "Bless");
    }

    /**
     * The offered passages step reports passages on pages 1 and 3 and a records step over the three pages, which is
     * claimed stating the provider and reports one spell named {@code name} from page 3, with one call's usage.
     */
    private void recordsOf(Chain chain, String name) throws Exception {
        JobClaims.Claim passages = claimOffered(chain);
        claims.report(passages.jobId(), passages.claimId(), passages.grant(), "done", null, null,
                JSON.readTree(passagesReport("Texto", "Capítulo", "Seção").replace("\"pipelineVersion\":\"4\"",
                        "\"pipelineVersion\":\"5\"").replace("\"next\":[]", "\"next\":[{\"stage\":\"records\","
                        + "\"firstPage\":1,\"lastPage\":3,\"input\":\"passages\"}]")));
        UUID job = jdbc.sql("select s.job_id from ingestion_step s join job j on j.id = s.job_id"
                + " where s.version_id = ? and j.state = 'offered'").param(chain.versionId()).query(UUID.class).single();
        JobClaims.Claim records = claims.claim(job, null, List.of("azure-openai"));
        assertThat(records.model().provider()).isEqualTo("azure-openai");
        assertThat(records.input().path("recordTypes").toString()).isEqualTo("[\"rule\",\"spell\",\"item\"]");
        UUID page3 = jdbc.sql("select id from passage where version_id = ? and page = 3").param(chain.versionId())
                .query(UUID.class).single();
        claims.report(job, records.claimId(), records.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"5\",\"records\":[{\"type\":\"spell\",\"name\":\"" + name + "\",\"fields\":"
                        + "[{\"name\":\"School\",\"value\":\"Evocation\"}],\"passageIds\":[\"" + page3 + "\"]}],"
                        + "\"next\":[]}"), List.of(new CallUsage(Ids.newId(), null, 900, 0, 0, 120, 0, false)));
        assertThat(state(chain.versionId())).isEqualTo("needs_review");
    }

    private long count(String table, UUID versionId) {
        return jdbc.sql("select count(*) from " + table + " where version_id = ?").param(versionId).query(Long.class)
                .single();
    }

    /** The records tab's count of the system's spell type. */
    private long spellCount(UUID owner, UUID system) throws Exception {
        return as(owner, () -> ontology.recordTypes(system, 0, 8)).orElseThrow().items().stream()
                .filter(type -> "spell".equals(type.core())).findFirst().orElseThrow().records();
    }

    // --- Story 4.4 (part A): ontology proposals on Postgres

    @Autowired
    private io.github.gmshield.knowledge.service.Proposals proposals;
    @Autowired
    private io.github.gmshield.knowledge.service.Terms terms;
    @Autowired
    private io.github.gmshield.library.service.SystemDeletion systemDeletion;

    @Test
    void proposalsAreStoredEncryptedShareTheSystemsNameSpaceAndGoWithTheirVersionAndMaterialOnPostgres()
            throws Exception {
        UUID owner = Ids.newId();
        write(owner, () -> library.createStarter(Language.EN));
        UUID system = jdbc.sql("select id from system where owner_id = ?").param(owner).query(UUID.class).single();
        Chain chain = completed(owner, pdf(MIB), new Uploads.Details(null, null, null, List.of(system.toString())));
        JobClaims.Claim clean = claims.claim(chain.jobId());
        JobFiles.Stored original = store(chain, clean, pdf(4000));
        JobFiles.Stored batch = store(chain, clean, pdf(3000));
        claims.report(chain.jobId(), clean.claimId(), clean.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"5\",\"pageTotal\":3,\"original\":{\"fileId\":\"" + original.fileId()
                        + "\"},\"batches\":[{\"fileId\":\"" + batch.fileId() + "\",\"firstPage\":1,\"lastPage\":3}],"
                        + "\"next\":[" + layoutStep(1, 3, batch.fileId()) + ",{\"stage\":\"passages\",\"firstPage\":1,"
                        + "\"lastPage\":3,\"input\":\"texts\"}]}"));
        layoutReport(chain, claimOffered(chain), 1, 3, List.of(), List.of(1, 2, 3));
        JobClaims.Claim passages = claimOffered(chain);
        claims.report(passages.jobId(), passages.claimId(), passages.grant(), "done", null, null,
                JSON.readTree(passagesReport("Talentos são habilidades. Pontos de Vida (PV): medem a saúde", "Regras",
                        "Talentos").replace("\"pipelineVersion\":\"4\"", "\"pipelineVersion\":\"5\"")
                        .replace("\"next\":[]", "\"next\":[{\"stage\":\"records\",\"firstPage\":1,\"lastPage\":3,"
                                + "\"input\":\"passages\"}]")));
        UUID page3 = passageOn(chain, 3);
        UUID job = jdbc.sql("select s.job_id from ingestion_step s join job j on j.id = s.job_id"
                + " where s.version_id = ? and j.state = 'offered'").param(chain.versionId()).query(UUID.class).single();
        JobClaims.Claim records = claims.claim(job, null, List.of("azure-openai"));
        claims.report(job, records.claimId(), records.grant(), "done", null, null, JSON.readTree(
                "{\"pipelineVersion\":\"5\",\"records\":[],\"proposals\":[" + String.join(",",
                        pgProposal("record_type", "Talento", "", page3, "Talentos são"),
                        pgProposal("record_type", "Habilidade", "", page3, "habilidades"),
                        pgProposal("term", "Pontos de Vida", "Pontos de Vida (PV): medem a saúde", page3, "PV"),
                        pgProposal("term", "PV", "Pontos de Vida (PV)", page3, "(PV)")) + "],\"next\":[]}"));
        assertThat(state(chain.versionId())).isEqualTo("needs_review");

        // Encrypted on PostgreSQL: name, definition and examples hold vault ciphertexts, bound to the proposal.
        List<Map<String, Object>> rows = jdbc.sql("select id, owner_id, system_id, material_id, version_id, kind, name,"
                + " definition, examples, state from proposal where material_id = ? order by id")
                .param(chain.materialId()).query().listOfRows();
        assertThat(rows).hasSize(4).allSatisfy(row -> assertThat(row).containsEntry("owner_id", owner)
                .containsEntry("system_id", system).containsEntry("version_id", chain.versionId())
                .containsEntry("state", "pending"));
        for (Map<String, Object> row : rows) {
            for (String column : List.of("name", "definition", "examples")) {
                byte[] stored = (byte[]) row.get(column);
                if (stored != null) {
                    assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("Talento")
                            .doesNotContain("Pontos").doesNotContain("PV").doesNotContain("habilidades");
                }
            }
        }
        UUID talent = rows.getFirst().get("id") instanceof UUID id ? id : null;
        assertThat(vault.decryptText(new Binding("proposal", "name", talent, owner), (byte[]) rows.getFirst()
                .get("name"))).isEqualTo("Talento");
        // The table refuses a second proposal of the same name for the same system and material.
        assertThatThrownBy(() -> jdbc.sql("insert into proposal (id, owner_id, system_id, material_id, kind, name,"
                + " name_fingerprint, examples, state, created_at) select ?, owner_id, system_id, material_id, kind,"
                + " name, name_fingerprint, examples, 'pending', now() from proposal where id = ?")
                .params(Ids.newId(), talent).update()).isInstanceOf(DataIntegrityViolationException.class);

        // Approved: a record type with its key and origin, a term; one name space for both.
        UUID skill = (UUID) rows.get(1).get("id");
        UUID hp = (UUID) rows.get(2).get("id");
        UUID pv = (UUID) rows.get(3).get("id");
        UUID type = as(owner, () -> proposals.approve(talent, 0, "Feat")).orElseThrow().resultId();
        assertThat(jdbc.sql("select key, proposal_id, core from record_type where id = ?").param(type).query()
                .singleRow()).containsEntry("key", "feat").containsEntry("proposal_id", talent)
                .containsEntry("core", null);
        UUID term = as(owner, () -> proposals.approve(hp, 0, null)).orElseThrow().resultId();
        Map<String, Object> termRow = jdbc.sql("select name, definition, proposal_id from term where id = ?").param(term)
                .query().singleRow();
        assertThat(termRow).containsEntry("proposal_id", hp);
        assertThat(new String((byte[]) termRow.get("name"), StandardCharsets.ISO_8859_1)
                + new String((byte[]) termRow.get("definition"), StandardCharsets.ISO_8859_1))
                .doesNotContain("Pontos").doesNotContain("saúde");
        for (String taken : List.of("FEAT", "pontos de vida", "Spell", "Magia")) {
            assertThat(messages(org.assertj.core.api.Assertions.catchThrowable(() -> as(owner,
                    () -> proposals.approve(skill, 0, taken))))).as(taken).contains("ontology.name_taken");
        }
        as(owner, () -> proposals.merge(pv, 0, term));
        assertThat(jdbc.sql("select count(*) from term_alias where term_id = ? and proposal_id = ?").params(term, pv)
                .query(Long.class).single()).isOne();
        assertThat(as(owner, () -> terms.terms(system, 0, 50)).orElseThrow().items().getFirst().aliases())
                .containsExactly("PV");
        // Another owner reads and decides nothing.
        UUID other = Ids.newId();
        assertThat(as(other, () -> proposals.ofSystem(system, 0, 50))).isEmpty();
        assertThat(as(other, () -> proposals.reject(skill, 0))).isEmpty();

        // A discard: the pending proposal goes, the decided ones stay without their version.
        as(owner, () -> actions.act(chain.materialId(), "discard"));
        assertThat(jdbc.sql("select count(*) from proposal where id = ?").param(skill).query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from proposal where material_id = ? and version_id is null")
                .param(chain.materialId()).query(Long.class).single()).isEqualTo(3);

        // The material's deletion: only the approved ones stay, without it; the alias stays, without its proposal.
        freeTheIngestionLane();
        assertThat(as(owner, () -> systemDeletion.deleteMaterial(chain.materialId()))).isTrue();
        UUID deletionId = jdbc.sql("select id from deletion where target_id = ?").param(chain.materialId())
                .query(UUID.class).single();
        assertThat(systemDeletion.run(owner, deletionId)).isTrue();
        assertThat(jdbc.sql("select count(*) from material where id = ?").param(chain.materialId()).query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("select id from proposal where system_id = ? and material_id is null order by id")
                .param(system).query(UUID.class).list()).containsExactly(talent, hp);
        assertThat(jdbc.sql("select proposal_id from term_alias where term_id = ?").param(term).query()
                .singleRow()).containsEntry("proposal_id", null);
        assertThat(as(owner, () -> ontology.recordType(type)).orElseThrow().origin())
                .isEqualTo(new Ontology.Origin(talent, null, null));
    }

    private static String pgProposal(String kind, String name, String definition, UUID passage, String quote) {
        return "{\"kind\":\"" + kind + "\",\"name\":\"" + name + "\",\"definition\":\"" + definition
                + "\",\"examples\":[{\"passageId\":\"" + passage + "\",\"quote\":\"" + quote + "\"}]}";
    }

    // --- Story 4.3: a system's records on Postgres

    @Autowired
    private Records systemRecords;

    @Test
    void aSystemsRecordsAreListedByNameFromItsReadyVersionsOnlyAndOneOpensWithItsReferencesGroupedOnPostgres()
            throws Exception {
        UUID owner = Ids.newId();
        write(owner, () -> library.createStarter(Language.EN));
        UUID system = jdbc.sql("select id from system where owner_id = ?").param(owner).query(UUID.class).single();
        UUID campaign = jdbc.sql("select id from campaign where owner_id = ?").param(owner).query(UUID.class).single();
        Uploads.Details toSystem = new Uploads.Details(null, null, null, List.of(system.toString()));

        // A material of the system, with two more passages on page 2 under one section path.
        Chain book = reviewed(completed(owner, pdf(MIB), toSystem), 4, List.of());
        UUID p1 = passageOn(book, 1);
        UUID p3 = passageOn(book, 3);
        UUID twoA = Ids.newId();
        UUID twoB = Ids.newId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        write(owner, () -> store.insertPassages(List.of(passageRow(book, twoA, 2, 2, now),
                passageRow(book, twoB, 3, 2, now))));
        write(owner, () -> extracted.replaceRange(book.versionId(), 1, 4, List.of(
                new ExtractedRecords.Extracted("spell", "bola de fogo", List.of(new ExtractedRecords.Field("Nível",
                        "3"), new ExtractedRecords.Field("Alcance", "45 m")), List.of(p3, twoA, p1, twoB), 1),
                new ExtractedRecords.Extracted("spell", "Ácido", List.of(), List.of(p1), 1),
                new ExtractedRecords.Extracted("spell", "Bola de Fogo", List.of(), List.of(p3), 3),
                new ExtractedRecords.Extracted("item", "Corda", List.of(), List.of(p1), 1))));
        accepted(book);
        // A material of the system's campaign: the type filter spans both versions.
        Chain campaignBook = reviewed(completed(owner, pdf(MIB), new Uploads.Details(null, null, campaign.toString(),
                (List<String>) null)), 4, List.of());
        write(owner, () -> extracted.replaceRange(campaignBook.versionId(), 1, 4, List.of(
                new ExtractedRecords.Extracted("spell", "Zarabatana", List.of(), List.of(passageOn(campaignBook, 3)),
                        3),
                new ExtractedRecords.Extracted("spell", "abelha", List.of(), List.of(passageOn(campaignBook, 1)),
                        1))));
        accepted(campaignBook);
        // A material accepted, then replaced by a version accepted too: the first is superseded.
        Chain old = reviewed(completed(owner, pdf(MIB), toSystem), 4, List.of());
        write(owner, () -> extracted.replaceRange(old.versionId(), 1, 4, List.of(new ExtractedRecords.Extracted(
                "spell", "Antiga", List.of(), List.of(passageOn(old, 1)), 1))));
        accepted(old);
        Chain renewed = reviewed(completed(owner, pdf(MIB), new Uploads.Details(null, null, null, null,
                old.materialId().toString())), 4, List.of());
        write(owner, () -> extracted.replaceRange(renewed.versionId(), 1, 4, List.of(new ExtractedRecords.Extracted(
                "spell", "Renovada", List.of(), List.of(passageOn(renewed, 1)), 1))));
        accepted(renewed);
        assertThat(state(old.versionId())).isEqualTo("superseded");
        // A version that still needs review.
        Chain pending = reviewed(completed(owner, pdf(MIB), toSystem), 4, List.of());
        write(owner, () -> extracted.replaceRange(pending.versionId(), 1, 4, List.of(new ExtractedRecords.Extracted(
                "spell", "Pendente", List.of(), List.of(passageOn(pending, 1)), 1))));
        UUID spell = as(owner, () -> ontology.recordTypes(system, 0, 8)).orElseThrow().items().stream()
                .filter(type -> "spell".equals(type.core())).findFirst().orElseThrow().id();

        Records.SummaryPage all = as(owner, () -> systemRecords.records(spell, 0, 50)).orElseThrow();

        assertThat(all.total()).isEqualTo(6);
        assertThat(all.items()).extracting(Records.Summary::name).containsExactly("abelha", "Ácido",
                all.items().get(2).name(), all.items().get(3).name(), "Renovada", "Zarabatana");
        assertThat(all.items().subList(2, 4)).extracting(Records.Summary::name)
                .containsExactlyInAnyOrder("bola de fogo", "Bola de Fogo");
        assertThat(all.items().subList(2, 4)).extracting(Records.Summary::id).isSorted();
        assertThat(all.items()).allSatisfy(item -> assertThat(item.materialName()).isEqualTo("Livro.pdf"));
        assertThat(all.items()).extracting(Records.Summary::page).containsExactly(1, 1,
                all.items().get(2).page(), all.items().get(3).page(), 1, 3);
        Records.SummaryPage paged = as(owner, () -> systemRecords.records(spell, 2, 2)).orElseThrow();
        assertThat(paged.total()).isEqualTo(6);
        assertThat(paged.items()).isEqualTo(all.items().subList(2, 4));
        assertThat(as(owner, () -> systemRecords.records(spell, 5, 2)).orElseThrow().items())
                .extracting(Records.Summary::name).containsExactly("Zarabatana");
        // Another owner reads none of it.
        assertThat(as(Ids.newId(), () -> systemRecords.records(spell, 0, 50))).isEmpty();

        // One record: its fields in stored order, one reference per (version, section path, page), in page order.
        UUID bola = jdbc.sql("select id from record where version_id = ? and ordinal = 0").param(book.versionId())
                .query(UUID.class).single();
        Records.Detail detail = as(owner, () -> systemRecords.record(system, bola)).orElseThrow();
        assertThat(detail.name()).isEqualTo("bola de fogo");
        assertThat(detail.recordType().id()).isEqualTo(spell);
        assertThat(detail.recordType().core()).isEqualTo("spell");
        assertThat(detail.fields()).extracting(Records.Field::name, Records.Field::value)
                .containsExactly(tuple("Nível", "3"), tuple("Alcance", "45 m"));
        assertThat(detail.references()).extracting(Records.Reference::page, Records.Reference::sectionPath)
                .containsExactly(tuple(1, List.of("Capítulo")), tuple(2, List.of("A")),
                        tuple(3, List.of("Capítulo", "Seção")));
        // Each carries the record's passages at its place: both of page 2's under one reference, ordered by id.
        assertThat(detail.references()).extracting(Records.Reference::passageIds).containsExactly(List.of(p1),
                Stream.of(twoA, twoB).sorted().toList(), List.of(p3));
        assertThat(detail.references()).allSatisfy(reference -> {
            assertThat(reference.versionId()).isEqualTo(book.versionId());
            assertThat(reference.materialName()).isEqualTo("Livro.pdf");
        });
        // Its version's pages, every one of them, with their pictures.
        assertThat(as(owner, () -> systemRecords.page(system, bola, 4)).orElseThrow().pageTotal()).isEqualTo(4);
        assertThat(as(owner, () -> systemRecords.picture(system, bola, 2))).isPresent();
        assertThat(as(owner, () -> systemRecords.page(system, bola, 5))).isEmpty();

        // Hidden: a superseded version's record, a pending one's, another system's view, another owner.
        UUID antiga = jdbc.sql("select id from record where version_id = ?").param(old.versionId()).query(UUID.class)
                .single();
        UUID pendente = jdbc.sql("select id from record where version_id = ?").param(pending.versionId())
                .query(UUID.class).single();
        UUID otherSystem = as(owner, () -> library.createSystem(new Library.SystemFields("Outro", null, null))).id();
        assertThat(as(owner, () -> systemRecords.record(system, antiga))).isEmpty();
        assertThat(as(owner, () -> systemRecords.page(system, antiga, 1))).isEmpty();
        assertThat(as(owner, () -> systemRecords.record(system, pendente))).isEmpty();
        assertThat(as(owner, () -> systemRecords.record(otherSystem, bola))).isEmpty();
        assertThat(as(Ids.newId(), () -> systemRecords.record(system, bola))).isEmpty();
        assertThat(as(owner, () -> systemRecords.record(system, Ids.newId()))).isEmpty();
        freeTheIngestionLane();
    }

    // --- Story 4.3 part B: records in a campaign's search on Postgres

    @Autowired
    private Scopes scopes;
    @Autowired
    private CampaignScopes campaignScopes;
    @Autowired
    private Searches searches;
    @Autowired
    private SearchStore searchStore;

    @Test
    void aRecordsNameFingerprintFindsItsExactNameWithinTheScopeAndASearchStoresAndReadsRecordResultsOnPostgres()
            throws Exception {
        UUID owner = Ids.newId();
        write(owner, () -> library.createStarter(Language.EN));
        UUID system = jdbc.sql("select id from system where owner_id = ?").param(owner).query(UUID.class).single();
        UUID campaign = jdbc.sql("select id from campaign where owner_id = ?").param(owner).query(UUID.class).single();
        Uploads.Details toSystem = new Uploads.Details(null, null, null, List.of(system.toString()));
        Chain book = reviewed(completed(owner, pdf(MIB), toSystem), 4, List.of());
        write(owner, () -> extracted.replaceRange(book.versionId(), 1, 4, List.of(
                new ExtractedRecords.Extracted("item", "Vorpal Sword", List.of(), List.of(passageOn(book, 3)), 3),
                new ExtractedRecords.Extracted("spell", "  VORPAL sword", List.of(), List.of(passageOn(book, 1)), 1),
                new ExtractedRecords.Extracted("item", "Espada Curta", List.of(), List.of(passageOn(book, 1)), 1))));
        accepted(book);
        Chain off = reviewed(completed(owner, pdf(MIB), toSystem), 4, List.of());
        write(owner, () -> extracted.replaceRange(off.versionId(), 1, 4, List.of(new ExtractedRecords.Extracted(
                "item", "Vorpal Sword", List.of(), List.of(passageOn(off, 1)), 1))));
        accepted(off);
        Chain review = reviewed(completed(owner, pdf(MIB), toSystem), 4, List.of());
        write(owner, () -> extracted.replaceRange(review.versionId(), 1, 4, List.of(new ExtractedRecords.Extracted(
                "item", "Vorpal Sword", List.of(), List.of(passageOn(review, 1)), 1))));
        UUID item = jdbc.sql("select id from record where version_id = ? and ordinal = 0").param(book.versionId())
                .query(UUID.class).single();
        UUID spell = jdbc.sql("select id from record where version_id = ? and ordinal = 1").param(book.versionId())
                .query(UUID.class).single();
        UUID sword = jdbc.sql("select id from record where version_id = ? and ordinal = 2").param(book.versionId())
                .query(UUID.class).single();

        // V37: the fingerprint is the vault's of the name's unique form, never the name, and is set on every record.
        byte[] stored = jdbc.sql("select name_fingerprint from record where id = ?").param(item).query(byte[].class)
                .single();
        assertThat(stored).isEqualTo(vault.fingerprint("record", "name", Library.uniqueForm("Vorpal Sword")))
                .isEqualTo(jdbc.sql("select name_fingerprint from record where id = ?").param(spell)
                        .query(byte[].class).single());
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContainIgnoringCase("vorpal");
        assertThat(jdbc.sql("select count(*) from record where name_fingerprint is null and owner_id = ?")
                .param(owner).query(Long.class).single()).isZero();

        // The exact name: case and surrounding space aside, of the scope's ready versions only, by type when asked.
        ScopeSettings withoutOff = new ScopeSettings(true, true, false, false, java.util.Set.of(off.materialId()));
        ResolvedScope scope = as(owner, () -> scopes.resolveSources(campaign, withoutOff));
        assertThat(as(owner, () -> scopes.namedRecords(scope, " vorpal SWORD ", null, List.of())))
                .extracting(ScopedRecord::recordId).containsExactlyInAnyOrder(item, spell);
        List<ScopedRecord> items = as(owner, () -> scopes.namedRecords(scope, "Vorpal Sword", "item", List.of()));
        assertThat(items).extracting(ScopedRecord::recordId).containsExactly(item);
        assertThat(items.getFirst().page()).isEqualTo(3);
        assertThat(items.getFirst().passageIds()).containsExactly(passageOn(book, 3));
        assertThat(items.getFirst().name()).isEqualTo("Vorpal Sword");
        assertThat(items.getFirst().tier()).isEqualTo(Tier.SYSTEM);
        // With the material switched on, its record joins; a version that needs review never does.
        ResolvedScope everything = as(owner, () -> scopes.resolveSources(campaign, ScopeSettings.STARTING));
        assertThat(as(owner, () -> scopes.namedRecords(everything, "Vorpal Sword", "item", List.of())))
                .extracting(ScopedRecord::versionId).containsExactlyInAnyOrder(book.versionId(), off.versionId());
        assertThat(as(owner, () -> scopes.records(everything, List.of(item, sword, Ids.newId()))))
                .extracting(ScopedRecord::recordId).containsExactly(item, sword);
        UUID reviewing = jdbc.sql("select id from record where version_id = ?").param(review.versionId())
                .query(UUID.class).single();
        assertThat(as(owner, () -> scopes.records(everything, List.of(reviewing)))).isEmpty();

        // A search with a record-type filter stores it; its record results are stored by id and read back named.
        UUID searchId = as(owner, () -> searches.create(campaign, "Vorpal Sword", null, null, "item"));
        assertThat(jdbc.sql("select record_type from search where id = ?").param(searchId).query(String.class)
                .single()).isEqualTo("item");
        write(owner, () -> {
            searchStore.insertResults(List.of(
                    new SearchResultRow(Ids.newId(), owner, searchId, 1, null, item, book.versionId(), "system"),
                    new SearchResultRow(Ids.newId(), owner, searchId, 2, null, sword, book.versionId(), "system")));
            searchStore.endSearch(searchId, "done", null);
        });
        Searches.Found found = as(owner, () -> searches.find(searchId)).orElseThrow();
        assertThat(found.recordType()).isEqualTo("item");
        assertThat(found.results()).extracting(Searches.Result::kind, Searches.Result::recordId,
                Searches.Result::name, Searches.Result::recordType)
                .containsExactly(tuple("record", item, "Vorpal Sword", "item"),
                        tuple("record", sword, "Espada Curta", "item"));
        // V37's checks: a result is a passage or a record, never both or neither, and a record once per search; a
        // record type is a core key.
        assertThatThrownBy(() -> jdbc.sql("insert into search_result (id, owner_id, search_id, rank, passage_id,"
                + " record_id, version_id, tier) values (?, ?, ?, 3, null, null, ?, 'system')")
                .params(Ids.newId(), owner, searchId, book.versionId()).update()).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.sql("insert into search_result (id, owner_id, search_id, rank, passage_id,"
                + " record_id, version_id, tier) values (?, ?, ?, 3, ?, ?, ?, 'system')")
                .params(Ids.newId(), owner, searchId, passageOn(book, 1), item, book.versionId()).update())
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.sql("insert into search_result (id, owner_id, search_id, rank, passage_id,"
                + " record_id, version_id, tier) values (?, ?, ?, 3, null, ?, ?, 'system')")
                .params(Ids.newId(), owner, searchId, item, book.versionId()).update())
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.sql("update search set record_type = 'Dragon' where id = ?").param(searchId)
                .update()).isInstanceOf(RuntimeException.class);

        // The record as the campaign shows it: in the scope, with the campaign's system's type; switched off, not.
        Records.Detail detail = as(owner, () -> systemRecords.inCampaign(campaign, item)).orElseThrow();
        assertThat(detail.name()).isEqualTo("Vorpal Sword");
        assertThat(detail.recordType().core()).isEqualTo("item");
        assertThat(detail.references()).extracting(Records.Reference::page).containsExactly(3);
        assertThat(as(owner, () -> systemRecords.inCampaign(campaign, reviewing))).isEmpty();
        assertThat(as(Ids.newId(), () -> systemRecords.inCampaign(campaign, item))).isEmpty();
        UUID offRecord = jdbc.sql("select id from record where version_id = ?").param(off.versionId())
                .query(UUID.class).single();
        assertThat(as(owner, () -> systemRecords.inCampaign(campaign, offRecord))).isPresent();
        as(owner, () -> campaignScopes.change(campaign, new CampaignScopes.Change(0, true, true, false, false,
                List.of(off.materialId())))).orElseThrow();
        assertThat(as(owner, () -> systemRecords.inCampaign(campaign, offRecord))).isEmpty();
        freeTheIngestionLane();
    }

    /** The chain's version accepted; its index changes would hold the ingestion lane. */
    private void accepted(Chain chain) throws Exception {
        as(chain.owner(), () -> actions.act(chain.materialId(), "accept"));
        assertThat(state(chain.versionId())).isEqualTo("ready");
        freeTheIngestionLane();
    }

    private UUID passageOn(Chain chain, int page) {
        return jdbc.sql("select id from passage where version_id = ? and page = ? order by ordinal limit 1")
                .params(chain.versionId(), page).query(UUID.class).single();
    }

    // --- helpers

    private Chain completed(byte[] content) throws Exception {
        return completed(Ids.newId(), content, Uploads.Details.NONE);
    }

    /** A completed PDF of {@code owner}, with these details (Story 3.4: a replacement names its material). */
    private Chain completed(UUID owner, byte[] content, Uploads.Details details) throws Exception {
        UUID id = as(owner, () -> uploads.create(content.length, "Livro.pdf", details)).uploadId();
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
