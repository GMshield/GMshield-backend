package io.github.gmshield.materials.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.jobs.service.JobClaims;
import io.github.gmshield.jobs.service.JobFiles;
import io.github.gmshield.jobs.service.JobSweep;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.StepRow;
import io.github.gmshield.materials.data.VersionFileRow;
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
 * Story 1.15 against a real PostgreSQL and Redis (test mode): V8 and V9 and their checks, the chain from a completed
 * PDF through the claim, the input, the outputs and the report into {@code version_file}, the raw upload's removal,
 * a password's failure and the sweep of an output nobody adopted, and the cross-owner test of {@code ingestion_step}
 * and {@code version_file}.
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
        List<String> messages = new java.util.ArrayList<>();
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
