package io.github.gmshield.materials.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.conversations.service.Conversations;
import io.github.gmshield.library.service.Language;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.library.service.SystemDeletionHandler;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.VersionFileRow;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.FileStore;
import io.github.gmshield.vault.service.Vault;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
 * Story 3.2 part B against a real PostgreSQL and Redis (test mode): V24, a campaign's deletion from the GM's delete to
 * the end of its removal. Every row, file and index removal of what goes (the campaign, its conversation with turns,
 * event batches and references, its materials with their uploads, steps, files and passages), one
 * {@code index.removal} per version and never a second, its system, the system's materials and the sibling campaign
 * untouched, the name free at once and the current campaign fallen back, a pass resumed after another stopped between
 * two handlers, the removal waiting for a running claim and taking an upload that completed during the wait, an
 * archived campaign leaving the archived list at once and the last campaign leaving no current one, and a system
 * deleted while one of its campaigns is deleting (both end done).
 *
 * <p>The raw rows are read here with SQL to prove what is stored; the application never reads a library table with
 * native SQL.
 */
@SpringBootTest(properties = {"GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
    "gmshield.materials.upload-sweep.enabled=false",
    "gmshield.materials.version-removal-sweep.enabled=false",
    "gmshield.library.deletion-sweep.enabled=false",
    "gmshield.materials.index-retry-sweep.enabled=false",
    "gmshield.files.dir=${java.io.tmpdir}/gmshield-campaign-deletion-it"})
@ActiveProfiles("test-mode")
class CampaignDeletionIntegrationTest {

    private static final int KIB = 1024;

    @Autowired
    private Uploads uploads;
    @Autowired
    private Library library;
    @Autowired
    private SystemDeletion deletion;
    @Autowired
    private List<SystemDeletionHandler> handlers;
    @Autowired
    private Conversations conversations;
    @Autowired
    private io.github.gmshield.conversations.service.Searches searches;
    @Autowired
    private MaterialStore store;
    @Autowired
    private io.github.gmshield.jobs.service.JobClaims claims;
    @Autowired
    private FileStore files;
    @Autowired
    private Vault vault;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactions;

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
    void aDeletedCampaignLeavesNoRowOrFileOfWhatGoesAndTouchesNothingElseOnPostgres() throws Exception {
        Owner a = owner();
        UUID target = campaign(a.id(), a.system(), "Mesa de quinta");
        UUID sibling = campaign(a.id(), a.system(), "Mesa de sexta");
        // The campaign to delete is the current one.
        assertThat(as(a.id(), () -> library.chooseCampaign(target))).isPresent();
        UUID turn = as(a.id(), () -> conversations.ask(target, "Quanto dano?")).turnId();
        UUID again = as(a.id(), () -> conversations.ask(target, "E o alcance?")).turnId();
        eventsAndReference(a.id(), turn);
        UUID siblingTurn = as(a.id(), () -> conversations.ask(sibling, "Quem age primeiro?")).turnId();
        // Story 3.9: a search of each campaign.
        UUID search = as(a.id(), () -> searches.create(target, "Quanto dano", null, null));
        UUID siblingSearch = as(a.id(), () -> searches.create(sibling, "Quem age", null, null));
        Completed ofCampaign = completed(a.id(), new Uploads.Details("rulebook", "pt-BR", target.toString(), (String) null));
        List<UUID> pieces = versionFileAndPassage(a.id(), ofCampaign.versionId());
        UUID open = uploads(a.id(), new Uploads.Details("supplement", "pt-BR", target.toString(), (String) null));
        UUID openMaterial = materialOf(a.id(), open);
        Completed ofSibling = completed(a.id(), new Uploads.Details("rulebook", "pt-BR", sibling.toString(), (String) null));
        List<UUID> siblingPieces = versionFileAndPassage(a.id(), ofSibling.versionId());
        UUID ofSystem = materialOf(a.id(), as(a.id(), () -> uploads.create(10, "system.pdf",
                new Uploads.Details("other", "pt-BR", null, a.system().toString()))).uploadId());
        List<UUID> goingVersions = List.of(ofCampaign.versionId(), versionOf(openMaterial));

        SystemDeletion.CampaignCounts counts = as(a.id(), () -> deletion.previewCampaign(target)).orElseThrow();
        assertThat(counts).isEqualTo(new SystemDeletion.CampaignCounts(1, 2));
        assertThat(as(a.id(), () -> deletion.preview(a.system())).orElseThrow().campaigns()).isEqualTo(3);
        assertThat(as(Ids.newId(), () -> deletion.previewCampaign(target))).isEmpty();
        assertThat(as(Ids.newId(), () -> deletion.deleteCampaign(target))).isFalse();

        assertThat(as(a.id(), () -> deletion.deleteCampaign(target))).isTrue();

        // From the commit: deleting, not found, its versions discarded and their jobs and the answers' jobs cancelled.
        assertThat(jdbc.sql("select state from campaign where id = ?").param(target).query(String.class).single())
                .isEqualTo("deleting");
        assertThat(as(a.id(), () -> library.campaign(target))).isEmpty();
        assertThat(as(a.id(), () -> library.chooseCampaign(target))).isEmpty();
        assertThat(as(a.id(), () -> library.changeCampaign(target, 0, "Outro", null))).isEmpty();
        assertThat(as(a.id(), () -> library.campaignIdsOf(a.system())).orElseThrow())
                .containsExactlyInAnyOrder(sibling, a.campaign());
        assertThat(as(a.id(), () -> library.campaigns(null, false, null, 50)).orElseThrow().items())
                .extracting(Library.Campaign::id).doesNotContain(target);
        assertThat(as(a.id(), () -> library.campaigns(a.system(), true, null, 50)).orElseThrow().items()).isEmpty();
        assertThat(as(a.id(), () -> library.currentCampaign()).orElseThrow().id()).isEqualTo(sibling);
        assertThat(as(a.id(), () -> deletion.preview(a.system())).orElseThrow().campaigns()).isEqualTo(2);
        assertThat(as(a.id(), () -> deletion.previewCampaign(target))).isEmpty();
        assertThat(as(a.id(), () -> deletion.deleteCampaign(target))).isFalse();
        // The name is free at once in the same system.
        assertThat(as(a.id(), () -> library.createCampaign(a.system(), "Mesa de quinta"))).isPresent();
        for (UUID version : goingVersions) {
            assertThat(jdbc.sql("select state from material_version where id = ?").param(version).query(String.class)
                    .single()).isEqualTo("discarded");
        }
        assertThat(jobState(ofCampaign.jobId())).isEqualTo("cancelled");
        assertThat(jobState(turnJob(turn))).isEqualTo("cancelled");
        assertThat(jobState(turnJob(siblingTurn))).isNotEqualTo("cancelled");
        assertThat(jobState(ofSibling.jobId())).isNotEqualTo("cancelled");
        assertThat(jobState(searchJob(search))).isEqualTo("cancelled");
        assertThat(jobState(searchJob(siblingSearch))).isNotEqualTo("cancelled");
        assertThat(as(a.id(), () -> searches.find(search))).isEmpty();
        Map<String, Object> row = jdbc.sql("select id, owner_id, kind, not_before, done_at from deletion"
                + " where target_id = ?").param(target).query().singleRow();
        assertThat(row).containsEntry("owner_id", a.id()).containsEntry("kind", "campaign")
                .containsEntry("done_at", null);
        UUID deletionId = (UUID) row.get("id");
        assertThat(jdbc.sql("select count(*) from index_removal where version_id in (?, ?)")
                .params(goingVersions.get(0), goingVersions.get(1)).query(Long.class).single()).isZero();

        // A pass stopped between two handlers: the conversation went, nothing else. The next pass finishes.
        SystemDeletionHandler conversationPart = handlers.stream()
                .filter(handler -> SystemDeletion.CONVERSATIONS.equals(handler.part())).findFirst().orElseThrow();
        OwnerContext.callAs(a.id(), () -> {
            conversationPart.remove(new SystemDeletionHandler.Going(null, List.of(target)));
            return null;
        });
        assertThat(count("turn", "id in (?, ?)", turn, again)).isZero();
        assertThat(count("turn", "id = ?", siblingTurn)).isOne();
        assertThat(count("campaign", "id = ?", target)).isOne();
        assertThat(count("shield", "campaign_id = ?", target)).isOne();

        assertThat(deletion.run(a.id(), deletionId)).isTrue();

        assertThat(count("campaign", "id = ?", target)).isZero();
        // Story 6.1: its Shield went before its row, which shield_campaign_fkey names; the sibling's stays.
        assertThat(count("shield", "campaign_id = ?", target)).isZero();
        assertThat(count("shield", "campaign_id = ?", sibling)).isOne();
        assertThat(count("conversation", "campaign_id = ?", target)).isZero();
        assertThat(count("turn_event_batch", "turn_id = ?", turn)).isZero();
        assertThat(count("turn_reference", "turn_id = ?", turn)).isZero();
        assertThat(count("search", "campaign_id = ?", target)).isZero();
        assertThat(count("search", "id = ?", siblingSearch)).isOne();
        assertThat(count("material", "id in (?, ?)", ofCampaign.materialId(), openMaterial)).isZero();
        for (UUID version : goingVersions) {
            for (String table : List.of("material_version", "upload", "ingestion_step", "version_file", "passage",
                    "page_view", "page_layer", "page_outcome", "version_outline", "index_change")) {
                String column = table.equals("material_version") ? "id" : "version_id";
                assertThat(count(table, column + " = ?", version)).as(table).isZero();
            }
            assertThat(jdbc.sql("select j.kind from index_removal r join job j on j.id = r.job_id where r.version_id = ?")
                    .param(version).query(String.class).list()).as("one index removal").containsExactly("index.removal");
        }
        assertThat(pieces).allSatisfy(file -> assertThat(files.fileExists(a.id(), file)).isFalse());
        assertThat(files.fileExists(a.id(), ofCampaign.uploadId())).isFalse();
        assertThat(files.fileExists(a.id(), open)).isFalse();
        // Its system, the system's material and the sibling campaign with its conversation and material stay.
        assertThat(count("system", "id = ?", a.system())).isOne();
        assertThat(count("campaign", "id in (?, ?)", sibling, a.campaign())).isEqualTo(2);
        assertThat(count("turn", "id = ?", siblingTurn)).isOne();
        assertThat(count("material", "id in (?, ?)", ofSibling.materialId(), ofSystem)).isEqualTo(2);
        assertThat(jdbc.sql("select system_id from material_system where material_id = ?").param(ofSystem)
                .query(UUID.class).list()).containsExactly(a.system());
        assertThat(count("material_version", "id = ?", ofSibling.versionId())).isOne();
        assertThat(count("index_removal", "version_id = ?", ofSibling.versionId())).isZero();
        assertThat(siblingPieces).allSatisfy(file -> assertThat(files.fileExists(a.id(), file)).isTrue());
        assertThat(files.fileExists(a.id(), ofSibling.uploadId())).isTrue();
        assertThat(jdbc.sql("select done_at from deletion where id = ?").param(deletionId).query().singleRow()
                .get("done_at")).isNotNull();

        // Again: nothing, and no second index removal.
        assertThat(deletion.run(a.id(), deletionId)).isFalse();
        for (UUID version : goingVersions) {
            assertThat(count("index_removal", "version_id = ?", version)).isOne();
        }
    }

    @Test
    void theRemovalWaitsForARunningClaimAndTakesAnUploadOfTheWaitOnPostgres() throws Exception {
        Owner b = owner();
        UUID target = campaign(b.id(), b.system(), "Mesa de sábado");
        Completed running = completed(b.id(), new Uploads.Details("rulebook", "pt-BR", target.toString(), (String) null));
        io.github.gmshield.jobs.service.JobClaims.Claim claim = claims.claim(running.jobId());
        // An upload whose last part arrived before the delete, and whose completion comes during the wait.
        byte[] late = pdf(3 * KIB);
        UUID lateUpload = as(b.id(), () -> uploads.create(late.length, "late.pdf",
                new Uploads.Details("rulebook", "pt-BR", target.toString(), (String) null))).uploadId();
        as(b.id(), () -> uploads.patch(lateUpload, 0, late.length, sha256(late), new ByteArrayInputStream(late)));

        assertThat(as(b.id(), () -> deletion.deleteCampaign(target))).isTrue();

        // The current campaign falls back to the starter.
        assertThat(as(b.id(), () -> library.currentCampaign()).orElseThrow().id()).isEqualTo(b.campaign());
        UUID deletionId = jdbc.sql("select id from deletion where target_id = ?").param(target).query(UUID.class)
                .single();
        Instant notBefore = jdbc.sql("select not_before from deletion where id = ?").param(deletionId)
                .query(java.time.OffsetDateTime.class).single().toInstant();
        assertThat(notBefore).isEqualTo(claim.lapsesAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(jobState(running.jobId())).isEqualTo("cancelled");
        assertThat(deletion.run(b.id(), deletionId)).isFalse();
        assertThat(count("index_removal", "version_id = ?", running.versionId())).isZero();
        assertThat(count("material", "id = ?", running.materialId())).isOne();

        // During the wait the late upload completes: its version starts again (it does not know of the deletion).
        as(b.id(), () -> uploads.complete(lateUpload, sha256(late)));
        UUID lateVersion = jdbc.sql("select version_id from upload where id = ?").param(lateUpload).query(UUID.class)
                .single();
        assertThat(jdbc.sql("select state from material_version where id = ?").param(lateVersion).query(String.class)
                .single()).isEqualTo("waiting");
        jdbc.sql("update deletion set not_before = ? where id = ?")
                .params(java.time.OffsetDateTime.now().minusSeconds(1), deletionId).update();

        assertThat(deletion.sweep()).isGreaterThanOrEqualTo(1);

        assertThat(count("campaign", "id = ?", target)).isZero();
        assertThat(count("campaign", "id = ?", b.campaign())).isOne();
        assertThat(count("system", "id = ?", b.system())).isOne();
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
    }

    @Test
    void anArchivedCampaignLeavesTheArchivedListAtOnceAndTheLastCampaignLeavesNoCurrentOneOnPostgres() throws Exception {
        Owner d = owner();
        UUID archived = campaign(d.id(), d.system(), "Mesa antiga");
        assertThat(as(d.id(), () -> library.changeCampaign(archived, 0, null, true)).orElseThrow().archived()).isTrue();
        assertThat(as(d.id(), () -> library.campaigns(null, true, null, 50)).orElseThrow().items())
                .extracting(Library.Campaign::id).containsExactly(archived);
        assertThat(as(d.id(), () -> library.campaigns(d.system(), true, null, 50)).orElseThrow().items())
                .extracting(Library.Campaign::id).containsExactly(archived);

        assertThat(as(d.id(), () -> deletion.deleteCampaign(archived))).isTrue();

        assertThat(as(d.id(), () -> library.campaigns(null, true, null, 50)).orElseThrow().items()).isEmpty();
        assertThat(as(d.id(), () -> library.campaigns(d.system(), true, null, 50)).orElseThrow().items()).isEmpty();
        assertThat(as(d.id(), () -> library.campaign(archived))).isEmpty();
        assertThat(as(d.id(), () -> library.currentCampaign()).orElseThrow().id()).isEqualTo(d.campaign());

        // The current campaign, and the owner's last.
        assertThat(as(d.id(), () -> deletion.deleteCampaign(d.campaign()))).isTrue();

        assertThat(as(d.id(), () -> library.currentCampaign())).isEmpty();
        assertThat(as(d.id(), () -> library.campaigns(null, false, null, 50)).orElseThrow().items()).isEmpty();
        assertThat(as(d.id(), () -> library.system(d.system()))).isPresent();
        for (UUID target : List.of(archived, d.campaign())) {
            UUID id = jdbc.sql("select id from deletion where target_id = ?").param(target).query(UUID.class).single();
            assertThat(deletion.run(d.id(), id)).isTrue();
            assertThat(count("campaign", "id = ?", target)).isZero();
        }
        assertThat(count("system", "id = ?", d.system())).isOne();
        assertThat(as(d.id(), () -> library.currentCampaign())).isEmpty();
    }

    @Test
    void aSystemDeletedWhileItsCampaignIsDeletingTakesItAndBothDeletionsEndDoneOnPostgres() throws Exception {
        Owner c = owner();
        UUID system = as(c.id(), () -> library.createSystem(new Library.SystemFields("Tormenta", null, null))).id();
        UUID target = campaign(c.id(), system, "Mesa de domingo");
        UUID turn = as(c.id(), () -> conversations.ask(target, "Quanto dano?")).turnId();
        Completed ofCampaign = completed(c.id(), new Uploads.Details("rulebook", "pt-BR", target.toString(), (String) null));

        assertThat(as(c.id(), () -> deletion.deleteCampaign(target))).isTrue();
        assertThat(as(c.id(), () -> deletion.preview(system)).orElseThrow())
                .isEqualTo(new SystemDeletion.Counts(0, 0, 0));
        assertThat(as(c.id(), () -> deletion.delete(system))).isTrue();
        UUID ofTarget = jdbc.sql("select id from deletion where target_id = ?").param(target).query(UUID.class).single();
        UUID ofSystem = jdbc.sql("select id from deletion where target_id = ?").param(system).query(UUID.class).single();

        assertThat(deletion.run(c.id(), ofSystem)).isTrue();

        assertThat(count("system", "id = ?", system)).isZero();
        assertThat(count("campaign", "id = ?", target)).isZero();
        assertThat(count("turn", "id = ?", turn)).isZero();
        assertThat(count("material", "id = ?", ofCampaign.materialId())).isZero();
        assertThat(count("index_removal", "version_id = ?", ofCampaign.versionId())).isOne();

        assertThat(deletion.run(c.id(), ofTarget)).isTrue();

        for (UUID id : List.of(ofTarget, ofSystem)) {
            assertThat(jdbc.sql("select done_at from deletion where id = ?").param(id).query().singleRow()
                    .get("done_at")).isNotNull();
        }
        assertThat(count("index_removal", "version_id = ?", ofCampaign.versionId())).isOne();
        assertThat(count("campaign", "id = ?", c.campaign())).isOne();
        assertThat(as(c.id(), () -> library.currentCampaign()).orElseThrow().id()).isEqualTo(c.campaign());
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

    /** A new campaign of the owner's system, as the GM creates one (Story 3.2); it becomes the current one. */
    private UUID campaign(UUID owner, UUID system, String name) throws Exception {
        return as(owner, () -> library.createCampaign(system, name)).orElseThrow().id();
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

    /** An open upload with its details and its first part: its id. */
    private UUID uploads(UUID owner, Uploads.Details details) throws Exception {
        byte[] content = pdf(6 * KIB);
        UUID id = as(owner, () -> uploads.create(content.length * 2L, "Parte.pdf", details)).uploadId();
        as(owner, () -> uploads.patch(id, 0, content.length, sha256(content), new ByteArrayInputStream(content)));
        return id;
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

    /** A batch of answer events and a source reference of the turn, as the answer job stores them. */
    private void eventsAndReference(UUID owner, UUID turn) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("insert into turn_event_batch (id, owner_id, turn_id, first_sequence, last_sequence, payload,"
                + " created_at) values (?, ?, ?, 1, 0, ?, ?)").params(Ids.newId(), owner, turn,
                "x".getBytes(StandardCharsets.UTF_8), java.sql.Timestamp.from(now)).update();
        jdbc.sql("insert into turn_reference (id, owner_id, turn_id, number, version_id, material_id, page, passage_ids,"
                + " material_name, section_path, created_at) values (?, ?, ?, 1, ?, ?, 1, array[?]::uuid[], ?, ?, ?)")
                .params(Ids.newId(), owner, turn, Ids.newId(), Ids.newId(), Ids.newId(),
                        "n".getBytes(StandardCharsets.UTF_8), "s".getBytes(StandardCharsets.UTF_8),
                        java.sql.Timestamp.from(now)).update();
    }

    private UUID materialOf(UUID owner, UUID uploadId) {
        return jdbc.sql("select v.material_id from upload u join material_version v on v.id = u.version_id"
                + " where u.id = ? and u.owner_id = ?").params(uploadId, owner).query(UUID.class).single();
    }

    private UUID versionOf(UUID material) {
        return jdbc.sql("select id from material_version where material_id = ?").param(material).query(UUID.class)
                .single();
    }

    private UUID turnJob(UUID turn) {
        return jdbc.sql("select job_id from turn where id = ?").param(turn).query(UUID.class).single();
    }

    private UUID searchJob(UUID search) {
        return jdbc.sql("select job_id from search where id = ?").param(search).query(UUID.class).single();
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
