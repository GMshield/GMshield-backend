package io.github.gmshield.library.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.accounts.data.AccountRow;
import io.github.gmshield.accounts.data.AccountStore;
import io.github.gmshield.accounts.data.InMemoryAccountStore;
import io.github.gmshield.accounts.data.InMemorySessionStore;
import io.github.gmshield.accounts.data.InMemorySignInAttemptStore;
import io.github.gmshield.accounts.data.SessionStore;
import io.github.gmshield.accounts.data.SignInAttemptStore;
import io.github.gmshield.artifacts.data.InMemoryArtifactShareStore;
import io.github.gmshield.audit.data.AuditStore;
import io.github.gmshield.audit.data.InMemoryAuditStore;
import io.github.gmshield.conversations.data.ConversationTestStores;
import io.github.gmshield.conversations.data.InMemoryConversationStore;
import io.github.gmshield.conversations.data.InMemorySearchStore;
import io.github.gmshield.conversations.data.InMemoryTurnReferenceStore;
import io.github.gmshield.jobs.data.InMemoryJobStorage;
import io.github.gmshield.jobs.data.InMemoryJobStream;
import io.github.gmshield.jobs.data.JobMemoryStores;
import io.github.gmshield.jobs.data.MutableClock;
import io.github.gmshield.library.data.DeletionRow;
import io.github.gmshield.library.data.InMemoryDeletionStore;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.data.LibraryTestStores;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.materials.data.InMemoryIndexStore;
import io.github.gmshield.materials.data.IndexRemovalRow;
import io.github.gmshield.materials.data.InMemoryMaterialStore;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialSystemRow;
import io.github.gmshield.materials.data.MaterialTestStores;
import io.github.gmshield.materials.data.VersionFileRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.materials.service.TestMaterials;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.data.DataKeyStore;
import io.github.gmshield.vault.data.InMemoryDataKeyStore;
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
import java.util.Arrays;
import java.util.Base64;
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
 * Story 3.2 part B without a database or Redis: every backend row of its I/O matrix. What a campaign's deletion names,
 * the delete (202) after which the campaign is "not found" on every campaign route (and to uploads naming it), gone
 * from the lists and the system's preview, its name free at once and the current campaign fallen back; a second
 * delete; the removal of what goes (the campaign, its conversation, its materials with their files and one
 * {@code index.removal} per version) and of nothing else (its system, the system's materials, the sibling campaign
 * and its conversation); the removal waiting for a running answer's claim; an archived campaign and the last one
 * deleted; another owner's campaign on both routes. The same rows against PostgreSQL are in
 * {@code CampaignDeletionIntegrationTest}.
 *
 * <p>Story 5.6 part A: a campaign an artifact is shared into, once deleted, is no target and not listed, and its pass
 * removes the shares into it before the campaign row; an artifact's own campaign deleted takes its shares, and the
 * campaign it was shared into reads it no more.
 *
 * <p>Story 6.1: a deleted campaign's Shields go before its row, which their key names; the other campaigns' stay.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GMSHIELD_MODE=test")
@ActiveProfiles({"test-mode", "unit"})
class CampaignDeletionFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CREDENTIAL = "gmshield-test-mode-frontend-credential-not-a-secret";
    private static final String WORKER_CREDENTIAL = "gmshield-test-mode-worker-credential-not-a-secret";
    private static final Instant START = Instant.parse("2026-11-20T12:00:00Z");

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
    private InMemoryLibraryStore library;
    @Autowired
    private InMemoryArtifactShareStore shares;
    @Autowired
    private InMemoryDeletionStore deletions;
    @Autowired
    private InMemoryMaterialStore materials;
    @Autowired
    private InMemoryIndexStore index;
    @Autowired
    private InMemoryConversationStore conversations;
    @Autowired
    private InMemoryTurnReferenceStore references;
    @Autowired
    private InMemorySearchStore searches;
    @Autowired
    private InMemoryJobStorage jobs;
    @Autowired
    private io.github.gmshield.scope.data.InMemoryScopeStore scopes;
    @Autowired
    private InMemoryJobStream stream;
    @Autowired
    private SystemDeletion deletion;
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

    /** A material and its version, with the files of its pages. */
    record Made(UUID materialId, UUID versionId, List<UUID> files) {
    }

    @BeforeEach
    void start() {
        clock.set(START);
        stream.clear();
    }

    @Test
    void theDialogsCountsThenTheDeleteHidesTheCampaignEverywhereAndTheRemovalLeavesOnlyWhatStays() throws Exception {
        Gm gm = signUp();
        tick();
        UUID target = createCampaign(gm, gm.system(), "Mesa de sexta");
        tick();
        UUID sibling = createCampaign(gm, gm.system(), "Mesa de sábado");
        tick();
        // The campaign to delete is the current one.
        assertThat(send(gm, "PUT", "/api/v1/campaigns/current", "{\"campaignId\":\"" + target + "\"}").statusCode())
                .isEqualTo(200);
        UUID firstTurn = turnId(ask(gm, target));
        UUID secondTurn = turnId(ask(gm, target));
        UUID siblingTurn = turnId(ask(gm, sibling));
        // Story 3.9: a search of each campaign.
        UUID targetSearch = searchId(gm, target);
        UUID siblingSearch = searchId(gm, sibling);
        Made ready = material(gm, target, List.of(), "ready", 2);
        Made reviewing = material(gm, target, List.of(), "needs_review", 1);
        // Its one version was discarded: it goes, and the dialog does not count it.
        Made hidden = material(gm, target, List.of(), "discarded", 0);
        Made ofSibling = material(gm, sibling, List.of(), "ready", 1);
        Made ofSystem = material(gm, null, List.of(gm.system()), "ready", 1);
        // A version the GM discarded before: it has its index removal already, and gets no second one.
        // Story 3.5: both campaigns have stored scope settings, each with a material switched off.
        assertThat(send(gm, "PUT", "/api/v1/campaigns/" + target + "/scope", "{\"version\":0,\"campaignTier\":true,"
                + "\"systemTier\":true,\"otherTier\":true,\"modelKnowledge\":false,\"materialsOff\":[\""
                + ready.materialId() + "\"]}").statusCode()).isEqualTo(200);
        assertThat(send(gm, "PUT", "/api/v1/campaigns/" + sibling + "/scope", "{\"version\":0,\"campaignTier\":true,"
                + "\"systemTier\":true,\"otherTier\":false,\"modelKnowledge\":false,\"materialsOff\":[\""
                + ofSystem.materialId() + "\"]}").statusCode()).isEqualTo(200);
        UUID discardedBefore = version(gm, ready.materialId(), "discarded");
        as(gm.id(), () -> {
            index.insertRemoval(new IndexRemovalRow(Ids.newId(), gm.id(), discardedBefore));
            return null;
        });
        assertThat(json(get(gm, "/api/v1/systems/" + gm.system() + "/deletion")).path("campaigns").asLong())
                .isEqualTo(3);

        HttpResponse<String> preview = get(gm, "/api/v1/campaigns/" + target + "/deletion");
        assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
        assertThat(preview.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(json(preview).path("conversations").asLong()).isEqualTo(1);
        assertThat(json(preview).path("materials").asLong()).isEqualTo(2);
        assertThat(json(preview).has("campaigns")).isFalse();

        HttpResponse<String> deleted = send(gm, "DELETE", "/api/v1/campaigns/" + target);
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(202);
        assertThat(deleted.body()).isEmpty();
        assertThat(deleted.headers().firstValue("Cache-Control")).hasValue("no-store");

        // From the commit: not found on every campaign route, and gone from every list.
        assertNotFound(get(gm, "/api/v1/campaigns/" + target));
        assertNotFound(get(gm, "/api/v1/campaigns/" + target + "/deletion"));
        assertNotFound(send(gm, "PATCH", "/api/v1/campaigns/" + target, "{\"version\":0,\"name\":\"X\"}"));
        assertNotFound(send(gm, "PATCH", "/api/v1/campaigns/" + target, "{\"version\":0,\"archived\":true}"));
        assertNotFound(send(gm, "PUT", "/api/v1/campaigns/" + target + "/party", "{\"version\":0,\"size\":4,\"level\":5}"));
        assertNotFound(send(gm, "PUT", "/api/v1/campaigns/current", "{\"campaignId\":\"" + target + "\"}"));
        assertNotFound(send(gm, "DELETE", "/api/v1/campaigns/" + target));
        assertNotFound(get(gm, "/api/v1/campaigns/" + target + "/conversation"));
        assertNotFound(ask(gm, target));
        for (String list : List.of("/api/v1/campaigns", "/api/v1/campaigns?archived=true",
                "/api/v1/campaigns?system=" + gm.system())) {
            assertThat(ids(json(get(gm, list)).path("items"))).as(list).doesNotContain(target.toString());
        }
        assertThat(ids(json(get(gm, "/api/v1/campaigns")).path("items")))
                .containsExactlyInAnyOrder(sibling.toString(), gm.campaign().toString());
        // The current campaign falls back: the one chosen last before it.
        JsonNode current = json(get(gm, "/api/v1/campaigns/current"));
        assertThat(current.path("id").asString()).isEqualTo(sibling.toString());
        assertThat(json(get(gm, "/api/v1/conversations/current")).path("id").asString())
                .isNotEqualTo(conversations.turns.get(firstTurn).conversationId().toString());
        // An upload naming it is refused as one naming an unknown campaign.
        HttpResponse<String> unknownUpload = upload(gm, Ids.newId());
        HttpResponse<String> deletingUpload = upload(gm, target);
        assertThat(deletingUpload.statusCode()).isEqualTo(400).isEqualTo(unknownUpload.statusCode());
        assertThat(json(deletingUpload).path("code").asString()).isEqualTo(json(unknownUpload).path("code").asString())
                .isEqualTo("upload.metadata_invalid");
        // The system's preview no longer counts it.
        assertThat(json(get(gm, "/api/v1/systems/" + gm.system() + "/deletion")).path("campaigns").asLong())
                .isEqualTo(2);
        // Its versions are hidden and its answers' jobs cancelled; the others are untouched.
        for (Made going : List.of(ready, reviewing)) {
            assertThat(materials.versions.get(going.versionId()).state()).isEqualTo("discarded");
        }
        assertThat(materials.versions.get(ofSibling.versionId()).state()).isEqualTo("ready");
        assertThat(materials.versions.get(ofSystem.versionId()).state()).isEqualTo("ready");
        assertThat(jobs.rows.get(conversations.turns.get(firstTurn).jobId()).state()).isEqualTo("cancelled");
        assertThat(jobs.rows.get(conversations.turns.get(secondTurn).jobId()).state()).isEqualTo("cancelled");
        assertThat(jobs.rows.get(conversations.turns.get(siblingTurn).jobId()).state()).isNotEqualTo("cancelled");
        assertThat(jobs.rows.get(searches.searches.get(targetSearch).jobId()).state()).isEqualTo("cancelled");
        assertThat(jobs.rows.get(searches.searches.get(siblingSearch).jobId()).state()).isNotEqualTo("cancelled");
        assertNotFound(get(gm, "/api/v1/searches/" + targetSearch));
        assertThat(get(gm, "/api/v1/searches/" + siblingSearch).statusCode()).isEqualTo(200);
        // The same name is free at once in the same system.
        assertThat(post(gm, "/api/v1/campaigns", Map.of("name", "Mesa de sexta", "systemId", gm.system().toString()))
                .statusCode()).isEqualTo(201);
        DeletionRow row = deletions.rows.values().stream().filter(found -> found.targetId().equals(target)).findFirst()
                .orElseThrow();
        assertThat(row.kind()).isEqualTo(DeletionRow.CAMPAIGN);
        assertThat(row.ownerId()).isEqualTo(gm.id());
        assertThat(row.notBefore()).isEqualTo(clock.instant().truncatedTo(ChronoUnit.MICROS));
        // Nothing is removed before the removal runs.
        assertThat(library.campaigns).containsKey(target);
        assertThat(materials.materials).containsKey(ready.materialId());

        assertThat(deletion.sweep()).isEqualTo(1);

        assertThat(library.campaigns).doesNotContainKey(target).containsKeys(sibling, gm.campaign());
        // Story 6.1: the target's Shields went before its row (their key names it); the others' stay.
        assertThat(library.shields.values()).noneMatch(shield -> shield.campaignId().equals(target))
                .anyMatch(shield -> shield.campaignId().equals(sibling))
                .anyMatch(shield -> shield.campaignId().equals(gm.campaign()));
        // Story 3.5: the target's scope settings went with it; the sibling's stay.
        assertThat(scopes.rows).doesNotContainKey(target).containsKey(sibling);
        assertThat(scopes.rows.get(sibling).scope().materialsOff()).containsExactly(ofSystem.materialId());
        assertThat(library.systems).containsKey(gm.system());
        assertThat(conversations.conversations.values()).noneMatch(conversation ->
                conversation.campaignId().equals(target));
        assertThat(conversations.turns).doesNotContainKeys(firstTurn, secondTurn).containsKey(siblingTurn);
        assertThat(references.rows.values()).noneMatch(reference ->
                List.of(firstTurn, secondTurn).contains(reference.turnId()));
        assertThat(searches.searches).doesNotContainKey(targetSearch).containsKey(siblingSearch);
        assertThat(materials.materials).doesNotContainKeys(ready.materialId(), reviewing.materialId(),
                hidden.materialId()).containsKeys(ofSibling.materialId(), ofSystem.materialId());
        assertThat(materials.systems.values()).filteredOn(link -> link.materialId().equals(ofSystem.materialId()))
                .extracting(MaterialSystemRow::systemId).containsExactly(gm.system());
        for (UUID version : List.of(ready.versionId(), reviewing.versionId(), hidden.versionId(), discardedBefore)) {
            assertThat(materials.versions).doesNotContainKey(version);
            assertThat(index.removals.values()).as("one index removal per version")
                    .filteredOn(removal -> removal.versionId().equals(version)).hasSize(1);
            assertThat(materials.passages.values()).noneMatch(passage -> passage.versionId().equals(version));
            assertThat(materials.pageViews.values()).noneMatch(view -> view.versionId().equals(version));
            assertThat(materials.files.values()).noneMatch(file -> file.versionId().equals(version));
        }
        assertThat(ready.files()).isNotEmpty()
                .allSatisfy(file -> assertThat(files.fileExists(gm.id(), file)).isFalse());
        assertThat(ofSibling.files()).isNotEmpty()
                .allSatisfy(file -> assertThat(files.fileExists(gm.id(), file)).isTrue());
        assertThat(ofSystem.files()).allSatisfy(file -> assertThat(files.fileExists(gm.id(), file)).isTrue());
        assertThat(index.removals.values()).noneMatch(removal -> removal.versionId().equals(ofSibling.versionId())
                || removal.versionId().equals(ofSystem.versionId()));
        assertThat(deletions.rows.get(row.id()).doneAt()).isNotNull();
        assertThat(get(gm, "/api/v1/campaigns/" + sibling).statusCode()).isEqualTo(200);
        assertNotFound(get(gm, "/api/v1/campaigns/" + target));

        // Again: nothing to do, and no second index removal.
        int removals = index.removals.size();
        assertThat(deletion.sweep()).isZero();
        assertThat(index.removals).hasSize(removals);
    }

    @Test
    void anArchivedCampaignAndThenTheLastCampaignAreDeletedAndNoCampaignIsLeft() throws Exception {
        Gm gm = signUp();
        tick();
        UUID archived = createCampaign(gm, gm.system(), "Mesa antiga");
        assertThat(send(gm, "PATCH", "/api/v1/campaigns/" + archived, "{\"version\":0,\"archived\":true}")
                .statusCode()).isEqualTo(200);
        assertThat(json(get(gm, "/api/v1/campaigns/" + archived + "/deletion")).path("conversations").asLong()).isZero();

        assertThat(send(gm, "DELETE", "/api/v1/campaigns/" + archived).statusCode()).isEqualTo(202);
        assertThat(json(get(gm, "/api/v1/campaigns?archived=true")).path("items")).isEmpty();
        assertThat(json(get(gm, "/api/v1/campaigns/current")).path("id").asString()).isEqualTo(gm.campaign().toString());

        // The current campaign, and the GM's last.
        turnId(ask(gm, gm.campaign()));
        assertThat(send(gm, "DELETE", "/api/v1/campaigns/" + gm.campaign()).statusCode()).isEqualTo(202);
        assertNotFound(get(gm, "/api/v1/campaigns/current"));
        assertNotFound(get(gm, "/api/v1/conversations/current"));
        assertThat(json(get(gm, "/api/v1/campaigns")).path("items")).isEmpty();
        // Its system stays, with no campaign.
        assertThat(get(gm, "/api/v1/systems/" + gm.system()).statusCode()).isEqualTo(200);
        assertThat(json(get(gm, "/api/v1/systems/" + gm.system() + "/deletion")).path("campaigns").asLong()).isZero();

        assertThat(deletion.sweep()).isEqualTo(2);
        assertThat(library.campaigns).doesNotContainKeys(archived, gm.campaign());
        assertThat(library.systems).containsKey(gm.system());
        assertThat(conversations.conversations.values()).noneMatch(conversation ->
                conversation.campaignId().equals(gm.campaign()));
    }

    @Test
    void aRunningAnswerMakesTheRemovalWaitForItsClaim() throws Exception {
        Gm gm = signUp();
        UUID turn = turnId(ask(gm, gm.campaign()));
        UUID job = conversations.turns.get(turn).jobId();
        JsonNode claim = claim(job);
        Instant lapses = Instant.parse(claim.path("lapsesAt").asString());
        assertThat(lapses).isAfter(START);

        assertThat(send(gm, "DELETE", "/api/v1/campaigns/" + gm.campaign()).statusCode()).isEqualTo(202);

        assertThat(jobs.rows.get(job).state()).isEqualTo("cancelled");
        DeletionRow row = deletions.rows.values().stream().filter(found -> found.targetId().equals(gm.campaign()))
                .findFirst().orElseThrow();
        assertThat(row.notBefore()).isEqualTo(lapses.truncatedTo(ChronoUnit.MICROS));
        assertThat(deletion.sweep()).isZero();
        assertThat(deletion.run(gm.id(), row.id())).isFalse();
        assertThat(conversations.turns).containsKey(turn);
        assertThat(library.campaigns).containsKey(gm.campaign());

        clock.set(lapses.plus(Duration.ofSeconds(1)));
        assertThat(deletion.sweep()).isEqualTo(1);

        assertThat(conversations.turns).doesNotContainKey(turn);
        assertThat(library.campaigns).doesNotContainKey(gm.campaign());
        assertThat(library.systems).containsKey(gm.system());
    }

    @Test
    void anotherOwnersCampaignIsNotFoundOnBothRoutesAndNothingChanges() throws Exception {
        Gm owner = signUp();
        Gm other = signUp();

        assertNotFound(get(other, "/api/v1/campaigns/" + owner.campaign() + "/deletion"));
        assertNotFound(send(other, "DELETE", "/api/v1/campaigns/" + owner.campaign()));
        assertNotFound(send(owner, "DELETE", "/api/v1/campaigns/not-an-id"));
        assertNotFound(send(owner, "DELETE", "/api/v1/campaigns/" + Ids.newId()));
        assertNotFound(get(owner, "/api/v1/campaigns/" + Ids.newId() + "/deletion"));

        assertThat(library.deletingCampaigns).doesNotContain(owner.campaign());
        assertThat(deletions.rows.values()).noneMatch(row -> row.targetId().equals(owner.campaign()));
        assertThat(get(owner, "/api/v1/campaigns/" + owner.campaign()).statusCode()).isEqualTo(200);
        assertThat(json(get(owner, "/api/v1/campaigns/current")).path("id").asString())
                .isEqualTo(owner.campaign().toString());
    }

    // --- Story 5.6 part A: shares into and out of a campaign going

    @Test
    void deletingACampaignAnArtifactIsSharedIntoRemovesThoseSharesAndDeletingItsOwnCampaignRemovesItsShares()
            throws Exception {
        Gm gm = signUp();
        UUID quinta = createCampaign(gm, gm.system(), "Mesa de quinta");
        UUID domingo = createCampaign(gm, gm.system(), "Mesa de domingo");
        String kael = artifactIn(gm, gm.campaign(), "Espada de Kael");
        String arco = artifactIn(gm, domingo, "Arco de Lia");
        String kaelPath = "/api/v1/campaigns/" + gm.campaign() + "/artifacts/" + kael;
        String arcoPath = "/api/v1/campaigns/" + domingo + "/artifacts/" + arco;
        assertThat(share(gm, kaelPath, quinta, domingo).statusCode()).isEqualTo(200);
        assertThat(share(gm, arcoPath, quinta).statusCode()).isEqualTo(200);

        // Matrix row "Deleted" (B deleted): from the delete on, it is no target and not listed; then its pass removes
        // the share into it, before the campaign row.
        assertThat(send(gm, "DELETE", "/api/v1/campaigns/" + quinta).statusCode()).isEqualTo(202);
        assertThat(json(get(gm, kaelPath)).path("sharedWith")).extracting(one -> one.path("campaignId").asString())
                .containsExactly(domingo.toString());
        HttpResponse<String> refused = send(gm, "PUT", kaelPath + "/sharing", "{\"campaignIds\":[\"" + quinta
                + "\"]}");
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(deletion.sweep()).isEqualTo(1);
        assertThat(library.campaigns).doesNotContainKey(quinta);
        assertThat(shares.rows.values()).noneMatch(row -> row.campaignId().equals(quinta));
        assertThat(shares.rows.values()).extracting(row -> row.artifactId().toString() + ">" + row.campaignId())
                .containsExactly(kael + ">" + domingo);

        // The artifact's own campaign deleted: its shares go with it, and the campaign it was shared into reads it no
        // more from the delete on.
        String there = "/api/v1/campaigns/" + domingo + "/artifacts/" + kael;
        assertThat(get(gm, there).statusCode()).isEqualTo(200);
        assertThat(send(gm, "DELETE", "/api/v1/campaigns/" + gm.campaign()).statusCode()).isEqualTo(202);
        assertNotFound(get(gm, there));
        assertThat(deletion.sweep()).isEqualTo(1);
        assertThat(shares.rows.values()).noneMatch(row -> row.artifactId().toString().equals(kael));
        assertThat(get(gm, arcoPath).statusCode()).isEqualTo(200);
    }

    // --- helpers

    /** A new artifact of the GM's campaign (Story 5.6 part A); its id. */
    private String artifactIn(Gm gm, UUID campaign, String name) throws Exception {
        HttpResponse<String> created = post(gm, "/api/v1/campaigns/" + campaign + "/artifacts", Map.of("recordType",
                "item", "name", name, "fields", List.of()));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return json(created).path("id").asString();
    }

    /** The GM's sharing of the artifact at {@code path} with exactly these campaigns (Story 5.6 part A). */
    private HttpResponse<String> share(Gm gm, String path, UUID... campaigns) throws Exception {
        return send(gm, "PUT", path + "/sharing", JSON.writeValueAsString(Map.of("campaignIds",
                Arrays.stream(campaigns).map(UUID::toString).toList())));
    }

    /** The clock moves on, so what is chosen or made next comes later. */
    private void tick() {
        clock.set(clock.instant().plusSeconds(1));
    }

    private UUID createCampaign(Gm gm, UUID system, String name) throws Exception {
        HttpResponse<String> created = post(gm, "/api/v1/campaigns", Map.of("name", name, "systemId", system.toString()));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        Thread.sleep(2); // ids made in the same millisecond sort randomly (Ids)
        return UUID.fromString(json(created).path("id").asString());
    }

    private static List<String> ids(JsonNode items) {
        List<String> found = new java.util.ArrayList<>();
        items.forEach(item -> found.add(item.path("id").asString()));
        return found;
    }

    /** A new upload whose details name the campaign. */
    private HttpResponse<String> upload(Gm gm, UUID campaign) throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/api/v1/uploads")).header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Session-Token", gm.session()).header("Tus-Resumable", "1.0.0").header("Upload-Length", "10")
                .header("Upload-Metadata", "filename " + b64("a.pdf") + ",campaign " + b64(campaign.toString()))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** A material of the campaign or attached to the systems, its version in {@code state}, with picture pages. */
    private Made material(Gm gm, UUID campaign, List<UUID> systems, String state, int pages) {
        return as(gm.id(), () -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            UUID materialId = Ids.newId();
            materials.insertMaterial(new MaterialRow(materialId, gm.id(),
                    TestMaterials.materialName(vault, materialId, gm.id(), "Livro"), now, "rulebook", null, campaign));
            for (UUID system : systems) {
                materials.insertMaterialSystem(new MaterialSystemRow(Ids.newId(), gm.id(), materialId, system, now));
            }
            UUID versionId = Ids.newId();
            materials.insertVersion(new VersionRow(versionId, gm.id(), materialId, state, "pdf", 1000L, now, now,
                    pages, "5", null));
            for (int page = 1; page <= pages; page++) {
                TestMaterials.picturePage(materials, vault, files, gm.id(), versionId, page, new byte[] {1, 2, 3}, 10,
                        10, List.of(), now);
                materials.insertPassages(List.of(TestMaterials.passage(vault, Ids.newId(), gm.id(), versionId,
                        page - 1, page, "Texto", List.of("A"), now)));
            }
            List<UUID> stored = materials.files.values().stream().filter(file -> file.versionId().equals(versionId))
                    .map(VersionFileRow::fileId).toList();
            return new Made(materialId, versionId, stored);
        });
    }

    /** Another, older version of the material, in {@code state}. */
    private UUID version(Gm gm, UUID materialId, String state) {
        return as(gm.id(), () -> {
            Instant earlier = clock.instant().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
            UUID versionId = Ids.newId();
            materials.insertVersion(new VersionRow(versionId, gm.id(), materialId, state, "pdf", 1000L, earlier,
                    earlier));
            return versionId;
        });
    }

    private <T> T as(UUID owner, Supplier<T> work) {
        return OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status -> work.get()));
    }

    private HttpResponse<String> ask(Gm gm, UUID campaign) throws Exception {
        return send(gm, "POST", "/api/v1/campaigns/" + campaign + "/turns", "{\"question\":\"Quanto dano?\"}");
    }

    /** A search of the campaign (Story 3.9); answers its id. */
    private UUID searchId(Gm gm, UUID campaign) throws Exception {
        HttpResponse<String> created = send(gm, "POST", "/api/v1/campaigns/" + campaign + "/searches",
                "{\"query\":\"Quanto dano\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(202);
        return UUID.fromString(json(created).path("id").asString());
    }

    private static UUID turnId(HttpResponse<String> asked) {
        assertThat(asked.statusCode()).as(asked.body()).isEqualTo(201);
        return UUID.fromString(json(asked).path("id").asString());
    }

    private JsonNode claim(UUID jobId) throws Exception {
        HttpResponse<String> claimed = sendAsWorker("POST", "/internal/v1/jobs/" + jobId + "/claims", null);
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(201);
        return json(claimed);
    }

    private static void assertNotFound(HttpResponse<String> answer) {
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(404);
        assertThat(json(answer).path("code").asString()).isEqualTo("request.not_found");
    }

    private HttpResponse<String> get(Gm gm, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Session-Token", gm.session()).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(Gm gm, String method, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Session-Token", gm.session()).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(Gm gm, String method, String path, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Session-Token", gm.session()).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Gm gm, String path, Map<String, Object> body) throws Exception {
        return send(gm, "POST", path, JSON.writeValueAsString(body));
    }

    private HttpResponse<String> sendAsWorker(String method, String path, String grant) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + WORKER_CREDENTIAL).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(grant == null
                        ? "{\"providers\":[\"azure-openai\"]}" : "{}"));
        if (grant != null) {
            request.header("X-Job-Grant", grant);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private Gm signUp() throws Exception {
        String sub = "del-" + UUID.randomUUID();
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
