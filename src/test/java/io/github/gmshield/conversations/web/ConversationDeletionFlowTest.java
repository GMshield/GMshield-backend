package io.github.gmshield.conversations.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.accounts.data.AccountRow;
import io.github.gmshield.accounts.data.AccountStore;
import io.github.gmshield.accounts.data.InMemoryAccountStore;
import io.github.gmshield.accounts.data.InMemorySessionStore;
import io.github.gmshield.accounts.data.InMemorySignInAttemptStore;
import io.github.gmshield.accounts.data.SessionStore;
import io.github.gmshield.accounts.data.SignInAttemptStore;
import io.github.gmshield.artifacts.data.InMemoryArtifactVersionStore;
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
import io.github.gmshield.knowledge.data.InMemoryRecordStore;
import io.github.gmshield.library.data.DeletionRow;
import io.github.gmshield.library.data.InMemoryDeletionStore;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.data.LibraryTestStores;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.materials.data.InMemoryMaterialStore;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialTestStores;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.materials.service.TestMaterials;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.data.DataKeyStore;
import io.github.gmshield.vault.data.InMemoryDataKeyStore;
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
import java.util.ArrayList;
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
 * Story 3.10 part B without a database or Redis: every row of its I/O matrix. What a conversation's deletion names (its
 * questions), the delete (202) after which the conversation is "not found" on every route (its list and total, GET,
 * turns, PATCH, the preview, a second delete, a question naming it, its turns' events, stop and reference pages) and
 * gone from the campaign's counts, the area-less question then continuing the next latest conversation; the removal
 * of the conversation with its turns, event batches and references, and of nothing else (the other conversation, its
 * campaign, the searches, the materials, the usage); the removal waiting for a running answer's claim; a removal
 * resumed by the sweep after a stop mid-way; a campaign deleted meanwhile, after which both deletions end done; another
 * owner's conversation on both routes. The removal against PostgreSQL is in
 * {@code ConversationDeletionIntegrationTest}. Story 3.11: a deleting conversation's turns are not found on both
 * dismissal routes, and a dismissed turn goes with its conversation. Story 3.12: the same on both bookmark routes; a
 * saved turn leaves the saved list when its conversation or campaign starts being deleted, and goes with its row; a
 * conversation marked deleting while the list is read is left out of that page and its total. Story 5.4 part B: every
 * row of its matrix (keep all, delete some, select all, none saved, a foreign id, one gone meanwhile, a bad body, not
 * found, another owner), the dialog's list (oldest first, newest names, no deleting artifact) and a kept artifact's
 * conversation null from the delete on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GMSHIELD_MODE=test")
@ActiveProfiles({"test-mode", "unit"})
class ConversationDeletionFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CREDENTIAL = "gmshield-test-mode-frontend-credential-not-a-secret";
    private static final String WORKER_CREDENTIAL = "gmshield-test-mode-worker-credential-not-a-secret";
    private static final Instant START = Instant.parse("2026-11-22T12:00:00Z");

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
    private InMemoryConversationStore conversations;
    @Autowired
    private InMemoryRecordStore records;
    @Autowired
    private InMemoryArtifactVersionStore artifactVersions;
    @Autowired
    private InMemoryTurnReferenceStore references;
    @Autowired
    private InMemorySearchStore searches;
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
    private PlatformTransactionManager transactions;

    private final HttpClient client = HttpClient.newHttpClient();

    record Gm(UUID id, String session, UUID campaign, UUID system) {
    }

    record Claim(UUID jobId, String claimId, String grant) {

        String path(String action) {
            return "/internal/v1/jobs/" + jobId + "/claims/" + claimId + "/" + action;
        }
    }

    /** A material of the campaign and its version, with one passage. */
    record Source(UUID materialId, UUID versionId, UUID passageId) {
    }

    @BeforeEach
    void start() {
        clock.set(START);
        stream.clear();
    }

    @Test
    void theDialogsCountThenTheDeleteHidesTheConversationEverywhereAndTheRemovalTakesItAlone() throws Exception {
        Gm gm = signUp();
        Source source = source(gm);
        Source switchedOff = source(gm);
        // The other conversation, asked first: the target becomes the campaign's latest questions conversation.
        UUID otherTurn = answered(gm, null, "questions", source);
        UUID other = conversations.turns.get(otherTurn).conversationId();
        tick();
        UUID firstTurn = answered(gm, null, "questions", source);
        UUID target = conversations.turns.get(firstTurn).conversationId();
        tick();
        // A turn with a material switched off (turn_material_off).
        assertThat(send(gm, "PUT", "/api/v1/campaigns/" + gm.campaign() + "/scope", "{\"version\":0,"
                + "\"campaignTier\":true,\"systemTier\":true,\"otherTier\":false,\"modelKnowledge\":false,"
                + "\"materialsOff\":[\"" + switchedOff.materialId() + "\"]}").statusCode()).isEqualTo(200);
        UUID secondTurn = answered(gm, target, null, source);
        tick();
        UUID thirdTurn = answered(gm, target, null, source);
        List<UUID> targetTurns = List.of(firstTurn, secondTurn, thirdTurn);
        assertThat(conversations.turns.get(secondTurn).materialsOff()).containsExactly(switchedOff.materialId());
        assertThat(references.rows.values()).filteredOn(row -> targetTurns.contains(row.turnId())).hasSize(3);
        assertThat(conversations.batches.values()).filteredOn(row -> targetTurns.contains(row.turnId())).isNotEmpty();
        UUID search = searchId(gm);
        assertThat(json(get(gm, "/api/v1/campaigns/" + gm.campaign() + "/conversation")).path("id").asString())
                .isEqualTo(target.toString());
        assertThat(json(get(gm, "/api/v1/campaigns/" + gm.campaign() + "/deletion")).path("conversations").asLong())
                .isEqualTo(2);
        assertThat(json(get(gm, listPath(gm))).path("total").asLong()).isEqualTo(2);

        HttpResponse<String> preview = get(gm, "/api/v1/conversations/" + target + "/deletion");
        assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
        assertThat(preview.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(json(preview).path("questions").asLong()).isEqualTo(3);

        HttpResponse<String> deleted = send(gm, "DELETE", "/api/v1/conversations/" + target);
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(202);
        assertThat(deleted.body()).isEmpty();
        assertThat(deleted.headers().firstValue("Cache-Control")).hasValue("no-store");

        // From the commit: not found on every route, gone from the list and from the campaign's counts.
        assertNotFound(get(gm, "/api/v1/conversations/" + target));
        assertNotFound(get(gm, "/api/v1/conversations/" + target + "/turns"));
        assertNotFound(get(gm, "/api/v1/conversations/" + target + "/deletion"));
        assertNotFound(send(gm, "PATCH", "/api/v1/conversations/" + target, "{\"version\":0,\"name\":\"Outra\"}"));
        assertNotFound(send(gm, "DELETE", "/api/v1/conversations/" + target));
        assertNotFound(askIn(gm, "Ainda?", target, null));
        assertNotFound(get(gm, "/api/v1/turns/" + firstTurn + "/events"));
        assertNotFound(send(gm, "POST", "/api/v1/turns/" + firstTurn + "/stop"));
        assertNotFound(get(gm, "/api/v1/turns/" + firstTurn + "/references/1/pages/1"));
        assertNotFound(get(gm, "/api/v1/turns/" + firstTurn + "/references/1/pages/1/picture"));
        JsonNode list = json(get(gm, listPath(gm)));
        assertThat(ids(list.path("items"))).containsExactly(other.toString());
        assertThat(list.path("total").asLong()).isEqualTo(1);
        assertThat(json(get(gm, "/api/v1/campaigns/" + gm.campaign() + "/deletion")).path("conversations").asLong())
                .isEqualTo(1);
        JsonNode ofCampaign = json(get(gm, "/api/v1/campaigns/" + gm.campaign() + "/conversation"));
        assertThat(ofCampaign.path("id").asString()).isEqualTo(other.toString());
        assertThat(ofCampaign.path("questions").asLong()).isEqualTo(1);
        // The area-less question continues the next latest questions conversation.
        HttpResponse<String> areaLess = askIn(gm, "E agora?", null, null);
        assertThat(areaLess.statusCode()).as(areaLess.body()).isEqualTo(201);
        assertThat(json(areaLess).path("conversationId").asString()).isEqualTo(other.toString());
        UUID laterTurn = UUID.fromString(json(areaLess).path("id").asString());
        // The other conversation still answers.
        assertThat(get(gm, "/api/v1/conversations/" + other).statusCode()).isEqualTo(200);
        assertThat(json(get(gm, "/api/v1/conversations/" + other + "/turns")).path("items")).hasSize(2);
        DeletionRow row = deletions.rows.values().stream().filter(found -> found.targetId().equals(target))
                .findFirst().orElseThrow();
        assertThat(row.kind()).isEqualTo(DeletionRow.CONVERSATION);
        assertThat(row.ownerId()).isEqualTo(gm.id());
        assertThat(row.notBefore()).isEqualTo(clock.instant().truncatedTo(ChronoUnit.MICROS));
        // Nothing is removed before the removal runs.
        assertThat(conversations.conversations).containsKey(target);
        assertThat(conversations.deleting).containsExactly(target);
        assertThat(conversations.turns).containsKeys(firstTurn, secondTurn, thirdTurn);

        assertThat(deletion.sweep()).isEqualTo(1);

        assertThat(conversations.conversations).doesNotContainKey(target).containsKey(other);
        assertThat(conversations.deleting).isEmpty();
        assertThat(conversations.turns).doesNotContainKeys(firstTurn, secondTurn, thirdTurn)
                .containsKeys(otherTurn, laterTurn);
        assertThat(conversations.batches.values()).noneMatch(batch -> targetTurns.contains(batch.turnId()))
                .anyMatch(batch -> batch.turnId().equals(otherTurn));
        assertThat(references.rows.values()).noneMatch(reference -> targetTurns.contains(reference.turnId()))
                .anyMatch(reference -> reference.turnId().equals(otherTurn));
        assertThat(searches.searches).containsKey(search);
        assertThat(materials.materials).containsKeys(source.materialId(), switchedOff.materialId());
        assertThat(library.campaigns).containsKey(gm.campaign());
        assertThat(deletions.rows.get(row.id()).doneAt()).isNotNull();
        assertNotFound(get(gm, "/api/v1/conversations/" + target));

        // Again: nothing to do.
        assertThat(deletion.sweep()).isZero();
    }

    @Test
    void aRunningAnswerIsCancelledAndTheRemovalWaitsForItsClaim() throws Exception {
        Gm gm = signUp();
        HttpResponse<String> asked = askIn(gm, "Quanto dano?", null, "rules");
        UUID turn = UUID.fromString(json(asked).path("id").asString());
        UUID conversation = UUID.fromString(json(asked).path("conversationId").asString());
        UUID job = conversations.turns.get(turn).jobId();
        Claim claim = claim(job);
        Instant lapses = jobs.rows.get(job).claimLapsesAt();
        assertThat(lapses).isAfter(START);

        assertThat(send(gm, "DELETE", "/api/v1/conversations/" + conversation).statusCode()).isEqualTo(202);

        assertThat(jobs.rows.get(job).state()).isEqualTo("cancelled");
        DeletionRow row = deletions.rows.values().stream().filter(found -> found.targetId().equals(conversation))
                .findFirst().orElseThrow();
        assertThat(row.notBefore()).isEqualTo(lapses.truncatedTo(ChronoUnit.MICROS));
        assertThat(deletion.sweep()).isZero();
        assertThat(deletion.run(gm.id(), row.id())).isFalse();
        assertThat(conversations.turns).containsKey(turn);
        // The cancel ended the worker's claim: what it posts now is refused, and the conversation stays hidden.
        HttpResponse<String> late = sendAsWorker("POST", claim.path("events"), "{\"batch\":1,\"events\":[{\"type\":"
                + "\"delta\",\"text\":\"x\"}],\"usage\":[]}", claim.grant());
        assertThat(late.statusCode()).as(late.body()).isEqualTo(403);
        assertThat(json(late).path("code").asString()).isEqualTo("job.grant_refused");
        assertThat(conversations.batches.values()).noneMatch(batch -> batch.turnId().equals(turn));
        assertNotFound(get(gm, "/api/v1/conversations/" + conversation));

        clock.set(lapses.plus(Duration.ofSeconds(1)));
        assertThat(deletion.sweep()).isEqualTo(1);

        assertThat(conversations.turns).doesNotContainKey(turn);
        assertThat(conversations.conversations).doesNotContainKey(conversation);
        assertThat(conversations.batches.values()).noneMatch(batch -> batch.turnId().equals(turn));
        assertThat(deletions.rows.get(row.id()).doneAt()).isNotNull();
    }

    @Test
    void aRemovalStoppedMidWayIsFinishedByTheSweep() throws Exception {
        Gm gm = signUp();
        Source source = source(gm);
        UUID firstTurn = answered(gm, null, "questions", source);
        UUID conversation = conversations.turns.get(firstTurn).conversationId();
        UUID secondTurn = answered(gm, conversation, null, source);

        assertThat(send(gm, "DELETE", "/api/v1/conversations/" + conversation).statusCode()).isEqualTo(202);
        // The backend stopped after removing the first turn's references and the turn, before the rest.
        as(gm.id(), () -> {
            references.deleteReferences(firstTurn);
            return conversations.deleteTurn(firstTurn);
        });
        assertThat(conversations.turns).doesNotContainKey(firstTurn).containsKey(secondTurn);
        assertNotFound(get(gm, "/api/v1/conversations/" + conversation));

        assertThat(deletion.sweep()).isEqualTo(1);

        assertThat(conversations.turns).doesNotContainKeys(firstTurn, secondTurn);
        assertThat(conversations.conversations).doesNotContainKey(conversation);
        assertThat(references.rows.values()).noneMatch(reference -> reference.turnId().equals(secondTurn));
        assertThat(deletion.sweep()).isZero();
    }

    @Test
    void aCampaignDeletedMeanwhileTakesTheConversationAndBothDeletionsEndDone() throws Exception {
        Gm gm = signUp();
        UUID campaign = createCampaign(gm, "Mesa de domingo");
        HttpResponse<String> asked = send(gm, "POST", "/api/v1/campaigns/" + campaign + "/turns",
                "{\"question\":\"Quanto dano?\"}");
        UUID turn = UUID.fromString(json(asked).path("id").asString());
        UUID conversation = UUID.fromString(json(asked).path("conversationId").asString());

        assertThat(send(gm, "DELETE", "/api/v1/conversations/" + conversation).statusCode()).isEqualTo(202);
        // The campaign's preview no longer counts the deleting conversation; its deletion still takes it.
        assertThat(json(get(gm, "/api/v1/campaigns/" + campaign + "/deletion")).path("conversations").asLong())
                .isZero();
        assertThat(send(gm, "DELETE", "/api/v1/campaigns/" + campaign).statusCode()).isEqualTo(202);
        DeletionRow ofConversation = deletions.rows.values().stream()
                .filter(row -> row.targetId().equals(conversation)).findFirst().orElseThrow();
        DeletionRow ofCampaign = deletions.rows.values().stream()
                .filter(row -> row.targetId().equals(campaign)).findFirst().orElseThrow();

        assertThat(deletion.run(gm.id(), ofCampaign.id())).isTrue();
        assertThat(conversations.conversations).doesNotContainKey(conversation);
        assertThat(conversations.turns).doesNotContainKey(turn);
        assertThat(library.campaigns).doesNotContainKey(campaign);

        assertThat(deletion.run(gm.id(), ofConversation.id())).isTrue();
        assertThat(deletions.rows.get(ofConversation.id()).doneAt()).isNotNull();
        assertThat(deletions.rows.get(ofCampaign.id()).doneAt()).isNotNull();
        assertThat(conversations.deleting).isEmpty();
    }

    @Test
    void anotherOwnersConversationIsNotFoundOnBothRoutesAndNothingChanges() throws Exception {
        Gm owner = signUp();
        Gm other = signUp();
        HttpResponse<String> asked = askIn(owner, "Quanto dano?", null, "questions");
        UUID conversation = UUID.fromString(json(asked).path("conversationId").asString());

        assertNotFound(get(other, "/api/v1/conversations/" + conversation + "/deletion"));
        assertNotFound(send(other, "DELETE", "/api/v1/conversations/" + conversation));
        assertNotFound(send(owner, "DELETE", "/api/v1/conversations/not-an-id"));
        assertNotFound(send(owner, "DELETE", "/api/v1/conversations/" + Ids.newId()));
        assertNotFound(get(owner, "/api/v1/conversations/" + Ids.newId() + "/deletion"));

        assertThat(conversations.deleting).doesNotContain(conversation);
        assertThat(deletions.rows.values()).noneMatch(row -> row.targetId().equals(conversation));
        assertThat(get(owner, "/api/v1/conversations/" + conversation).statusCode()).isEqualTo(200);
        assertThat(json(get(owner, "/api/v1/conversations/" + conversation + "/deletion")).path("questions").asLong())
                .isEqualTo(1);
    }

    @Test
    void aDeletingConversationsTurnIsNotFoundOnBothDismissalRoutesAndItsDismissedTurnIsRemovedWithIt() throws Exception {
        Gm gm = signUp();
        Source source = source(gm);
        UUID dismissed = answered(gm, null, "questions", source);
        UUID conversation = conversations.turns.get(dismissed).conversationId();
        tick();
        UUID shown = answered(gm, conversation, null, source);
        // Story 3.11: a dismissed turn is not counted, but the deletion takes it too.
        assertThat(send(gm, "PUT", "/api/v1/turns/" + dismissed + "/dismissal").statusCode()).isEqualTo(204);
        assertThat(json(get(gm, "/api/v1/conversations/" + conversation + "/deletion")).path("questions").asLong())
                .isEqualTo(1);

        assertThat(send(gm, "DELETE", "/api/v1/conversations/" + conversation).statusCode()).isEqualTo(202);

        for (UUID turn : List.of(dismissed, shown)) {
            assertNotFound(send(gm, "PUT", "/api/v1/turns/" + turn + "/dismissal"));
            assertNotFound(send(gm, "DELETE", "/api/v1/turns/" + turn + "/dismissal"));
        }
        assertThat(conversations.turns.get(dismissed).dismissedAt()).isNotNull();
        assertThat(conversations.turns.get(shown).dismissedAt()).isNull();

        assertThat(deletion.sweep()).isEqualTo(1);

        assertThat(conversations.turns).doesNotContainKeys(dismissed, shown);
        assertThat(conversations.conversations).doesNotContainKey(conversation);
        assertThat(conversations.batches.values()).noneMatch(batch -> batch.turnId().equals(dismissed));
        assertThat(references.rows.values()).noneMatch(reference -> reference.turnId().equals(dismissed));
    }

    @Test
    void aDeletingConversationsTurnIsNotFoundOnBothBookmarkRoutesAndItsSavedTurnLeavesTheListAndIsRemoved()
            throws Exception {
        Gm gm = signUp();
        Source source = source(gm);
        UUID saved = answered(gm, null, "questions", source);
        UUID conversation = conversations.turns.get(saved).conversationId();
        tick();
        UUID kept = answered(gm, null, "rules", source);
        UUID campaign = createCampaign(gm, "Mesa de domingo");
        HttpResponse<String> asked = send(gm, "POST", "/api/v1/campaigns/" + campaign + "/turns",
                "{\"question\":\"Quanto dano?\"}");
        UUID elsewhere = UUID.fromString(json(asked).path("id").asString());
        Source there = source(gm, campaign);
        Claim claim = claim(conversations.turns.get(elsewhere).jobId());
        assertThat(sendAsWorker("POST", claim.path("events"), "{\"batch\":1,\"events\":[{\"type\":\"delta\","
                + "\"text\":\"1d10\"},{\"type\":\"citation\",\"passageId\":\"" + there.passageId()
                + "\",\"offset\":4}],\"usage\":[]}", claim.grant()).statusCode()).isEqualTo(200);
        assertThat(sendAsWorker("POST", claim.path("outcome"), "{\"outcome\":\"done\"}", claim.grant())
                .statusCode()).isEqualTo(204);
        for (UUID turn : List.of(saved, kept, elsewhere)) {
            tick();
            assertThat(send(gm, "PUT", "/api/v1/turns/" + turn + "/bookmark").statusCode()).isEqualTo(204);
        }
        assertThat(ids(json(get(gm, "/api/v1/saved-answers")).path("items"), "turnId"))
                .containsExactly(elsewhere.toString(), kept.toString(), saved.toString());

        assertThat(send(gm, "DELETE", "/api/v1/conversations/" + conversation).statusCode()).isEqualTo(202);

        // Story 3.12: from the delete's commit its turns are not found on both routes and leave the list.
        assertNotFound(send(gm, "PUT", "/api/v1/turns/" + saved + "/bookmark"));
        assertNotFound(send(gm, "DELETE", "/api/v1/turns/" + saved + "/bookmark"));
        assertThat(conversations.turns.get(saved).bookmarkedAt()).isNotNull();
        JsonNode listed = json(get(gm, "/api/v1/saved-answers"));
        assertThat(ids(listed.path("items"), "turnId")).containsExactly(elsewhere.toString(), kept.toString());
        assertThat(listed.path("total").asLong()).isEqualTo(2);
        // A campaign being deleted: its saved answer leaves the list at once too.
        assertThat(send(gm, "DELETE", "/api/v1/campaigns/" + campaign).statusCode()).isEqualTo(202);
        assertThat(ids(json(get(gm, "/api/v1/saved-answers")).path("items"), "turnId"))
                .containsExactly(kept.toString());

        assertThat(deletion.sweep()).isEqualTo(2);

        // The rows go, and the bookmarks with them; the other conversation's saved turn stays.
        assertThat(conversations.turns).doesNotContainKeys(saved, elsewhere);
        assertThat(conversations.turns.get(kept).bookmarkedAt()).isNotNull();
        JsonNode after = json(get(gm, "/api/v1/saved-answers"));
        assertThat(ids(after.path("items"), "turnId")).containsExactly(kept.toString());
        assertThat(after.path("total").asLong()).isEqualTo(1);
    }

    @Test
    void aConversationMarkedDeletingWhileTheSavedListIsReadIsLeftOutOfThatPageAndItsTotal() throws Exception {
        Gm gm = signUp();
        Source source = source(gm);
        UUID going = answered(gm, null, "questions", source);
        UUID conversation = conversations.turns.get(going).conversationId();
        tick();
        UUID kept = answered(gm, null, "rules", source);
        for (UUID turn : List.of(going, kept)) {
            tick();
            assertThat(send(gm, "PUT", "/api/v1/turns/" + turn + "/bookmark").statusCode()).isEqualTo(204);
        }
        // Story 3.12: the conversation starts being deleted after the page's saved turns were read and before the
        // conversations behind them are (another connection's commit, READ COMMITTED): a 200, never a 500.
        conversations.afterSavedTurns.set(() -> conversations.deleting.add(conversation));
        try {
            HttpResponse<String> listed = get(gm, "/api/v1/saved-answers");

            assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
            JsonNode page = json(listed);
            assertThat(ids(page.path("items"), "turnId")).containsExactly(kept.toString());
            // The total is counted after the mark, so it leaves the deleting conversation out as well.
            assertThat(page.path("total").asLong()).isEqualTo(1);
            assertThat(page.has("nextCursor")).isFalse();
            assertThat(conversations.afterSavedTurns.get()).isNull();
        } finally {
            conversations.afterSavedTurns.set(null);
            conversations.deleting.remove(conversation);
        }
    }

    // --- Story 5.4 part B: a conversation that has saved artifacts

    @Test
    void theDialogListsTheLiveSavedArtifactsOldestFirstAndKeepAllKeepsThemWithNoLink() throws Exception {
        Gm gm = signUp();
        Source source = source(gm);
        UUID cryptTurn = drafted(gm, null, source);
        UUID conversation = conversations.turns.get(cryptTurn).conversationId();
        UUID crypt = saved(gm, cryptTurn, "Cripta", "encounter");
        tick();
        UUID troll = saved(gm, drafted(gm, conversation, source), "Troll", "creature");
        tick();
        // A saved artifact deleted since is not listed; one of another conversation and one written by hand neither.
        UUID gone = saved(gm, drafted(gm, conversation, source), "Velho", "note");
        assertThat(send(gm, "DELETE", artifactPath(gm, gone)).statusCode()).isEqualTo(202);
        UUID otherTurn = drafted(gm, null, source);
        UUID elsewhere = saved(gm, otherTurn, "Outra", "note");
        UUID handWritten = handWritten(gm, "Kael");
        // A later edit renames it: the dialog lists the newest version's name.
        assertThat(send(gm, "PATCH", artifactPath(gm, troll), "{\"version\":1,\"recordType\":\"creature\","
                + "\"name\":\"Troll das Pedras\",\"fields\":[]}").statusCode()).isEqualTo(200);
        assertThat(json(get(gm, artifactPath(gm, crypt))).path("conversationId").asString())
                .isEqualTo(conversation.toString());

        HttpResponse<String> preview = get(gm, "/api/v1/conversations/" + conversation + "/deletion");

        assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
        JsonNode dialog = json(preview);
        assertThat(dialog.path("questions").asLong()).isEqualTo(3);
        assertThat(ids(dialog.path("artifacts"))).containsExactly(crypt.toString(), troll.toString());
        assertThat(dialog.path("artifacts").get(0).path("name").asString()).isEqualTo("Cripta");
        assertThat(dialog.path("artifacts").get(0).path("recordType").asString()).isEqualTo("encounter");
        assertThat(dialog.path("artifacts").get(1).path("name").asString()).isEqualTo("Troll das Pedras");
        assertThat(dialog.path("artifacts").get(1).path("recordType").asString()).isEqualTo("creature");
        assertThat(ids(json(get(gm, "/api/v1/conversations/" + conversations.turns.get(otherTurn).conversationId()
                + "/deletion")).path("artifacts"))).containsExactly(elsewhere.toString());

        // Matrix row "Keep all": nothing ticked.
        HttpResponse<String> deleted = deleteWith(gm, conversation, "{\"artifactIds\":[]}");

        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(202);
        assertThat(deleted.body()).isEmpty();
        assertThat(deleted.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertNotFound(get(gm, "/api/v1/conversations/" + conversation));
        // At once, before the pass: both stay with their versions, and neither names the deleting conversation.
        for (UUID kept : List.of(crypt, troll)) {
            HttpResponse<String> read = get(gm, artifactPath(gm, kept));
            assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
            assertThat(json(read).path("conversationId").isNull()).isTrue();
        }
        assertThat(json(get(gm, artifactPath(gm, troll) + "/versions")).path("total").asLong()).isEqualTo(2);
        assertThat(json(get(gm, artifactPath(gm, elsewhere))).path("conversationId").asString())
                .isEqualTo(conversations.turns.get(otherTurn).conversationId().toString());
        assertThat(records.deletingArtifacts).contains(gone).doesNotContain(crypt, troll, elsewhere, handWritten);

        deletion.sweep();

        assertThat(conversations.conversations).doesNotContainKey(conversation);
        assertThat(records.artifacts).containsKeys(crypt, troll, elsewhere, handWritten).doesNotContainKey(gone);
        assertThat(json(get(gm, artifactPath(gm, crypt))).path("conversationId").isNull()).isTrue();
        assertThat(ids(json(get(gm, "/api/v1/campaigns/" + gm.campaign() + "/artifacts")).path("items")))
                .contains(crypt.toString(), troll.toString(), elsewhere.toString(), handWritten.toString());
    }

    @Test
    void theTickedArtifactsGoWithTheConversationAndTheOthersStayUnchanged() throws Exception {
        Gm gm = signUp();
        Source source = source(gm);
        UUID cryptTurn = drafted(gm, null, source);
        UUID conversation = conversations.turns.get(cryptTurn).conversationId();
        UUID crypt = saved(gm, cryptTurn, "Cripta", "encounter");
        UUID troll = saved(gm, drafted(gm, conversation, source), "Troll", "creature");

        // Matrix row "Delete some": "Cripta" ticked, "Troll" not.
        HttpResponse<String> deleted = deleteWith(gm, conversation, "{\"artifactIds\":[\"" + crypt + "\"]}");

        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(202);
        assertThat(records.deletingArtifacts).contains(crypt).doesNotContain(troll);
        assertNotFound(get(gm, artifactPath(gm, crypt)));
        assertThat(ids(json(get(gm, "/api/v1/campaigns/" + gm.campaign() + "/artifacts")).path("items")))
                .containsExactly(troll.toString());
        HttpResponse<String> kept = get(gm, artifactPath(gm, troll));
        assertThat(kept.statusCode()).isEqualTo(200);
        assertThat(json(kept).path("name").asString()).isEqualTo("Troll");
        assertThat(json(kept).path("version").asInt()).isEqualTo(1);
        assertThat(json(kept).path("conversationId").isNull()).isTrue();
        // The edit and restore answers name no deleting conversation either: unchanged, renamed, then version 1 back.
        HttpResponse<String> unchanged = send(gm, "PATCH", artifactPath(gm, troll), "{\"version\":1,"
                + "\"recordType\":\"creature\",\"name\":\"Troll\",\"fields\":[],\"content\":"
                + JSON.writeValueAsString(json(kept).path("content").asString()) + "}");
        assertThat(unchanged.statusCode()).as(unchanged.body()).isEqualTo(200);
        assertThat(json(unchanged).path("version").asInt()).isEqualTo(1);
        assertThat(json(unchanged).path("conversationId").isNull()).isTrue();
        HttpResponse<String> renamed = send(gm, "PATCH", artifactPath(gm, troll), "{\"version\":1,"
                + "\"recordType\":\"creature\",\"name\":\"Troll das Pedras\",\"fields\":[]}");
        assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(200);
        assertThat(json(renamed).path("version").asInt()).isEqualTo(2);
        assertThat(json(renamed).path("conversationId").isNull()).isTrue();
        HttpResponse<String> restored = send(gm, "POST", artifactPath(gm, troll) + "/versions/1/restore",
                "{\"version\":2}");
        assertThat(restored.statusCode()).as(restored.body()).isEqualTo(200);
        assertThat(json(restored).path("version").asInt()).isEqualTo(3);
        assertThat(json(restored).path("conversationId").isNull()).isTrue();
        assertThat(deletions.rows.values()).filteredOn(row -> row.targetId().equals(crypt))
                .singleElement().extracting(DeletionRow::kind).isEqualTo(DeletionRow.ARTIFACT);

        deletion.sweep();

        assertThat(records.artifacts).doesNotContainKey(crypt).containsKey(troll);
        assertThat(artifactVersions.rows.values()).noneMatch(row -> row.recordId().equals(crypt))
                .anyMatch(row -> row.recordId().equals(troll));
        assertThat(conversations.conversations).doesNotContainKey(conversation);
    }

    @Test
    void selectingAllTakesEveryArtifactWithTheConversation() throws Exception {
        Gm gm = signUp();
        Source source = source(gm);
        UUID cryptTurn = drafted(gm, null, source);
        UUID conversation = conversations.turns.get(cryptTurn).conversationId();
        UUID crypt = saved(gm, cryptTurn, "Cripta", "encounter");
        UUID troll = saved(gm, drafted(gm, conversation, source), "Troll", "creature");

        HttpResponse<String> deleted = deleteWith(gm, conversation, "{\"artifactIds\":[\"" + crypt + "\",\""
                + troll + "\",\"" + crypt + "\"]}");

        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(202);
        assertThat(records.deletingArtifacts).contains(crypt, troll);
        assertNotFound(get(gm, artifactPath(gm, crypt)));
        assertNotFound(get(gm, artifactPath(gm, troll)));
        assertThat(json(get(gm, "/api/v1/campaigns/" + gm.campaign() + "/artifacts")).path("total").asLong())
                .isZero();

        deletion.sweep();

        assertThat(records.artifacts).doesNotContainKeys(crypt, troll);
        assertThat(conversations.conversations).doesNotContainKey(conversation);
    }

    @Test
    void aConversationWithNoLiveSavedArtifactListsNone() throws Exception {
        Gm gm = signUp();
        Source source = source(gm);
        UUID answered = answered(gm, null, "questions", source);
        UUID plain = conversations.turns.get(answered).conversationId();
        UUID draftTurn = drafted(gm, null, source);
        UUID withDeleted = conversations.turns.get(draftTurn).conversationId();
        UUID gone = saved(gm, draftTurn, "Velho", "note");
        assertThat(send(gm, "DELETE", artifactPath(gm, gone)).statusCode()).isEqualTo(202);

        for (UUID conversation : List.of(plain, withDeleted)) {
            JsonNode dialog = json(get(gm, "/api/v1/conversations/" + conversation + "/deletion"));
            assertThat(dialog.path("questions").asLong()).isEqualTo(1);
            assertThat(dialog.path("artifacts").isArray()).isTrue();
            assertThat(dialog.path("artifacts")).isEmpty();
        }
        // The plain delete keeps working, as an empty artifactIds.
        assertThat(send(gm, "DELETE", "/api/v1/conversations/" + plain).statusCode()).isEqualTo(202);
        assertNotFound(get(gm, "/api/v1/conversations/" + plain));
        deletion.sweep();
    }

    @Test
    void theOldDeleteKeepsEverySavedArtifact() throws Exception {
        Gm gm = signUp();
        Source source = source(gm);
        UUID cryptTurn = drafted(gm, null, source);
        UUID conversation = conversations.turns.get(cryptTurn).conversationId();
        UUID crypt = saved(gm, cryptTurn, "Cripta", "encounter");

        // The 1.48.0 frontend's delete.
        assertThat(send(gm, "DELETE", "/api/v1/conversations/" + conversation).statusCode()).isEqualTo(202);

        assertThat(records.deletingArtifacts).doesNotContain(crypt);
        assertThat(json(get(gm, artifactPath(gm, crypt))).path("conversationId").isNull()).isTrue();
        deletion.sweep();
        assertThat(get(gm, artifactPath(gm, crypt)).statusCode()).isEqualTo(200);
    }

    @Test
    void anIdThatIsNotOneOfTheConversationsLiveArtifactsIsIgnored() throws Exception {
        Gm gm = signUp();
        Source source = source(gm);
        UUID cryptTurn = drafted(gm, null, source);
        UUID conversation = conversations.turns.get(cryptTurn).conversationId();
        UUID crypt = saved(gm, cryptTurn, "Cripta", "encounter");
        // Matrix row "Gone meanwhile": deleted in another tab.
        UUID gone = saved(gm, drafted(gm, conversation, source), "Velho", "note");
        assertThat(send(gm, "DELETE", artifactPath(gm, gone)).statusCode()).isEqualTo(202);
        // Matrix row "Foreign id": another conversation's, one written by hand, and another owner's.
        UUID elsewhere = saved(gm, drafted(gm, null, source), "Outra", "note");
        UUID handWritten = handWritten(gm, "Kael");
        Gm other = signUp();
        UUID theirTurn = drafted(other, null, source(other));
        UUID theirs = saved(other, theirTurn, "Deles", "note");
        UUID theirConversation = conversations.turns.get(theirTurn).conversationId();

        HttpResponse<String> deleted = deleteWith(gm, conversation, "{\"artifactIds\":[\"" + gone + "\",\""
                + elsewhere + "\",\"" + handWritten + "\",\"" + theirs + "\",\"" + Ids.newId() + "\"]}");

        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(202);
        assertNotFound(get(gm, "/api/v1/conversations/" + conversation));
        assertThat(records.deletingArtifacts).contains(gone).doesNotContain(crypt, elsewhere, handWritten, theirs);
        List<UUID> named = List.of(gone, crypt, elsewhere, handWritten, theirs);
        assertThat(deletions.rows.values()).filteredOn(row -> named.contains(row.targetId()))
                .extracting(DeletionRow::targetId).containsExactly(gone);
        assertThat(get(gm, artifactPath(gm, crypt)).statusCode()).isEqualTo(200);
        assertThat(get(gm, artifactPath(gm, elsewhere)).statusCode()).isEqualTo(200);
        assertThat(get(gm, artifactPath(gm, handWritten)).statusCode()).isEqualTo(200);
        assertThat(get(other, artifactPath(other, theirs)).statusCode()).isEqualTo(200);
        assertThat(get(other, "/api/v1/conversations/" + theirConversation).statusCode()).isEqualTo(200);
        deletion.sweep();
    }

    @Test
    void aMalformedBodyIsRefusedOnArtifactIdsAndNothingIsDeleted() throws Exception {
        Gm gm = signUp();
        Source source = source(gm);
        UUID cryptTurn = drafted(gm, null, source);
        UUID conversation = conversations.turns.get(cryptTurn).conversationId();
        UUID crypt = saved(gm, cryptTurn, "Cripta", "encounter");
        List<String> tooMany = new ArrayList<>();
        tooMany.add(crypt.toString());
        while (tooMany.size() < 65) {
            tooMany.add(Ids.newId().toString());
        }
        List<String> bodies = List.of(JSON.writeValueAsString(Map.of("artifactIds", tooMany)),
                "{\"artifactIds\":[\"" + crypt + "\",\"nope\"]}", "{\"artifactIds\":[\"" + crypt + "\",7]}",
                "{\"artifactIds\":[null]}", "{\"artifactIds\":\"" + crypt + "\"}", "{\"artifactIds\":null}", "{}",
                "[]", "null", "nope", "{\"artifactIds\":[", "");

        for (String body : bodies) {
            HttpResponse<String> refused = deleteWith(gm, conversation, body);
            assertThat(refused.statusCode()).as(body + " -> " + refused.body()).isEqualTo(400);
            JsonNode problem = json(refused);
            assertThat(problem.path("code").asString()).as(body).isEqualTo("request.invalid");
            assertThat(problem.path("params").path("field").asString()).as(body).isEqualTo("artifactIds");
        }
        assertThat(get(gm, "/api/v1/conversations/" + conversation).statusCode()).isEqualTo(200);
        assertThat(get(gm, artifactPath(gm, crypt)).statusCode()).isEqualTo(200);
        assertThat(records.deletingArtifacts).doesNotContain(crypt);
        assertThat(deletions.rows.values()).noneMatch(row -> row.targetId().equals(conversation)
                || row.targetId().equals(crypt));
        // 64 ids are taken.
        HttpResponse<String> taken = deleteWith(gm, conversation, JSON.writeValueAsString(Map.of("artifactIds",
                tooMany.subList(0, 64))));
        assertThat(taken.statusCode()).as(taken.body()).isEqualTo(202);
        assertThat(records.deletingArtifacts).contains(crypt);
        deletion.sweep();
    }

    @Test
    void aConversationNotFoundDeletesNoArtifact() throws Exception {
        Gm gm = signUp();
        Source source = source(gm);
        UUID cryptTurn = drafted(gm, null, source);
        UUID conversation = conversations.turns.get(cryptTurn).conversationId();
        UUID crypt = saved(gm, cryptTurn, "Cripta", "encounter");
        String ticked = "{\"artifactIds\":[\"" + crypt + "\"]}";

        // Matrix row "Other owner": account B posts to A's conversation.
        Gm other = signUp();
        assertNotFound(deleteWith(other, conversation, ticked));
        assertNotFound(deleteWith(gm, Ids.newId(), ticked));
        assertNotFound(send(gm, "POST", "/api/v1/conversations/nope/deletion", ticked));
        assertThat(records.deletingArtifacts).doesNotContain(crypt);
        assertThat(get(gm, "/api/v1/conversations/" + conversation).statusCode()).isEqualTo(200);

        // Not checked here: a deletion another tab recorded first (covered in ConversationDeletionIntegrationTest).

        // Matrix row "Not found": deleted in another tab first.
        assertThat(send(gm, "DELETE", "/api/v1/conversations/" + conversation).statusCode()).isEqualTo(202);
        assertNotFound(deleteWith(gm, conversation, ticked));
        assertThat(records.deletingArtifacts).doesNotContain(crypt);
        assertThat(get(gm, artifactPath(gm, crypt)).statusCode()).isEqualTo(200);
        deletion.sweep();
    }

    // --- helpers

    /** The clock moves on, so what is asked next comes later. */
    private void tick() {
        clock.set(clock.instant().plusSeconds(1));
    }

    private static String listPath(Gm gm) {
        return "/api/v1/campaigns/" + gm.campaign() + "/conversations?area=questions";
    }

    private static final String PROPOSED = "{\"outcome\":\"done\",\"result\":{\"proposal\":"
            + "{\"name\":\"Emboscada na Cripta\",\"recordType\":\"encounter\"}}}";

    /**
     * A draft asked in the conversation (or a new questions conversation), claimed, streamed with a delta and a citation
     * and done with a proposal (Story 5.4 part B), as {@code ConversationFlowTest.doneDraft} builds one.
     */
    private UUID drafted(Gm gm, UUID conversation, Source source) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", "Um encontro para a cripta");
        if (conversation != null) {
            body.put("conversationId", conversation.toString());
        } else {
            body.put("area", "questions");
        }
        body.put("kind", "draft");
        HttpResponse<String> asked = send(gm, "POST", "/api/v1/campaigns/" + gm.campaign() + "/turns",
                JSON.writeValueAsString(body));
        assertThat(asked.statusCode()).as(asked.body()).isEqualTo(201);
        UUID turnId = UUID.fromString(json(asked).path("id").asString());
        Claim claim = claim(conversations.turns.get(turnId).jobId());
        HttpResponse<String> posted = sendAsWorker("POST", claim.path("events"), "{\"batch\":1,\"events\":["
                + "{\"type\":\"delta\",\"text\":\"## Emboscada\\n\\nTrês esqueletos.\"},{\"type\":\"citation\","
                + "\"passageId\":\"" + source.passageId() + "\",\"offset\":4}],\"usage\":[]}", claim.grant());
        assertThat(posted.statusCode()).as(posted.body()).isEqualTo(200);
        HttpResponse<String> reported = sendAsWorker("POST", claim.path("outcome"), PROPOSED, claim.grant());
        assertThat(reported.statusCode()).as(reported.body()).isEqualTo(204);
        return turnId;
    }

    /** The draft saved as an artifact named {@code name} of the type {@code recordType}; its id. */
    private UUID saved(Gm gm, UUID turnId, String name, String recordType) throws Exception {
        HttpResponse<String> saved = send(gm, "POST", "/api/v1/turns/" + turnId + "/artifact",
                JSON.writeValueAsString(Map.of("name", name, "recordType", recordType)));
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(201);
        return UUID.fromString(json(saved).path("id").asString());
    }

    /** An artifact of the GM's campaign written by hand; its id. */
    private UUID handWritten(Gm gm, String name) throws Exception {
        HttpResponse<String> created = send(gm, "POST", "/api/v1/campaigns/" + gm.campaign() + "/artifacts",
                JSON.writeValueAsString(Map.of("name", name, "recordType", "character_sheet", "fields", List.of())));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(json(created).path("id").asString());
    }

    private static String artifactPath(Gm gm, UUID artifactId) {
        return "/api/v1/campaigns/" + gm.campaign() + "/artifacts/" + artifactId;
    }

    /** The conversation's deletion with this body (Story 5.4 part B). */
    private HttpResponse<String> deleteWith(Gm gm, UUID conversation, String body) throws Exception {
        return send(gm, "POST", "/api/v1/conversations/" + conversation + "/deletion", body);
    }

    /** Asks in the conversation (or a new one of the area), claims, posts a delta and a citation, reports done. */
    private UUID answered(Gm gm, UUID conversation, String area, Source source) throws Exception {
        HttpResponse<String> asked = askIn(gm, "Quanto dano causa Fire Bolt?", conversation, area);
        assertThat(asked.statusCode()).as(asked.body()).isEqualTo(201);
        UUID turnId = UUID.fromString(json(asked).path("id").asString());
        Claim claim = claim(conversations.turns.get(turnId).jobId());
        HttpResponse<String> posted = sendAsWorker("POST", claim.path("events"), "{\"batch\":1,\"events\":["
                + "{\"type\":\"delta\",\"text\":\"1d10\"},{\"type\":\"citation\",\"passageId\":\"" + source.passageId()
                + "\",\"offset\":4}],\"usage\":[]}", claim.grant());
        assertThat(posted.statusCode()).as(posted.body()).isEqualTo(200);
        HttpResponse<String> reported = sendAsWorker("POST", claim.path("outcome"), "{\"outcome\":\"done\"}",
                claim.grant());
        assertThat(reported.statusCode()).as(reported.body()).isEqualTo(204);
        return turnId;
    }

    /** An accepted material of the GM's campaign with one passage. */
    private Source source(Gm gm) {
        return source(gm, gm.campaign());
    }

    /** An accepted material of the campaign with one passage. */
    private Source source(Gm gm, UUID campaign) {
        return as(gm.id(), () -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            UUID materialId = Ids.newId();
            materials.insertMaterial(new MaterialRow(materialId, gm.id(),
                    TestMaterials.materialName(vault, materialId, gm.id(), "Livro"), now, "rulebook", null,
                    campaign));
            UUID versionId = Ids.newId();
            materials.insertVersion(new VersionRow(versionId, gm.id(), materialId, "ready", "pdf", 1000L, now, now));
            UUID passageId = Ids.newId();
            List<PassageRow> rows = new ArrayList<>();
            rows.add(TestMaterials.passage(vault, passageId, gm.id(), versionId, 0, 1, "Fire Bolt deals 1d10",
                    List.of("Magias"), now));
            materials.insertPassages(rows);
            return new Source(materialId, versionId, passageId);
        });
    }

    private UUID searchId(Gm gm) throws Exception {
        HttpResponse<String> created = send(gm, "POST", "/api/v1/campaigns/" + gm.campaign() + "/searches",
                "{\"query\":\"Quanto dano\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(202);
        return UUID.fromString(json(created).path("id").asString());
    }

    private UUID createCampaign(Gm gm, String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("systemId", gm.system().toString());
        HttpResponse<String> created = send(gm, "POST", "/api/v1/campaigns", JSON.writeValueAsString(body));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(json(created).path("id").asString());
    }

    private HttpResponse<String> askIn(Gm gm, String question, UUID conversation, String area) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        if (conversation != null) {
            body.put("conversationId", conversation.toString());
        }
        if (area != null) {
            body.put("area", area);
        }
        return send(gm, "POST", "/api/v1/campaigns/" + gm.campaign() + "/turns", JSON.writeValueAsString(body));
    }

    private Claim claim(UUID jobId) throws Exception {
        HttpResponse<String> claimed = sendAsWorker("POST", "/internal/v1/jobs/" + jobId + "/claims",
                "{\"providers\":[\"azure-openai\"]}", null);
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(201);
        JsonNode reply = json(claimed);
        return new Claim(jobId, reply.path("claimId").asString(), reply.path("grant").asString());
    }

    private static List<String> ids(JsonNode items) {
        List<String> found = new ArrayList<>();
        items.forEach(item -> found.add(item.path("id").asString()));
        return found;
    }

    private static List<String> ids(JsonNode items, String field) {
        List<String> found = new ArrayList<>();
        items.forEach(item -> found.add(item.path(field).asString()));
        return found;
    }

    private <T> T as(UUID owner, Supplier<T> work) {
        return OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status -> work.get()));
    }

    private static void assertNotFound(HttpResponse<?> answer) {
        assertThat(answer.statusCode()).as(String.valueOf(answer.body())).isEqualTo(404);
        if (answer.body() instanceof String body && !body.isEmpty()) {
            assertThat(JSON.readTree(body).path("code").asString()).isEqualTo("request.not_found");
        }
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

    private HttpResponse<String> sendAsWorker(String method, String path, String json, String grant) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + WORKER_CREDENTIAL).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json));
        if (grant != null) {
            request.header("X-Job-Grant", grant);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private Gm signUp() throws Exception {
        String sub = "cdel-" + UUID.randomUUID();
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
