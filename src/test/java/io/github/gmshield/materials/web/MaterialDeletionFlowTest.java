package io.github.gmshield.materials.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.accounts.data.AccountRow;
import io.github.gmshield.accounts.data.AccountStore;
import io.github.gmshield.accounts.data.InMemoryAccountStore;
import io.github.gmshield.accounts.data.InMemorySessionStore;
import io.github.gmshield.accounts.data.InMemorySignInAttemptStore;
import io.github.gmshield.accounts.data.SessionStore;
import io.github.gmshield.accounts.data.SignInAttemptStore;
import io.github.gmshield.artifacts.data.ArtifactIndexRow;
import io.github.gmshield.artifacts.data.ArtifactReferenceRow;
import io.github.gmshield.artifacts.data.ArtifactVersionRow;
import io.github.gmshield.artifacts.data.InMemoryArtifactIndexStore;
import io.github.gmshield.artifacts.data.InMemoryArtifactReferenceStore;
import io.github.gmshield.artifacts.data.InMemoryArtifactVersionStore;
import io.github.gmshield.audit.data.AuditStore;
import io.github.gmshield.audit.data.InMemoryAuditStore;
import io.github.gmshield.conversations.data.ConversationTestStores;
import io.github.gmshield.jobs.data.InMemoryJobStorage;
import io.github.gmshield.jobs.data.InMemoryJobStream;
import io.github.gmshield.jobs.data.JobMemoryStores;
import io.github.gmshield.jobs.data.JobRow;
import io.github.gmshield.jobs.data.MutableClock;
import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.jobs.service.Lane;
import io.github.gmshield.knowledge.data.ArtifactRow;
import io.github.gmshield.knowledge.data.InMemoryRecordStore;
import io.github.gmshield.library.data.DeletionRow;
import io.github.gmshield.library.data.InMemoryDeletionStore;
import io.github.gmshield.library.data.LibraryTestStores;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.materials.data.InMemoryIndexStore;
import io.github.gmshield.materials.data.InMemoryMaterialStore;
import io.github.gmshield.materials.data.IndexRemovalRow;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialTestStores;
import io.github.gmshield.materials.data.StepRow;
import io.github.gmshield.materials.data.VersionFileRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.materials.service.IndexRemovalRetrySweep;
import io.github.gmshield.materials.service.IndexRemovals;
import io.github.gmshield.materials.service.ScopeReads;
import io.github.gmshield.materials.service.TestMaterials;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.data.DataKeyStore;
import io.github.gmshield.vault.data.InMemoryDataKeyStore;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.FileStore;
import io.github.gmshield.vault.service.Vault;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Story 3.4 part B without a database or Redis: every row of its I/O matrix. A material's deletion (the dialog's
 * counts, 202, at once absent from the lists, counts and scopes, then no row, file or version left and one
 * {@code index.removal} per version), a second delete, the removal of a superseded version (the ready one still serves,
 * an earlier reference reads removed and its page is not found), a version in another state (409), a version of another
 * material and another owner's material (404), a version that left {@code discarded} during the wait (the pass fails,
 * the next prepares again), a running claim the removal waits for, and a failed {@code index.removal} started again by
 * the sweep. The same rows against PostgreSQL are in {@code MaterialDeletionIntegrationTest}. Story 5.2 part B: a
 * material's deletion touches no artifact identity, version or index row. Story 5.4 part A: an artifact saved from a
 * draft that cited the material keeps its content and its reference, which reads as a removed source.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GMSHIELD_MODE=test")
@ActiveProfiles({"test-mode", "unit"})
class MaterialDeletionFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CREDENTIAL = "gmshield-test-mode-frontend-credential-not-a-secret";
    private static final Instant START = Instant.parse("2026-11-21T12:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    @Import({LibraryTestStores.class, MaterialTestStores.class, JobMemoryStores.class, ConversationTestStores.class})
    static class Stores {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }

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
        SessionStore inMemorySessions(MutableClock clock) {
            return new InMemorySessionStore(clock);
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
    private InMemoryDeletionStore deletions;
    @Autowired
    private InMemoryMaterialStore materials;
    @Autowired
    private InMemoryIndexStore index;
    @Autowired
    private InMemoryRecordStore records;
    @Autowired
    private InMemoryArtifactVersionStore artifactVersions;
    @Autowired
    private InMemoryArtifactIndexStore artifactIndex;
    @Autowired
    private InMemoryArtifactReferenceStore artifactReferences;
    @Autowired
    private InMemoryJobStorage jobs;
    @Autowired
    private InMemoryJobStream stream;
    @Autowired
    private Jobs jobService;
    @Autowired
    private SystemDeletion deletion;
    @Autowired
    private IndexRemovalRetrySweep removalRetry;
    @Autowired
    private IndexRemovals indexRemovals;
    @Autowired
    private ScopeReads scopeReads;
    @Autowired
    private MutableClock clock;
    @Autowired
    private Vault vault;
    @Autowired
    private FileStore files;
    @Autowired
    private PlatformTransactionManager transactions;

    private final HttpClient client = HttpClient.newHttpClient();

    record Gm(UUID id, String session, UUID campaign, UUID system) {
    }

    /** A version of a material, with the files of its pages. */
    record Made(UUID materialId, UUID versionId, List<UUID> files) {
    }

    @BeforeEach
    void start() {
        clock.set(START);
        stream.clear();
    }

    @Test
    void theDialogCountsThenTheDeleteHidesTheMaterialAtOnceAndTheRemovalLeavesNothingOfIt() throws Exception {
        Gm gm = signUp();
        // A ready material replaced once: its first version superseded, its second ready; another material stays.
        Made first = material(gm, gm.campaign(), "superseded", 3, START.minusSeconds(120));
        Made second = version(gm, first.materialId(), "ready", 2, START.minusSeconds(60));
        Made other = material(gm, gm.campaign(), "ready", 1, START.minusSeconds(30));
        // A version the GM discarded before: it has its index removal already, and gets no second one.
        Made discardedBefore = version(gm, first.materialId(), "discarded", 0, START.minusSeconds(90));
        as(gm.id(), () -> {
            index.insertRemoval(new IndexRemovalRow(Ids.newId(), gm.id(), discardedBefore.versionId()));
            return null;
        });
        assertThat(scopeReads(gm, () -> scopeReads.readyOfCampaign(gm.campaign())))
                .contains(second.versionId(), other.versionId());
        assertThat(json(get(gm, "/api/v1/materials?state=ready")).path("total").asLong()).isEqualTo(2);
        JsonNode read = json(get(gm, "/api/v1/materials/" + first.materialId()));
        assertThat(read.path("superseded")).hasSize(1);
        assertThat(read.path("superseded").get(0).path("id").asString()).isEqualTo(first.versionId().toString());
        assertThat(read.path("superseded").get(0).path("pages").asInt()).isEqualTo(3);
        assertThat(read.path("superseded").get(0).path("size").asLong()).isEqualTo(1000);
        assertThat(read.path("superseded").get(0).path("createdAt").asString())
                .isEqualTo(START.minusSeconds(120).toString());
        assertThat(json(get(gm, "/api/v1/materials/" + other.materialId())).path("superseded").isArray()).isTrue();

        // The dialog's counts: the versions that are not discarded, and their pages.
        HttpResponse<String> counted = get(gm, "/api/v1/materials/" + first.materialId() + "/deletion");
        assertThat(counted.statusCode()).as(counted.body()).isEqualTo(200);
        assertThat(counted.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(json(counted).path("versions").asLong()).isEqualTo(2);
        assertThat(json(counted).path("pages").asLong()).isEqualTo(5);

        // Matrix row "Delete material".
        HttpResponse<String> deleted = send(gm, "DELETE", "/api/v1/materials/" + first.materialId());
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(202);
        assertThat(deleted.body()).isEmpty();
        assertThat(deleted.headers().firstValue("Cache-Control")).hasValue("no-store");

        // At once: every version discarded, gone from the scopes, the lists and the counts.
        for (Made version : List.of(first, second, discardedBefore)) {
            assertThat(materials.versions.get(version.versionId()).state()).isEqualTo("discarded");
        }
        assertThat(scopeReads(gm, () -> scopeReads.readyOfCampaign(gm.campaign())))
                .doesNotContain(second.versionId()).contains(other.versionId());
        assertThat(scopeReads(gm, () -> scopeReads.removedVersions(List.of(first.versionId(), second.versionId()))))
                .containsExactlyInAnyOrder(first.versionId(), second.versionId());
        JsonNode listed = json(get(gm, "/api/v1/materials?state=uploading&state=waiting&state=processing"
                + "&state=needs_review&state=ready&state=superseded&state=failed"));
        assertThat(ids(listed.path("items"))).doesNotContain(first.materialId().toString())
                .contains(other.materialId().toString());
        assertThat(listed.path("total").asLong()).isEqualTo(1);
        // Its count and its versions are not found from now on; a second delete is answered as the first (below).
        assertNotFound(get(gm, "/api/v1/materials/" + first.materialId() + "/deletion"));
        assertNotFound(send(gm, "DELETE", "/api/v1/materials/" + first.materialId() + "/versions/" + first.versionId()));
        assertNotFound(send(gm, "DELETE", "/api/v1/materials/" + first.materialId() + "/versions/"
                + discardedBefore.versionId()));
        assertThat(deletions.rows.values()).noneMatch(found -> found.kind().equals(DeletionRow.VERSION)
                && found.ownerId().equals(gm.id()));
        DeletionRow row = deletions.rows.values().stream().filter(found -> found.targetId().equals(first.materialId()))
                .findFirst().orElseThrow();
        assertThat(row.kind()).isEqualTo(DeletionRow.MATERIAL);
        assertThat(row.ownerId()).isEqualTo(gm.id());
        assertThat(row.notBefore()).isEqualTo(clock.instant().truncatedTo(ChronoUnit.MICROS));
        // Nothing is removed before the removal runs, and no index removal starts yet.
        assertThat(materials.materials).containsKey(first.materialId());
        assertThat(index.removals.values()).noneMatch(removal -> removal.versionId().equals(second.versionId()));

        // Matrix row "Delete again": 202, and no second row.
        assertThat(send(gm, "DELETE", "/api/v1/materials/" + first.materialId()).statusCode()).isEqualTo(202);
        assertThat(deletions.rows.values()).filteredOn(found -> found.targetId().equals(first.materialId())).hasSize(1);

        assertThat(deletion.sweep()).isEqualTo(1);

        assertThat(materials.materials).doesNotContainKey(first.materialId()).containsKey(other.materialId());
        for (Made version : List.of(first, second, discardedBefore)) {
            assertThat(materials.versions).doesNotContainKey(version.versionId());
            assertThat(index.removals.values()).as("one index removal per version")
                    .filteredOn(removal -> removal.versionId().equals(version.versionId())).hasSize(1);
            assertThat(materials.passages.values()).noneMatch(passage -> passage.versionId().equals(version.versionId()));
            assertThat(materials.pageViews.values()).noneMatch(view -> view.versionId().equals(version.versionId()));
            assertThat(materials.files.values()).noneMatch(file -> file.versionId().equals(version.versionId()));
        }
        assertThat(first.files()).isNotEmpty().allSatisfy(file -> assertThat(files.fileExists(gm.id(), file)).isFalse());
        assertThat(second.files()).isNotEmpty()
                .allSatisfy(file -> assertThat(files.fileExists(gm.id(), file)).isFalse());
        assertThat(other.files()).isNotEmpty().allSatisfy(file -> assertThat(files.fileExists(gm.id(), file)).isTrue());
        assertThat(deletions.rows.get(row.id()).doneAt()).isNotNull();
        assertNotFound(get(gm, "/api/v1/materials/" + first.materialId()));
        assertNotFound(send(gm, "DELETE", "/api/v1/materials/" + first.materialId()));

        // Again: nothing to do, and no second index removal.
        int removals = index.removals.size();
        assertThat(deletion.sweep()).isZero();
        assertThat(index.removals).hasSize(removals);
    }

    @Test
    void aSavedDraftsSourceReadsRemovedOnceItsMaterialIsDeletedAndTheArtifactKeepsItsContent() throws Exception {
        Gm gm = signUp();
        Made book = material(gm, gm.campaign(), "ready", 2, START.minusSeconds(60));
        String artifacts = "/api/v1/campaigns/" + gm.campaign() + "/artifacts";
        HttpResponse<String> created = sendJson(gm, "POST", artifacts, "{\"recordType\":\"encounter\","
                + "\"name\":\"Emboscada\",\"fields\":[],\"content\":\"## Cripta\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID artifact = UUID.fromString(json(created).path("id").asString());
        UUID passage = materials.passages.values().stream().filter(row -> row.versionId().equals(book.versionId()))
                .findFirst().orElseThrow().id();
        // The draft's reference as a save copies it: sealed under the artifact.
        as(gm.id(), () -> {
            artifactReferences.insert(List.of(new ArtifactReferenceRow(artifact, gm.id(), 1, book.versionId(),
                    book.materialId(), 1, List.of(passage),
                    vault.encryptText(new Binding("artifact_reference", "material_name:1", artifact, gm.id()), "Livro"),
                    vault.encrypt(new Binding("artifact_reference", "section_path:1", artifact, gm.id()),
                            "[\"A\"]".getBytes(StandardCharsets.UTF_8)), "campaign", null, null, null, null, null,
                    null, START)));
            return null;
        });
        JsonNode before = json(get(gm, artifacts + "/" + artifact));
        assertThat(before.path("references").get(0).path("removed").asBoolean()).isFalse();

        // Matrix row "Source gone".
        assertThat(send(gm, "DELETE", "/api/v1/materials/" + book.materialId()).statusCode()).isEqualTo(202);
        assertThat(deletion.sweep()).isEqualTo(1);

        JsonNode after = json(get(gm, artifacts + "/" + artifact));
        assertThat(after.path("content").asString()).isEqualTo("## Cripta");
        assertThat(after.path("references")).hasSize(1);
        assertThat(after.path("references").get(0).path("removed").asBoolean()).isTrue();
        assertThat(after.path("references").get(0).path("materialName").asString()).isEqualTo("Livro");
        assertThat(artifactReferences.rows).containsKey(artifact + "/1");
    }

    @Test
    void aMaterialsDeletionLeavesEveryArtifactWithAllItsVersions() throws Exception {
        Gm gm = signUp();
        Made book = material(gm, null, "ready", 2, START.minusSeconds(60));
        String artifacts = "/api/v1/campaigns/" + gm.campaign() + "/artifacts";
        HttpResponse<String> created = sendJson(gm, "POST", artifacts, "{\"recordType\":\"item\","
                + "\"name\":\"Espada de Kael\",\"fields\":[]}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID artifact = UUID.fromString(json(created).path("id").asString());
        HttpResponse<String> edited = sendJson(gm, "PATCH", artifacts + "/" + artifact, "{\"version\":1,"
                + "\"recordType\":\"item\",\"name\":\"Espada de Kael II\",\"fields\":[]}");
        assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
        Map<String, ArtifactVersionRow> versionsBefore = Map.copyOf(artifactVersions.rows);
        ArtifactRow identity = records.artifacts.get(artifact);
        Map<UUID, ArtifactIndexRow> indexBefore = Map.copyOf(artifactIndex.rows);

        // Matrix row "Material deleted".
        assertThat(send(gm, "DELETE", "/api/v1/materials/" + book.materialId()).statusCode()).isEqualTo(202);
        assertThat(deletion.sweep()).isEqualTo(1);

        assertThat(materials.materials).doesNotContainKey(book.materialId());
        assertThat(records.artifacts.get(artifact)).isEqualTo(identity);
        assertThat(records.deletingArtifacts).doesNotContain(artifact);
        assertThat(artifactVersions.rows).isEqualTo(versionsBefore).containsKeys(artifact + "/1", artifact + "/2");
        assertThat(artifactIndex.rows).isEqualTo(indexBefore);
        assertThat(artifactIndex.removalRows.values()).noneMatch(row -> row.artifactId().equals(artifact));
        assertThat(deletions.rows.values()).noneMatch(row -> row.kind().equals(DeletionRow.ARTIFACT));
        JsonNode read = json(get(gm, artifacts + "/" + artifact));
        assertThat(read.path("version").asInt()).isEqualTo(2);
        assertThat(json(get(gm, artifacts + "/" + artifact + "/versions")).path("total").asLong()).isEqualTo(2);
    }

    @Test
    void removingASupersededVersionKeepsTheMaterialServingAndAnEarlierReferenceReadsRemoved() throws Exception {
        Gm gm = signUp();
        Made first = material(gm, gm.campaign(), "superseded", 2, START.minusSeconds(120));
        Made second = version(gm, first.materialId(), "ready", 1, START.minusSeconds(60));
        // A turn cited page 1 of the first version: it opens while the version is superseded.
        assertThat(scopeReads(gm, () -> scopeReads.versionPage(first.materialId(), first.versionId(), 1))).isPresent();

        // Matrix row "Remove version".
        HttpResponse<String> removed = send(gm, "DELETE",
                "/api/v1/materials/" + first.materialId() + "/versions/" + first.versionId());
        assertThat(removed.statusCode()).as(removed.body()).isEqualTo(202);
        assertThat(removed.body()).isEmpty();
        assertThat(removed.headers().firstValue("Cache-Control")).hasValue("no-store");

        // At once: gone from the material's superseded versions; the reference reads removed; the ready one serves.
        JsonNode read = json(get(gm, "/api/v1/materials/" + first.materialId()));
        assertThat(read.path("state").asString()).isEqualTo("ready");
        assertThat(read.path("superseded")).isEmpty();
        assertThat(scopeReads(gm, () -> scopeReads.removedVersions(List.of(first.versionId(), second.versionId()))))
                .containsExactly(first.versionId());
        assertThat(scopeReads(gm, () -> scopeReads.versionPage(first.materialId(), first.versionId(), 1))).isEmpty();
        assertThat(scopeReads(gm, () -> scopeReads.readyOfCampaign(gm.campaign()))).containsExactly(second.versionId());
        DeletionRow row = deletions.rows.values().stream().filter(found -> found.targetId().equals(first.versionId()))
                .findFirst().orElseThrow();
        assertThat(row.kind()).isEqualTo(DeletionRow.VERSION);
        // Again, while its removal is recorded: 202, and no second row.
        assertThat(send(gm, "DELETE", "/api/v1/materials/" + first.materialId() + "/versions/" + first.versionId())
                .statusCode()).isEqualTo(202);
        assertThat(deletions.rows.values()).filteredOn(found -> found.targetId().equals(first.versionId())).hasSize(1);

        assertThat(deletion.sweep()).isEqualTo(1);

        assertThat(materials.materials).containsKey(first.materialId());
        assertThat(materials.versions).doesNotContainKey(first.versionId());
        assertThat(materials.versions.get(second.versionId()).state()).isEqualTo("ready");
        assertThat(index.removals.values()).filteredOn(removal -> removal.versionId().equals(first.versionId()))
                .hasSize(1);
        assertThat(index.removals.values()).noneMatch(removal -> removal.versionId().equals(second.versionId()));
        assertThat(materials.passages.values()).noneMatch(passage -> passage.versionId().equals(first.versionId()));
        assertThat(materials.pageViews.values()).noneMatch(view -> view.versionId().equals(first.versionId()));
        assertThat(first.files()).isNotEmpty().allSatisfy(file -> assertThat(files.fileExists(gm.id(), file)).isFalse());
        assertThat(second.files()).isNotEmpty().allSatisfy(file -> assertThat(files.fileExists(gm.id(), file)).isTrue());
        assertThat(scopeReads(gm, () -> scopeReads.removedVersions(List.of(first.versionId()))))
                .containsExactly(first.versionId());
        assertThat(scopeReads(gm, () -> scopeReads.versionPage(first.materialId(), first.versionId(), 1))).isEmpty();
        assertThat(scopeReads(gm, () -> scopeReads.versionPage(first.materialId(), second.versionId(), 1)))
                .isPresent();
        assertThat(deletions.rows.get(row.id()).doneAt()).isNotNull();
        // Once removed, the version is gone: not found.
        assertNotFound(send(gm, "DELETE", "/api/v1/materials/" + first.materialId() + "/versions/" + first.versionId()));
    }

    @Test
    void aPendingVersionRemovalWhoseVersionWentWithItsMaterialEndsAsDone() throws Exception {
        Gm gm = signUp();
        Made first = material(gm, null, "superseded", 1, START.minusSeconds(120));
        version(gm, first.materialId(), "ready", 1, START.minusSeconds(60));
        assertThat(send(gm, "DELETE", "/api/v1/materials/" + first.materialId() + "/versions/" + first.versionId())
                .statusCode()).isEqualTo(202);
        assertThat(send(gm, "DELETE", "/api/v1/materials/" + first.materialId()).statusCode()).isEqualTo(202);
        DeletionRow ofVersion = deletions.rows.values().stream()
                .filter(found -> found.targetId().equals(first.versionId())).findFirst().orElseThrow();
        DeletionRow ofMaterial = deletions.rows.values().stream()
                .filter(found -> found.targetId().equals(first.materialId())).findFirst().orElseThrow();
        // The material's deletion runs first and takes every version, the one whose removal is pending included.
        assertThat(deletion.run(gm.id(), ofMaterial.id())).isTrue();
        assertThat(materials.versions).doesNotContainKey(first.versionId());
        assertThat(deletions.rows.get(ofVersion.id()).doneAt()).isNull();

        // The version's own removal finds nothing left of it, and ends.
        assertThat(deletion.sweep()).isEqualTo(1);
        assertThat(deletions.rows.get(ofVersion.id()).doneAt()).isNotNull();
        assertThat(index.removals.values()).filteredOn(removal -> removal.versionId().equals(first.versionId()))
                .hasSize(1);
        assertThat(deletion.sweep()).isZero();
    }

    @Test
    void aMaterialWhoseEveryPlaceIsBeingDeletedIsNotFoundOnTheThreeRoutes() throws Exception {
        Gm gm = signUp();
        Made first = material(gm, gm.campaign(), "superseded", 1, START.minusSeconds(120));
        version(gm, first.materialId(), "ready", 1, START.minusSeconds(60));
        assertThat(get(gm, "/api/v1/materials/" + first.materialId() + "/deletion").statusCode()).isEqualTo(200);
        // Its campaign's system is deleted: the campaign is being deleted with it.
        assertThat(send(gm, "DELETE", "/api/v1/systems/" + gm.system()).statusCode()).isEqualTo(202);

        assertNotFound(get(gm, "/api/v1/materials/" + first.materialId() + "/deletion"));
        assertNotFound(send(gm, "DELETE", "/api/v1/materials/" + first.materialId()));
        assertNotFound(send(gm, "DELETE", "/api/v1/materials/" + first.materialId() + "/versions/" + first.versionId()));
        assertThat(deletions.rows.values()).noneMatch(found -> found.ownerId().equals(gm.id())
                && (found.kind().equals(DeletionRow.MATERIAL) || found.kind().equals(DeletionRow.VERSION)));

        // The system's deletion ends, so it leaves nothing due for the other tests' sweeps.
        DeletionRow ofSystem = deletions.rows.values().stream().filter(found -> found.targetId().equals(gm.system()))
                .findFirst().orElseThrow();
        assertThat(deletion.run(gm.id(), ofSystem.id())).isTrue();
        assertThat(materials.materials).doesNotContainKey(first.materialId());
    }

    @Test
    void aVersionInAnotherStateIsNotRemovableAndAVersionOfAnotherMaterialIsNotFound() throws Exception {
        Gm gm = signUp();
        Made ready = material(gm, null, "ready", 1, START.minusSeconds(120));
        Made reviewing = version(gm, ready.materialId(), "needs_review", 1, START.minusSeconds(60));
        Made discarded = version(gm, ready.materialId(), "discarded", 0, START.minusSeconds(90));
        Made elsewhere = material(gm, null, "superseded", 1, START.minusSeconds(150));

        // Matrix row "Remove other state".
        for (Made version : List.of(ready, reviewing, discarded)) {
            HttpResponse<String> refused = send(gm, "DELETE",
                    "/api/v1/materials/" + ready.materialId() + "/versions/" + version.versionId());
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
            assertThat(json(refused).path("code").asString()).isEqualTo("material.version_not_removable");
        }
        // Matrix row "Wrong material".
        assertNotFound(send(gm, "DELETE", "/api/v1/materials/" + ready.materialId() + "/versions/"
                + elsewhere.versionId()));
        assertNotFound(send(gm, "DELETE", "/api/v1/materials/" + ready.materialId() + "/versions/" + Ids.newId()));
        assertNotFound(send(gm, "DELETE", "/api/v1/materials/" + ready.materialId() + "/versions/not-an-id"));
        assertNotFound(send(gm, "DELETE", "/api/v1/materials/not-an-id/versions/" + elsewhere.versionId()));

        assertThat(deletions.rows.values()).noneMatch(found -> found.ownerId().equals(gm.id()));
        assertThat(materials.versions.get(ready.versionId()).state()).isEqualTo("ready");
        assertThat(materials.versions.get(reviewing.versionId()).state()).isEqualTo("needs_review");
        assertThat(materials.versions.get(elsewhere.versionId()).state()).isEqualTo("superseded");
    }

    @Test
    void anotherOwnersMaterialIsNotFoundOnEveryRouteAndNothingChanges() throws Exception {
        Gm owner = signUp();
        Gm other = signUp();
        Made first = material(owner, null, "superseded", 1, START.minusSeconds(120));
        version(owner, first.materialId(), "ready", 1, START.minusSeconds(60));

        // Matrix row "Other owner".
        assertNotFound(get(other, "/api/v1/materials/" + first.materialId() + "/deletion"));
        assertNotFound(send(other, "DELETE", "/api/v1/materials/" + first.materialId()));
        assertNotFound(send(other, "DELETE", "/api/v1/materials/" + first.materialId() + "/versions/"
                + first.versionId()));
        assertNotFound(get(owner, "/api/v1/materials/" + Ids.newId() + "/deletion"));
        assertNotFound(get(owner, "/api/v1/materials/not-an-id/deletion"));
        assertNotFound(send(owner, "DELETE", "/api/v1/materials/" + Ids.newId()));
        assertNotFound(send(owner, "DELETE", "/api/v1/materials/not-an-id"));

        assertThat(deletions.rows.values()).noneMatch(found -> found.ownerId().equals(owner.id())
                || found.ownerId().equals(other.id()));
        assertThat(materials.versions.get(first.versionId()).state()).isEqualTo("superseded");
        assertThat(json(get(owner, "/api/v1/materials/" + first.materialId())).path("superseded")).hasSize(1);
    }

    @Test
    void aReplacementCompletedDuringTheWaitMakesThePassPrepareAgainAndThenEverythingGoes() throws Exception {
        Gm gm = signUp();
        Made ready = material(gm, null, "ready", 1, START.minusSeconds(120));
        Made replacement = version(gm, ready.materialId(), "uploading", 0, START.minusSeconds(60));
        assertThat(send(gm, "DELETE", "/api/v1/materials/" + ready.materialId()).statusCode()).isEqualTo(202);
        assertThat(materials.versions.get(replacement.versionId()).state()).isEqualTo("discarded");
        DeletionRow row = deletions.rows.values().stream().filter(found -> found.targetId().equals(ready.materialId()))
                .findFirst().orElseThrow();

        // Matrix row "Upload during wait": the replacement's upload completes before the pass (it does not know of the
        // deletion): its version waits again, and its first step is claimed.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        as(gm.id(), () -> materials.changeVersionState(replacement.versionId(), "waiting", now));
        Instant lapses = now.plusSeconds(90);
        UUID job = runningStep(gm, replacement.versionId(), lapses);

        // The pass prepares again: the version is discarded again, its step's job cancelled, and the pass waits.
        assertThat(deletion.sweep()).isZero();
        assertThat(materials.versions.get(replacement.versionId()).state()).isEqualTo("discarded");
        assertThat(jobs.rows.get(job).state()).isEqualTo("cancelled");
        assertThat(deletions.rows.get(row.id()).notBefore()).isEqualTo(lapses);
        assertThat(deletions.rows.get(row.id()).doneAt()).isNull();
        assertThat(materials.materials).containsKey(ready.materialId());
        assertThat(index.removals.values()).noneMatch(removal -> removal.versionId().equals(replacement.versionId()));

        clock.set(lapses.plus(Duration.ofSeconds(1)));
        assertThat(deletion.sweep()).isEqualTo(1);

        assertThat(materials.materials).doesNotContainKey(ready.materialId());
        assertThat(materials.versions).doesNotContainKeys(ready.versionId(), replacement.versionId());
        assertThat(index.removals.values()).filteredOn(removal -> removal.versionId().equals(replacement.versionId()))
                .hasSize(1);
        assertThat(deletions.rows.get(row.id()).doneAt()).isNotNull();
    }

    @Test
    void aRunningStepMakesTheRemovalWaitForItsClaim() throws Exception {
        Gm gm = signUp();
        Made ready = material(gm, null, "ready", 1, START.minusSeconds(120));
        Made processing = version(gm, ready.materialId(), "processing", 0, START.minusSeconds(60));
        Instant lapses = START.plusSeconds(90);
        UUID job = runningStep(gm, processing.versionId(), lapses);

        assertThat(send(gm, "DELETE", "/api/v1/materials/" + ready.materialId()).statusCode()).isEqualTo(202);

        assertThat(jobs.rows.get(job).state()).isEqualTo("cancelled");
        DeletionRow row = deletions.rows.values().stream().filter(found -> found.targetId().equals(ready.materialId()))
                .findFirst().orElseThrow();
        assertThat(row.notBefore()).isEqualTo(lapses);
        assertThat(deletion.sweep()).isZero();
        assertThat(deletion.run(gm.id(), row.id())).isFalse();
        assertThat(materials.materials).containsKey(ready.materialId());

        clock.set(lapses.plus(Duration.ofSeconds(1)));
        assertThat(deletion.sweep()).isEqualTo(1);
        assertThat(materials.materials).doesNotContainKey(ready.materialId());
        assertThat(materials.versions).doesNotContainKeys(ready.versionId(), processing.versionId());
    }

    @Test
    void aFailedIndexRemovalGetsANewJobWithTheSameInputAndItsRowMoves() throws Exception {
        Gm gm = signUp();
        UUID version = Ids.newId();
        UUID failed = as(gm.id(), () -> jobService.create("index.removal",
                job -> index.insertRemoval(new IndexRemovalRow(job.id(), gm.id(), version))).id());
        UUID done = as(gm.id(), () -> jobService.create("index.removal",
                job -> index.insertRemoval(new IndexRemovalRow(job.id(), gm.id(), Ids.newId()))).id());
        Instant now = clock.instant();
        jobs.rows.put(failed, jobs.rows.get(failed).withState("failed", now));
        jobs.rows.put(done, jobs.rows.get(done).withState("done", now));

        // Matrix row "Failed removal".
        assertThat(removalRetry.sweep()).isEqualTo(1);

        assertThat(index.removals).doesNotContainKey(failed).containsKey(done);
        IndexRemovalRow moved = index.removals.values().stream().filter(row -> row.versionId().equals(version))
                .findFirst().orElseThrow();
        assertThat(moved.jobId()).isNotEqualTo(failed);
        assertThat(moved.ownerId()).isEqualTo(gm.id());
        JobRow job = jobs.rows.get(moved.jobId());
        assertThat(job.kind()).isEqualTo("index.removal");
        assertThat(job.state()).isIn("waiting", "offered");
        assertThat(jobs.rows.get(failed).state()).isEqualTo("failed");
        // The worker's input at the claim: the same owner and source.
        JsonNode input = as(gm.id(), () -> indexRemovals.input(new JobRef(moved.jobId(), gm.id(), IndexRemovals.KIND,
                Lane.INGESTION, 1, null)));
        assertThat(input.path("source").asString()).isEqualTo(version.toString());
        assertThat(input.path("owner").asString()).isEqualTo(gm.id().toString());

        // The failed job kept as history has no row: it is never started twice.
        assertThat(removalRetry.sweep()).isZero();
        assertThat(index.removals.values()).filteredOn(row -> row.versionId().equals(version)).hasSize(1);
    }

    @Test
    void oneOwnersFailingRemovalRetryNeverStopsAnotherOwnersRetry() throws Exception {
        Gm one = signUp();
        Gm two = signUp();
        // The sweep visits the owners by id: the one visited first fails.
        Gm failing = one.id().compareTo(two.id()) < 0 ? one : two;
        Gm other = failing == one ? two : one;
        IndexRemovalRow failingRow = failedRemoval(failing);
        IndexRemovalRow otherRow = failedRemoval(other);
        index.refusedRemovalMoves.add(failingRow.jobId());
        try {
            assertThat(removalRetry.sweep()).isEqualTo(1);
        } finally {
            index.refusedRemovalMoves.clear();
        }

        // The other owner's row moved to a new job; the failing owner's is untouched, and tried next time.
        IndexRemovalRow moved = index.removals.values().stream()
                .filter(row -> row.versionId().equals(otherRow.versionId())).findFirst().orElseThrow();
        assertThat(moved.jobId()).isNotEqualTo(otherRow.jobId());
        assertThat(index.removals).containsEntry(failingRow.jobId(), failingRow);

        assertThat(removalRetry.sweep()).isEqualTo(1);
        IndexRemovalRow retried = index.removals.values().stream()
                .filter(row -> row.versionId().equals(failingRow.versionId())).findFirst().orElseThrow();
        assertThat(retried.jobId()).isNotEqualTo(failingRow.jobId());
        assertThat(jobs.rows.get(retried.jobId()).state()).isIn("waiting", "offered");
        assertThat(removalRetry.sweep()).isZero();
    }

    /** An index removal of the GM, of a version gone already, whose job ended failed. */
    private IndexRemovalRow failedRemoval(Gm gm) {
        UUID version = Ids.newId();
        UUID job = as(gm.id(), () -> jobService.create("index.removal",
                created -> index.insertRemoval(new IndexRemovalRow(created.id(), gm.id(), version))).id());
        jobs.rows.put(job, jobs.rows.get(job).withState("failed", clock.instant()));
        return index.removals.get(job);
    }

    // --- helpers

    /** A material of the campaign (or of no place), its first version in {@code state}, with picture pages. */
    private Made material(Gm gm, UUID campaign, String state, int pages, Instant at) {
        UUID materialId = Ids.newId();
        as(gm.id(), () -> {
            materials.insertMaterial(new MaterialRow(materialId, gm.id(),
                    TestMaterials.materialName(vault, materialId, gm.id(), "Livro"), at.truncatedTo(ChronoUnit.MICROS),
                    "rulebook", null, campaign));
            return null;
        });
        return version(gm, materialId, state, pages, at);
    }

    /** A version of the material in {@code state}, created at {@code at}, with picture pages and a passage on each. */
    private Made version(Gm gm, UUID materialId, String state, int pages, Instant at) {
        return as(gm.id(), () -> {
            Instant created = at.truncatedTo(ChronoUnit.MICROS);
            UUID versionId = Ids.newId();
            materials.insertVersion(new VersionRow(versionId, gm.id(), materialId, state, "pdf", 1000L, created,
                    created, pages == 0 ? null : pages, "5", null));
            for (int page = 1; page <= pages; page++) {
                TestMaterials.picturePage(materials, vault, files, gm.id(), versionId, page, new byte[] {1, 2, 3}, 10,
                        10, List.of(), created);
                materials.insertPassages(List.of(TestMaterials.passage(vault, Ids.newId(), gm.id(), versionId,
                        page - 1, page, "Texto", List.of("A"), created)));
            }
            List<UUID> stored = materials.files.values().stream().filter(file -> file.versionId().equals(versionId))
                    .map(VersionFileRow::fileId).toList();
            return new Made(materialId, versionId, stored);
        });
    }

    /** An ingestion step of the version whose job is running, under a claim that lapses at {@code lapses}: its job. */
    private UUID runningStep(Gm gm, UUID versionId, Instant lapses) {
        UUID job = as(gm.id(), () -> jobService.create("ingestion", created -> materials.insertStep(
                new StepRow(Ids.newId(), gm.id(), versionId, created.id(), "clean", null, null, Ids.newId(),
                        clock.instant().truncatedTo(ChronoUnit.MICROS)))).id());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        jobs.rows.put(job, jobs.rows.get(job).claimed(Ids.newId(), new byte[] {1}, now.plusSeconds(30), lapses, now));
        return job;
    }

    private <T> T as(UUID owner, Supplier<T> work) {
        return OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status -> work.get()));
    }

    private static <T> T scopeReads(Gm gm, Supplier<T> read) {
        return OwnerContext.callAs(gm.id(), read::get);
    }

    private static List<String> ids(JsonNode items) {
        List<String> found = new java.util.ArrayList<>();
        items.forEach(item -> found.add(item.path("id").asString()));
        return found;
    }

    private static void assertNotFound(HttpResponse<String> answer) {
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(404);
        assertThat(json(answer).path("code").asString()).isEqualTo("request.not_found");
    }

    private HttpResponse<String> get(Gm gm, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Session-Token", gm.session()).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendJson(Gm gm, String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Session-Token", gm.session()).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(Gm gm, String method, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Session-Token", gm.session()).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private Gm signUp() throws Exception {
        String sub = "mdel-" + UUID.randomUUID();
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
        String session = json(response).path("sessionToken").asString();
        JsonNode campaign = json(client.send(HttpRequest.newBuilder(uri("/api/v1/campaigns/current"))
                .header("Authorization", "Bearer " + CREDENTIAL).header("X-Session-Token", session).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
        return new Gm(row.id(), session, UUID.fromString(campaign.path("id").asString()),
                UUID.fromString(campaign.path("system").path("id").asString()));
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
}
