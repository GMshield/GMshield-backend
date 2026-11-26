package io.github.gmshield.materials.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.jobs.service.JobClaims;
import io.github.gmshield.library.service.Language;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.PageViewRow;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.VersionFileRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.FileStore;
import io.github.gmshield.vault.service.Vault;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 3.4 part B against a real PostgreSQL and Redis (test mode): V26, a material's deletion and a superseded
 * version's removal from the GM's delete to the end of the removal. A replaced material deleted leaves no row, file or
 * version, with one {@code index.removal} per version; a superseded version removed keeps the material and its ready
 * version, and an earlier reference to it reads removed and its page is not found; for the {@code material} kind, the
 * removal waits for a running claim and takes a replacement whose upload completed during the wait; a failed
 * {@code index.removal} gets a new job and its row moves to it. Story 4.2: a material's deletion and a system's
 * take the records of their versions with them (no reference cascades), even when an earlier pass already ran a
 * version's removal and left its records. Story 4.5: a version that later versions name as the one reprocessing read
 * again goes, and they forget it ({@code reprocessed_from} cleared), and the material then goes with all of them.
 *
 * <p>The raw rows are read here with SQL to prove what is stored; the application never reads a library table with
 * native SQL. The properties are {@code SystemDeletionIntegrationTest}'s, so both share one application context (and its
 * connections: the throwaway PostgreSQL allows 100).
 */
@SpringBootTest(properties = {"GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
    "gmshield.materials.upload-sweep.enabled=false",
    "gmshield.materials.version-removal-sweep.enabled=false",
    "gmshield.library.deletion-sweep.enabled=false",
    "gmshield.materials.index-retry-sweep.enabled=false",
    "gmshield.files.dir=${java.io.tmpdir}/gmshield-deletion-it"})
@ActiveProfiles("test-mode")
class MaterialDeletionIntegrationTest {

    private static final int KIB = 1024;
    /** Every state "Meus Arquivos" lists: all but a discarded material's. */
    private static final Set<String> LISTED = Set.of("uploading", "waiting", "processing", "needs_review", "ready",
            "superseded", "failed");

    @Autowired
    private Uploads uploads;
    @Autowired
    private Library library;
    @Autowired
    private Materials reads;
    @Autowired
    private MaterialDeletions materialDeletions;
    @Autowired
    private SystemDeletion deletion;
    @Autowired
    private ScopeReads scopeReads;
    @Autowired
    private IndexRemovals indexRemovals;
    @Autowired
    private IndexRemovalRetrySweep removalRetry;
    @Autowired
    private MaterialStore store;
    @Autowired
    private JobClaims claims;
    @Autowired
    private FileStore files;
    @Autowired
    private Vault vault;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private ExtractedRecords extracted;

    /** An owner with the starter system and campaign, as a first sign-in makes them. */
    private record Owner(UUID id, UUID campaign, UUID system) {
    }

    /** A material with a verified PDF: its version, its raw upload and the job of its first step. */
    private record Completed(UUID materialId, UUID versionId, UUID uploadId, UUID jobId) {
    }

    @BeforeEach
    void freeTheIngestionLane() {
        // One ingestion job at a time: whatever another test left offered or running would hold this test's jobs back.
        jdbc.sql("update job set state = 'cancelled', grant_hash = null, claim_renew_at = null, claim_lapses_at = null"
                + " where lane = 'ingestion' and state in ('waiting', 'offered', 'running')").update();
    }

    @Test
    void aDeletedReplacedMaterialLeavesNoRowFileOrVersionAndOneIndexRemovalPerVersionOnPostgres() throws Exception {
        Owner a = owner();
        Completed first = completed(a.id(), new Uploads.Details("rulebook", "pt-BR", a.campaign().toString(),
                (String) null));
        freeTheIngestionLane();
        List<UUID> firstPieces = versionFileAndPassage(a.id(), first.versionId());
        // Story 3.4: its first version superseded by a second, ready, and a replacement of it uploading.
        UUID second = Ids.newId();
        write(a.id(), () -> {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            store.changeVersionState(first.versionId(), "superseded", now);
            store.insertVersion(new VersionRow(second, a.id(), first.materialId(), "ready", "pdf", 10L, now, now, 1,
                    "5", null));
        });
        List<UUID> secondPieces = versionFileAndPassage(a.id(), second);
        UUID replacement = as(a.id(), () -> uploads.create(10, "novo.pdf",
                Uploads.Details.replacing(first.materialId().toString()))).uploadId();
        UUID third = jdbc.sql("select version_id from upload where id = ?").param(replacement).query(UUID.class)
                .single();
        // Another material of the campaign stays.
        UUID kept = materialWith(a.id(), a.campaign(), "ready");
        assertThat(as(a.id(), () -> scopeReads.readyOfCampaign(a.campaign()))).contains(second);
        assertThat(as(a.id(), () -> materialDeletions.count(first.materialId())).orElseThrow())
                .isEqualTo(new MaterialDeletions.Count(3, 1));
        assertThat(as(Ids.newId(), () -> materialDeletions.delete(first.materialId()))).isFalse();

        assertThat(as(a.id(), () -> materialDeletions.delete(first.materialId()))).isTrue();

        // From the commit: every version discarded, out of every scope, list and count.
        for (UUID version : List.of(first.versionId(), second, third)) {
            assertThat(state(version)).isEqualTo("discarded");
        }
        assertThat(as(a.id(), () -> scopeReads.readyOfCampaign(a.campaign()))).doesNotContain(second);
        assertThat(as(a.id(), () -> reads.list(LISTED, null, 50)).items()).extracting(Materials.Material::id)
                .doesNotContain(first.materialId()).contains(kept);
        Map<String, Object> row = jdbc.sql("select id, owner_id, kind, done_at from deletion where target_id = ?")
                .param(first.materialId()).query().singleRow();
        assertThat(row).containsEntry("owner_id", a.id()).containsEntry("kind", "material").containsEntry("done_at", null);
        UUID deletionId = (UUID) row.get("id");
        // Matrix row "Delete again": answered the same, and no second row.
        assertThat(as(a.id(), () -> materialDeletions.delete(first.materialId()))).isTrue();
        assertThat(count("deletion", "target_id = ?", first.materialId())).isOne();

        assertThat(deletion.run(a.id(), deletionId)).isTrue();

        assertThat(count("material", "id = ?", first.materialId())).isZero();
        assertThat(count("material_version", "material_id = ?", first.materialId())).isZero();
        for (UUID version : List.of(first.versionId(), second, third)) {
            for (String table : List.of("upload", "ingestion_step", "version_file", "passage", "page_view",
                    "page_layer", "page_outcome", "version_outline", "index_change")) {
                assertThat(count(table, "version_id = ?", version)).as(table).isZero();
            }
            assertThat(jdbc.sql("select j.kind from index_removal r join job j on j.id = r.job_id where r.version_id = ?")
                    .param(version).query(String.class).list()).as("one index removal").containsExactly("index.removal");
        }
        assertThat(firstPieces).allSatisfy(file -> assertThat(files.fileExists(a.id(), file)).isFalse());
        assertThat(secondPieces).allSatisfy(file -> assertThat(files.fileExists(a.id(), file)).isFalse());
        assertThat(files.fileExists(a.id(), first.uploadId())).isFalse();
        assertThat(count("material", "id = ?", kept)).isOne();
        assertThat(jdbc.sql("select done_at from deletion where id = ?").param(deletionId).query().singleRow()
                .get("done_at")).isNotNull();

        // Again: nothing, and no second index removal.
        assertThat(deletion.run(a.id(), deletionId)).isFalse();
        assertThat(as(a.id(), () -> materialDeletions.delete(first.materialId()))).isFalse();
        for (UUID version : List.of(first.versionId(), second, third)) {
            assertThat(count("index_removal", "version_id = ?", version)).isOne();
        }
    }

    @Test
    void aDeletedMaterialTakesTheRecordsOfItsVersionsBeforeTheirPassagesOnPostgres() throws Exception {
        Owner a = owner();
        UUID material = materialWith(a.id(), a.campaign(), null);
        UUID ready = versionWithPage(a.id(), material, "ready", Instant.now());
        versionFileAndPassage(a.id(), ready);
        withRecords(a.id(), ready);
        UUID kept = materialWith(a.id(), a.campaign(), null);
        UUID keptVersion = versionWithPage(a.id(), kept, "ready", Instant.now());
        versionFileAndPassage(a.id(), keptVersion);
        withRecords(a.id(), keptVersion);

        assertThat(as(a.id(), () -> materialDeletions.delete(material))).isTrue();
        // As if an earlier pass had run the version's removal and stopped: its records are still there, so only the
        // deletion's own removal of them lets its passages and its row go.
        write(a.id(), () -> store.clearRemovalPending(ready, Instant.now().truncatedTo(ChronoUnit.MICROS)));
        assertThat(count("record", "version_id = ?", ready)).isEqualTo(2);
        UUID deletionId = jdbc.sql("select id from deletion where target_id = ?").param(material).query(UUID.class)
                .single();

        assertThat(deletion.run(a.id(), deletionId)).isTrue();

        assertThat(count("material", "id = ?", material)).isZero();
        assertThat(count("material_version", "id = ?", ready)).isZero();
        assertThat(count("passage", "version_id = ?", ready)).isZero();
        assertThat(count("record", "version_id = ?", ready)).isZero();
        assertThat(count("record_passage", "owner_id = ? and record_id not in (select id from record)", a.id()))
                .isZero();
        assertThat(jdbc.sql("select done_at from deletion where id = ?").param(deletionId).query().singleRow()
                .get("done_at")).isNotNull();
        // Another material's records stay.
        assertThat(count("record", "version_id = ?", keptVersion)).isEqualTo(2);
        assertThat(count("record_passage", "record_id in (select id from record where version_id = ?)", keptVersion))
                .isEqualTo(2);
    }

    @Test
    void aDeletedSystemTakesTheRecordsOfItsMaterialsVersionsOnPostgres() throws Exception {
        Owner a = owner();
        UUID system = as(a.id(), () -> library.createSystem(new Library.SystemFields("Tormenta", "20", null))).id();
        UUID campaign = as(a.id(), () -> library.createCampaign(system, "Mesa de quinta")).orElseThrow().id();
        UUID material = materialWith(a.id(), campaign, null);
        UUID ready = versionWithPage(a.id(), material, "ready", Instant.now());
        versionFileAndPassage(a.id(), ready);
        withRecords(a.id(), ready);
        UUID starterMaterial = materialWith(a.id(), a.campaign(), null);
        UUID starterVersion = versionWithPage(a.id(), starterMaterial, "ready", Instant.now());
        versionFileAndPassage(a.id(), starterVersion);
        withRecords(a.id(), starterVersion);

        assertThat(as(a.id(), () -> deletion.delete(system))).isTrue();
        UUID deletionId = jdbc.sql("select id from deletion where target_id = ?").param(system).query(UUID.class)
                .single();

        assertThat(deletion.run(a.id(), deletionId)).isTrue();

        assertThat(count("system", "id = ?", system)).isZero();
        assertThat(count("material", "id = ?", material)).isZero();
        assertThat(count("material_version", "id = ?", ready)).isZero();
        assertThat(count("passage", "version_id = ?", ready)).isZero();
        assertThat(count("record", "version_id = ?", ready)).isZero();
        assertThat(count("record_passage", "owner_id = ? and record_id not in (select id from record)", a.id()))
                .isZero();
        // The starter system's material keeps its records.
        assertThat(count("record", "version_id = ?", starterVersion)).isEqualTo(2);
    }

    @Test
    void aRemovedSupersededVersionGoesAndTheMaterialAndItsReadyVersionStayOnPostgres() throws Exception {
        Owner a = owner();
        UUID material = materialWith(a.id(), a.campaign(), null);
        UUID oldest = versionWithPage(a.id(), material, "superseded", Instant.now().minusSeconds(120));
        UUID earlier = versionWithPage(a.id(), material, "superseded", Instant.now().minusSeconds(60));
        UUID ready = versionWithPage(a.id(), material, "ready", Instant.now());
        List<UUID> earlierPieces = versionFileAndPassage(a.id(), earlier);
        List<UUID> readyPieces = versionFileAndPassage(a.id(), ready);
        // A turn cited page 1 of the earlier version: it opens while that version is superseded.
        assertThat(as(a.id(), () -> scopeReads.versionPage(material, earlier, 1))).isPresent();
        // Newest first.
        assertThat(as(a.id(), () -> reads.find(material)).orElseThrow().superseded())
                .extracting(Materials.Superseded::id).containsExactly(earlier, oldest);

        assertThat(as(a.id(), () -> materialDeletions.removeVersion(material, earlier))).isTrue();

        assertThat(state(earlier)).isEqualTo("discarded");
        assertThat(as(a.id(), () -> reads.find(material)).orElseThrow().superseded())
                .extracting(Materials.Superseded::id).containsExactly(oldest);
        assertThat(as(a.id(), () -> scopeReads.removedVersions(List.of(oldest, earlier, ready))))
                .containsExactly(earlier);
        assertThat(as(a.id(), () -> scopeReads.versionPage(material, earlier, 1))).isEmpty();
        assertThat(as(a.id(), () -> scopeReads.readyOfCampaign(a.campaign()))).contains(ready);
        UUID deletionId = jdbc.sql("select id from deletion where target_id = ? and kind = 'version'").param(earlier)
                .query(UUID.class).single();
        // Again while it is recorded: answered the same.
        assertThat(as(a.id(), () -> materialDeletions.removeVersion(material, earlier))).isTrue();

        assertThat(deletion.run(a.id(), deletionId)).isTrue();

        assertThat(count("material", "id = ?", material)).isOne();
        assertThat(count("material_version", "id = ?", earlier)).isZero();
        assertThat(state(ready)).isEqualTo("ready");
        assertThat(state(oldest)).isEqualTo("superseded");
        for (String table : List.of("version_file", "passage", "page_view")) {
            assertThat(count(table, "version_id = ?", earlier)).as(table).isZero();
            assertThat(count(table, "version_id = ?", ready)).as(table).isPositive();
        }
        assertThat(count("index_removal", "version_id = ?", earlier)).isOne();
        assertThat(count("index_removal", "version_id = ?", ready)).isZero();
        assertThat(earlierPieces).allSatisfy(file -> assertThat(files.fileExists(a.id(), file)).isFalse());
        assertThat(readyPieces).allSatisfy(file -> assertThat(files.fileExists(a.id(), file)).isTrue());
        assertThat(as(a.id(), () -> scopeReads.removedVersions(List.of(earlier)))).containsExactly(earlier);
        assertThat(as(a.id(), () -> scopeReads.versionPage(material, earlier, 1))).isEmpty();
        assertThat(as(a.id(), () -> scopeReads.versionPage(material, ready, 1))).isPresent();
        // Gone: not found now.
        assertThat(as(a.id(), () -> materialDeletions.removeVersion(material, earlier))).isFalse();
    }

    @Test
    void aVersionReprocessingReadAgainGoesAndTheVersionsThatNameItForgetItThenTheMaterialGoesOnPostgres()
            throws Exception {
        Owner a = owner();
        UUID material = materialWith(a.id(), a.campaign(), null);
        UUID first = versionWithPage(a.id(), material, "superseded", Instant.now().minusSeconds(120));
        // Story 4.5: two reprocesses, each version naming the one it was read again from.
        UUID second = reprocessedVersion(a.id(), material, "superseded", first, Instant.now().minusSeconds(60));
        UUID third = reprocessedVersion(a.id(), material, "ready", second, Instant.now());
        assertThat(as(a.id(), () -> store.reprocessedFrom(second))).contains(first);

        assertThat(as(a.id(), () -> materialDeletions.removeVersion(material, first))).isTrue();
        UUID removal = jdbc.sql("select id from deletion where target_id = ? and kind = 'version'").param(first)
                .query(UUID.class).single();
        assertThat(deletion.run(a.id(), removal)).isTrue();

        assertThat(count("material_version", "id = ?", first)).isZero();
        assertThat(count("material_version", "id = ? and reprocessed_from is null", second)).isOne();
        assertThat(as(a.id(), () -> store.reprocessedFrom(second))).isEmpty();
        assertThat(as(a.id(), () -> store.reprocessedFrom(third))).contains(second);

        assertThat(as(a.id(), () -> materialDeletions.delete(material))).isTrue();
        UUID deletionId = jdbc.sql("select id from deletion where target_id = ?").param(material).query(UUID.class)
                .single();
        assertThat(deletion.run(a.id(), deletionId)).isTrue();

        assertThat(count("material", "id = ?", material)).isZero();
        assertThat(count("material_version", "material_id = ?", material)).isZero();
    }

    @Test
    void aMaterialsRemovalWaitsForARunningClaimAndTakesAReplacementCompletedDuringTheWaitOnPostgres()
            throws Exception {
        Owner b = owner();
        Completed running = completed(b.id(), new Uploads.Details("rulebook", "pt-BR", b.campaign().toString(),
                (String) null));
        JobClaims.Claim claim = claims.claim(running.jobId());
        // The version is ready while one of its steps still runs, so a replacement may be uploaded.
        write(b.id(), () -> store.changeVersionState(running.versionId(), "ready",
                Instant.now().truncatedTo(ChronoUnit.MICROS)));
        byte[] late = pdf(3 * KIB);
        UUID lateUpload = as(b.id(), () -> uploads.create(late.length, "late.pdf",
                Uploads.Details.replacing(running.materialId().toString()))).uploadId();
        as(b.id(), () -> uploads.patch(lateUpload, 0, late.length, sha256(late), new ByteArrayInputStream(late)));
        UUID lateVersion = jdbc.sql("select version_id from upload where id = ?").param(lateUpload).query(UUID.class)
                .single();

        assertThat(as(b.id(), () -> materialDeletions.delete(running.materialId()))).isTrue();

        UUID deletionId = jdbc.sql("select id from deletion where target_id = ?").param(running.materialId())
                .query(UUID.class).single();
        assertThat(notBefore(deletionId)).isEqualTo(claim.lapsesAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(jobState(running.jobId())).isEqualTo("cancelled");
        assertThat(state(running.versionId())).isEqualTo("discarded");
        assertThat(state(lateVersion)).isEqualTo("discarded");
        assertThat(deletion.run(b.id(), deletionId)).isFalse();
        assertThat(count("index_removal", "version_id = ?", running.versionId())).isZero();
        assertThat(count("material", "id = ?", running.materialId())).isOne();

        // Matrix row "Upload during wait": the replacement completes and starts (it does not know of the deletion).
        as(b.id(), () -> uploads.complete(lateUpload, sha256(late)));
        assertThat(state(lateVersion)).isEqualTo("waiting");
        UUID lateJob = jdbc.sql("select job_id from ingestion_step where version_id = ? and ended_at is null")
                .param(lateVersion).query(UUID.class).single();
        JobClaims.Claim lateClaim = claims.claim(lateJob);
        jdbc.sql("update deletion set not_before = ? where id = ?").params(OffsetDateTime.now().minusSeconds(1),
                deletionId).update();
        // The pass prepares again: the replacement is discarded again, its step cancelled, and the pass waits.
        assertThat(deletion.run(b.id(), deletionId)).isFalse();
        assertThat(notBefore(deletionId)).isEqualTo(lateClaim.lapsesAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(jobState(lateJob)).isEqualTo("cancelled");
        assertThat(state(lateVersion)).isEqualTo("discarded");
        assertThat(count("index_removal", "version_id = ?", lateVersion)).isZero();
        jdbc.sql("update deletion set not_before = ? where id = ?").params(OffsetDateTime.now().minusSeconds(1),
                deletionId).update();

        assertThat(deletion.run(b.id(), deletionId)).isTrue();

        assertThat(count("material", "id = ?", running.materialId())).isZero();
        for (UUID version : List.of(running.versionId(), lateVersion)) {
            assertThat(count("material_version", "id = ?", version)).isZero();
            assertThat(count("upload", "version_id = ?", version)).isZero();
            assertThat(count("ingestion_step", "version_id = ?", version)).isZero();
            assertThat(count("index_removal", "version_id = ?", version)).as("one index removal").isOne();
        }
        assertThat(files.fileExists(b.id(), running.uploadId())).isFalse();
        assertThat(files.fileExists(b.id(), lateUpload)).isFalse();
        assertThat(jdbc.sql("select done_at from deletion where id = ?").param(deletionId).query().singleRow()
                .get("done_at")).isNotNull();
        // The campaign and its system stay.
        assertThat(count("campaign", "id = ?", b.campaign())).isOne();
    }

    @Test
    void aFailedIndexRemovalGetsANewJobWithTheSameInputAndItsRowMovesOnPostgres() throws Exception {
        Owner a = owner();
        // The version is gone already: the removal outlives it, and the retry never needs it.
        UUID version = Ids.newId();
        UUID failed = as(a.id(), () -> new TransactionTemplate(transactions)
                .execute(status -> indexRemovals.start(version)));
        jdbc.sql("update job set state = 'failed', outcome_code = 'job.attempts_exhausted' where id = ?").param(failed)
                .update();

        // Matrix row "Failed removal".
        assertThat(removalRetry.sweep()).isPositive();

        UUID moved = jdbc.sql("select job_id from index_removal where version_id = ?").param(version).query(UUID.class)
                .single();
        assertThat(moved).isNotEqualTo(failed);
        assertThat(jdbc.sql("select owner_id from index_removal where job_id = ?").param(moved).query(UUID.class)
                .single()).isEqualTo(a.id());
        assertThat(jdbc.sql("select kind from job where id = ?").param(moved).query(String.class).single())
                .isEqualTo("index.removal");
        assertThat(jobState(moved)).isIn("waiting", "offered");
        assertThat(jobState(failed)).isEqualTo("failed");
        assertThat(count("index_removal", "job_id = ?", failed)).isZero();
        tools.jackson.databind.JsonNode input = as(a.id(), () -> new TransactionTemplate(transactions)
                .execute(status -> indexRemovals.input(new io.github.gmshield.jobs.service.JobRef(moved, a.id(),
                        IndexRemovals.KIND, io.github.gmshield.jobs.service.Lane.INGESTION, 1, null))));
        assertThat(input.path("source").asString()).isEqualTo(version.toString());
        assertThat(input.path("owner").asString()).isEqualTo(a.id().toString());

        // The failed job, kept as history, has no row: it is never started twice.
        removalRetry.sweep();
        assertThat(count("index_removal", "version_id = ?", version)).isOne();
    }

    // --- helpers

    private Owner owner() throws Exception {
        UUID id = Ids.newId();
        return as(id, () -> {
            new TransactionTemplate(transactions).executeWithoutResult(status -> library.createStarter(Language.PT_BR));
            Library.Campaign campaign = library.currentCampaign().orElseThrow();
            return new Owner(id, campaign.id(), campaign.system().id());
        });
    }

    /** A verified PDF with its details: its version is waiting, its first step offered. */
    private Completed completed(UUID owner, Uploads.Details details) throws Exception {
        byte[] content = pdf(5 * KIB);
        UUID id = as(owner, () -> uploads.create(content.length, "Livro.pdf", details)).uploadId();
        as(owner, () -> uploads.patch(id, 0, content.length, sha256(content), new ByteArrayInputStream(content)));
        Uploads.Completed done = as(owner, () -> uploads.complete(id, sha256(content)));
        UUID jobId = jdbc.sql("select job_id from ingestion_step where version_id = ?").param(done.versionId())
                .query(UUID.class).single();
        return new Completed(done.materialId(), done.versionId(), id, jobId);
    }

    /** A material of the campaign, with one version in {@code state} unless it is null: the material's id. */
    private UUID materialWith(UUID owner, UUID campaign, String state) throws Exception {
        UUID material = Ids.newId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        write(owner, () -> {
            store.insertMaterial(new MaterialRow(material, owner, vault.encryptText(new Binding(Uploads.MATERIAL,
                    Uploads.FIELD_NAME, material, owner), "Livro"), now, "rulebook", null, campaign));
            if (state != null) {
                store.insertVersion(new VersionRow(Ids.newId(), owner, material, state, "pdf", 10L, now, now, 1, "5",
                        null));
            }
        });
        return material;
    }

    /** A version of the material in {@code state}, created at {@code at}, with one text page: its id. */
    private UUID versionWithPage(UUID owner, UUID material, String state, Instant at) throws Exception {
        UUID version = Ids.newId();
        Instant created = at.truncatedTo(ChronoUnit.MICROS);
        write(owner, () -> {
            store.insertVersion(new VersionRow(version, owner, material, state, "pdf", 10L, created, created, 1, "5",
                    null));
            UUID view = Ids.newId();
            store.insertPageView(new PageViewRow(view, owner, version, 1, null, null, null,
                    vault.encrypt(PageViews.content(view, owner), PageViews.contentJson(new PageViews.Content(
                            List.of(), "Texto"))), created));
        });
        return version;
    }

    /** A version of the material that reprocessing read again from {@code from} (Story 4.5), with one text page. */
    private UUID reprocessedVersion(UUID owner, UUID material, String state, UUID from, Instant at) throws Exception {
        UUID version = Ids.newId();
        Instant created = at.truncatedTo(ChronoUnit.MICROS);
        write(owner, () -> {
            store.insertVersion(new VersionRow(version, owner, material, state, "pdf", 10L, created, created, 1, "5",
                    null), from);
            UUID view = Ids.newId();
            store.insertPageView(new PageViewRow(view, owner, version, 1, null, null, null,
                    vault.encrypt(PageViews.content(view, owner), PageViews.contentJson(new PageViews.Content(
                            List.of(), "Texto"))), created));
        });
        return version;
    }

    /** A stored file of the version and a passage of it, as ingestion records them: the file's id. */
    private List<UUID> versionFileAndPassage(UUID owner, UUID versionId) throws Exception {
        UUID fileId = Ids.newId();
        files.writePiece(owner, fileId, 0, pdf(2 * KIB));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID passage = Ids.newId();
        write(owner, () -> {
            store.insertVersionFile(new VersionFileRow(Ids.newId(), owner, versionId, fileId, VersionFileRow.ORIGINAL,
                    1, 3, 2 * KIB, 1, now));
            store.insertPassages(List.of(new PassageRow(passage, owner, versionId, 0, 1,
                    vault.encryptText(Passages.text(passage, owner), "Texto"),
                    vault.encrypt(Passages.place(passage, owner), Passages.placeJson(new Passages.Place(List.of("A"),
                            null, List.of(0, 5)))), now)));
        });
        return List.of(fileId);
    }

    /** Two records of the version (Story 4.2), a spell and an item, from its first passage, as ingestion stores. */
    private void withRecords(UUID owner, UUID versionId) throws Exception {
        UUID passage = jdbc.sql("select id from passage where version_id = ? order by ordinal limit 1").param(versionId)
                .query(UUID.class).single();
        write(owner, () -> extracted.replaceRange(versionId, 1, 1, List.of(
                new ExtractedRecords.Extracted("spell", "Bola de Fogo",
                        List.of(new ExtractedRecords.Field("Nível", "3")), List.of(passage), 1),
                new ExtractedRecords.Extracted("item", "Corda", List.of(), List.of(passage), 1))));
        assertThat(count("record", "version_id = ?", versionId)).isEqualTo(2);
    }

    private String state(UUID version) {
        return jdbc.sql("select state from material_version where id = ?").param(version).query(String.class).single();
    }

    private Instant notBefore(UUID deletionId) {
        return jdbc.sql("select not_before from deletion where id = ?").param(deletionId).query(OffsetDateTime.class)
                .single().toInstant();
    }

    private String jobState(UUID job) {
        return jdbc.sql("select state from job where id = ?").param(job).query(String.class).single();
    }

    private long count(String table, String where, Object... params) {
        return jdbc.sql("select count(*) from " + table + " where " + where).params(params).query(Long.class).single();
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
