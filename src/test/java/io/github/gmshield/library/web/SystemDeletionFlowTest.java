package io.github.gmshield.library.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.accounts.data.AccountRow;
import io.github.gmshield.accounts.data.AccountStore;
import io.github.gmshield.accounts.data.InMemoryAccountStore;
import io.github.gmshield.accounts.data.InMemorySessionStore;
import io.github.gmshield.accounts.data.InMemorySignInAttemptStore;
import io.github.gmshield.accounts.data.SessionStore;
import io.github.gmshield.accounts.data.SignInAttemptStore;
import io.github.gmshield.audit.data.AuditStore;
import io.github.gmshield.audit.data.InMemoryAuditStore;
import io.github.gmshield.conversations.data.ConversationTestStores;
import io.github.gmshield.conversations.data.InMemoryConversationStore;
import io.github.gmshield.conversations.data.InMemoryTurnReferenceStore;
import io.github.gmshield.jobs.data.InMemoryJobStorage;
import io.github.gmshield.jobs.data.InMemoryJobStream;
import io.github.gmshield.jobs.data.JobMemoryStores;
import io.github.gmshield.jobs.data.MutableClock;
import io.github.gmshield.library.data.CampaignRow;
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
 * Story 3.1 part B without a database or Redis: every backend row of its I/O matrix. What a system's deletion names, the
 * delete (202) after which the system and its campaigns are "not found" on every route, another owner's system on both
 * routes, a second delete, the removal of what goes (campaigns, conversations, the materials attached only to it, their
 * files, one {@code index.removal} per version) and of nothing else (a material shared with another system stays,
 * attached to it), the removal waiting for a running claim to lapse, and the last campaign gone. The same rows against
 * PostgreSQL are in {@code SystemDeletionIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GMSHIELD_MODE=test")
@ActiveProfiles({"test-mode", "unit"})
class SystemDeletionFlowTest {

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
    private InMemoryJobStorage jobs;
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
    void theDialogsCountsThenTheDeleteHidesEverythingAndTheRemovalLeavesOnlyWhatStays() throws Exception {
        Gm gm = signUp();
        UUID system = createSystem(gm, "Tormenta");
        UUID other = createSystem(gm, "Old Dragon");
        UUID first = campaign(gm, system, "Mesa de sexta");
        UUID second = campaign(gm, system, "Mesa de sábado");
        UUID kept = campaign(gm, other, "Mesa de domingo");
        UUID firstTurn = turnId(ask(gm, first));
        UUID secondTurn = turnId(ask(gm, second));
        turnId(ask(gm, kept));
        Made ofCampaign = material(gm, first, List.of(), "ready", 2);
        Made ofSystemAlone = material(gm, null, List.of(system), "needs_review", 1);
        Made shared = material(gm, null, List.of(system, other), "ready", 1);
        Made starter = material(gm, gm.campaign(), List.of(), "ready", 1);
        // A version the GM discarded before: it has its index removal already, and gets no second one.
        UUID discardedBefore = version(gm, ofCampaign.materialId(), "discarded");
        as(gm.id(), () -> {
            index.insertRemoval(new IndexRemovalRow(Ids.newId(), gm.id(), discardedBefore));
            return null;
        });

        JsonNode counts = json(get(gm, "/api/v1/systems/" + system + "/deletion"));
        assertThat(counts.path("campaigns").asLong()).isEqualTo(2);
        assertThat(counts.path("conversations").asLong()).isEqualTo(2);
        assertThat(counts.path("materials").asLong()).isEqualTo(2);
        assertThat(get(gm, "/api/v1/systems/" + system + "/deletion").headers().firstValue("Cache-Control"))
                .hasValue("no-store");

        HttpResponse<String> deleted = send(gm, "DELETE", "/api/v1/systems/" + system);
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(202);
        assertThat(deleted.body()).isEmpty();

        // From the commit: not found everywhere, and gone from every list.
        List<String> listed = new java.util.ArrayList<>();
        json(get(gm, "/api/v1/systems")).path("items").forEach(item -> listed.add(item.path("id").asString()));
        assertThat(listed).doesNotContain(system.toString()).contains(other.toString(), gm.system().toString());
        assertNotFound(get(gm, "/api/v1/systems/" + system));
        assertNotFound(get(gm, "/api/v1/systems/" + system + "/deletion"));
        assertNotFound(send(gm, "PATCH", "/api/v1/systems/" + system, "{\"version\":1,\"name\":\"X\"}"));
        assertNotFound(send(gm, "DELETE", "/api/v1/systems/" + system));
        assertNotFound(get(gm, "/api/v1/campaigns?system=" + system));
        assertNotFound(get(gm, "/api/v1/campaigns/" + first));
        assertNotFound(get(gm, "/api/v1/materials?state=ready&system=" + system));
        assertNotFound(ask(gm, second));
        assertThat(materials.versions.get(ofCampaign.versionId()).state()).isEqualTo("discarded");
        assertThat(materials.versions.get(ofSystemAlone.versionId()).state()).isEqualTo("discarded");
        assertThat(materials.versions.get(shared.versionId()).state()).isEqualTo("ready");
        assertThat(jobs.rows.get(conversations.turns.get(firstTurn).jobId()).state()).isEqualTo("cancelled");
        assertThat(jobs.rows.get(conversations.turns.get(secondTurn).jobId()).state()).isEqualTo("cancelled");
        // The same name is free at once.
        assertThat(post(gm, "/api/v1/systems", Map.of("name", "Tormenta")).statusCode()).isEqualTo(201);
        // Nothing was waiting on a claim: the removal is due now. Nothing is removed before it runs.
        DeletionRow row = deletions.rows.values().stream().filter(found -> found.targetId().equals(system)).findFirst()
                .orElseThrow();
        assertThat(row.notBefore()).isEqualTo(START);
        assertThat(row.ownerId()).isEqualTo(gm.id());
        assertThat(materials.materials).containsKey(ofCampaign.materialId());

        assertThat(deletion.sweep()).isEqualTo(1);

        assertThat(library.systems).doesNotContainKey(system).containsKeys(other, gm.system());
        assertThat(library.campaigns).doesNotContainKeys(first, second).containsKeys(kept, gm.campaign());
        assertThat(conversations.conversations.values()).noneMatch(conversation ->
                List.of(first, second).contains(conversation.campaignId()));
        assertThat(conversations.turns).doesNotContainKeys(firstTurn, secondTurn).hasSize(1);
        assertThat(materials.materials).doesNotContainKeys(ofCampaign.materialId(), ofSystemAlone.materialId())
                .containsKeys(shared.materialId(), starter.materialId());
        assertThat(materials.versions).doesNotContainKeys(ofCampaign.versionId(), ofSystemAlone.versionId(),
                discardedBefore);
        assertThat(materials.systems.values()).filteredOn(link -> link.materialId().equals(shared.materialId()))
                .extracting(MaterialSystemRow::systemId).containsExactly(other);
        for (UUID version : List.of(ofCampaign.versionId(), ofSystemAlone.versionId(), discardedBefore)) {
            assertThat(index.removals.values()).as("one index removal per version")
                    .filteredOn(removal -> removal.versionId().equals(version)).hasSize(1);
            assertThat(materials.passages.values()).noneMatch(passage -> passage.versionId().equals(version));
            assertThat(materials.pageViews.values()).noneMatch(view -> view.versionId().equals(version));
            assertThat(materials.files.values()).noneMatch(file -> file.versionId().equals(version));
        }
        assertThat(ofCampaign.files()).isNotEmpty()
                .allSatisfy(file -> assertThat(files.fileExists(gm.id(), file)).isFalse());
        assertThat(shared.files()).allSatisfy(file -> assertThat(files.fileExists(gm.id(), file)).isTrue());
        assertThat(deletions.rows.get(row.id()).doneAt()).isNotNull();
        assertThat(get(gm, "/api/v1/campaigns/" + kept).statusCode()).isEqualTo(200);

        // Again: nothing to do, and no second index removal.
        int removals = index.removals.size();
        assertThat(deletion.sweep()).isZero();
        assertThat(index.removals).hasSize(removals);
    }

    @Test
    void anotherOwnersSystemIsNotFoundOnBothRoutesAndNothingChanges() throws Exception {
        Gm owner = signUp();
        Gm other = signUp();

        assertNotFound(get(other, "/api/v1/systems/" + owner.system() + "/deletion"));
        assertNotFound(send(other, "DELETE", "/api/v1/systems/" + owner.system()));
        assertNotFound(send(owner, "DELETE", "/api/v1/systems/not-an-id"));
        assertNotFound(send(owner, "DELETE", "/api/v1/systems/" + Ids.newId()));

        assertThat(library.deleting).doesNotContain(owner.system());
        assertThat(deletions.rows.values()).noneMatch(row -> row.targetId().equals(owner.system()));
        assertThat(get(owner, "/api/v1/systems/" + owner.system()).statusCode()).isEqualTo(200);
        assertThat(json(get(owner, "/api/v1/systems/" + owner.system() + "/deletion")).path("campaigns").asLong())
                .isEqualTo(1);
    }

    @Test
    void aRunningAnswerMakesTheRemovalWaitForItsClaimAndTheLastCampaignGoes() throws Exception {
        Gm gm = signUp();
        UUID turn = turnId(ask(gm, gm.campaign()));
        UUID job = conversations.turns.get(turn).jobId();
        JsonNode claim = claim(job);
        Instant lapses = Instant.parse(claim.path("lapsesAt").asString());
        assertThat(lapses).isAfter(START);

        assertThat(send(gm, "DELETE", "/api/v1/systems/" + gm.system()).statusCode()).isEqualTo(202);

        // The only system held the only campaign: there is no campaign now.
        assertNotFound(get(gm, "/api/v1/campaigns/current"));
        assertNotFound(get(gm, "/api/v1/conversations/current"));
        assertThat(json(get(gm, "/api/v1/systems")).path("items")).isEmpty();
        assertThat(jobs.rows.get(job).state()).isEqualTo("cancelled");
        DeletionRow row = deletions.rows.values().stream().filter(found -> found.targetId().equals(gm.system()))
                .findFirst().orElseThrow();
        assertThat(row.notBefore()).isEqualTo(lapses.truncatedTo(ChronoUnit.MICROS));
        // A worker still holding the cancelled claim is refused at once.
        HttpResponse<String> renewal = sendAsWorker("POST", "/internal/v1/jobs/" + job + "/claims/"
                + claim.path("claimId").asString() + "/renewals", claim.path("grant").asString());
        assertThat(renewal.statusCode()).isEqualTo(403);

        assertThat(deletion.sweep()).isZero();
        assertThat(deletion.run(gm.id(), row.id())).isFalse();
        assertThat(conversations.turns).containsKey(turn);
        assertThat(library.campaigns).containsKey(gm.campaign());

        clock.set(lapses.plus(Duration.ofSeconds(1)));
        assertThat(deletion.sweep()).isEqualTo(1);

        assertThat(conversations.turns).doesNotContainKey(turn);
        assertThat(library.campaigns).doesNotContainKey(gm.campaign());
        assertThat(library.systems).doesNotContainKey(gm.system());
        assertThat(references.rows.values()).noneMatch(reference -> reference.turnId().equals(turn));
    }

    // --- helpers

    private UUID createSystem(Gm gm, String name) throws Exception {
        HttpResponse<String> created = post(gm, "/api/v1/systems", Map.of("name", name));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(json(created).path("id").asString());
    }

    /** A campaign of the system, as Story 3.2 will create one. */
    private UUID campaign(Gm gm, UUID system, String name) throws Exception {
        UUID id = Ids.newId();
        Thread.sleep(2); // ids made in the same millisecond sort randomly (Ids)
        as(gm.id(), () -> {
            library.insertCampaign(new CampaignRow(id, gm.id(), system,
                    vault.encryptText(new Binding("campaign", "name", id, gm.id()), name), clock.instant()));
            return null;
        });
        return id;
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
