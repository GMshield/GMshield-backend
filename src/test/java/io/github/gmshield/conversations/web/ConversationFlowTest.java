package io.github.gmshield.conversations.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import io.github.gmshield.conversations.data.EventBatchRow;
import io.github.gmshield.conversations.data.InMemoryConversationStore;
import io.github.gmshield.conversations.data.InMemoryTurnReferenceStore;
import io.github.gmshield.conversations.data.TurnReferenceRow;
import io.github.gmshield.conversations.data.TurnRow;
import io.github.gmshield.jobs.data.InMemoryJobStorage;
import io.github.gmshield.jobs.data.InMemoryJobStream;
import io.github.gmshield.jobs.data.JobMemoryStores;
import io.github.gmshield.jobs.data.JobRow;
import io.github.gmshield.jobs.data.MutableClock;
import io.github.gmshield.jobs.service.JobSweep;
import io.github.gmshield.library.data.LibraryTestStores;
import io.github.gmshield.limits.data.InMemoryUsageStore;
import io.github.gmshield.limits.data.UsageRow;
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
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * Story 2.4 without a database or Redis: every backend row of the I/O matrix, from the GM's question through the
 * worker's claim of its {@code question.answer} job, its numbered event batches and usage, and its outcome, to the
 * server-sent events the GM reads (replay from {@code Last-Event-ID}, the keep-alive, the end on {@code done} or
 * {@code error}). The no-splice rule for a reported and a lapsed attempt, the encrypted columns, the foreign owner. The
 * same tables against PostgreSQL are in {@code ConversationIntegrationTest}.
 *
 * <p>Story 2.5: every row of its I/O matrix: the cited passages expanded into numbered references stored by value with
 * the turn and sent on {@code done}, one per place, a bad id dropped, {@code silent} with no reference, a replay with
 * {@code removed} as of now, no reference for a failed answer, and the invariant (references xor silent).
 *
 * <p>Story 2.6: every backend row of its I/O matrix: the GM's stop mid-answer, with nothing cited, before the claim,
 * after the end, then a lapse, and on a foreign turn; the batch reply's {@code stop}; exactly one final event over
 * stop-then-done, stop-then-failed and stop-then-lapse; an estimated usage stored as one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"GMSHIELD_MODE=test",
    "gmshield.conversations.keep-alive=400ms", "gmshield.conversations.poll=100ms"})
@ActiveProfiles({"test-mode", "unit"})
class ConversationFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CREDENTIAL = "gmshield-test-mode-frontend-credential-not-a-secret";
    private static final String WORKER_CREDENTIAL = "gmshield-test-mode-worker-credential-not-a-secret";
    private static final String QUESTION = "Quanto dano causa Fire Bolt?";
    private static final String PASSAGE_TEXT = "Fire Bolt deals 1d10 Fire damage";
    private static final String MATERIAL_NAME = "SRD 5.2.1 Livro";
    private static final List<String> SECTION = List.of("Magias", "Fire Bolt");
    private static final Instant START = Instant.parse("2026-11-18T12:00:00Z");

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
    private InMemoryJobStorage jobs;
    @Autowired
    private InMemoryJobStream stream;
    @Autowired
    private InMemoryConversationStore conversations;
    @Autowired
    private InMemoryTurnReferenceStore references;
    @Autowired
    private InMemoryUsageStore usage;
    @Autowired
    private InMemoryMaterialStore materials;
    @Autowired
    private JobSweep sweep;
    @Autowired
    private MutableClock clock;
    @Autowired
    private Vault vault;
    @Autowired
    private PlatformTransactionManager transactions;

    private final HttpClient client = HttpClient.newHttpClient();
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    record Gm(UUID id, String session, UUID campaign) {
    }

    /** A live claim: its job, claim id and grant. */
    record Claim(UUID jobId, String claimId, String grant, JsonNode input) {

        String path(String action) {
            return "/internal/v1/jobs/" + jobId + "/claims/" + claimId + "/" + action;
        }
    }

    /** One server-sent event as the GM's stream sends it. */
    record Event(String id, String name, String data) {
    }

    @BeforeEach
    void start() {
        clock.set(START);
        stream.clear();
        logged.start();
        root.addAppender(logged);
    }

    @AfterEach
    void stop() {
        root.detachAppender(logged);
        logged.stop();
    }

    // --- the matrix

    @Test
    void aQuestionIsRecordedWithItsJobAndItsAnswerStreamsInOrderThenDone() throws Exception {
        Gm gm = signUp();
        List<UUID> passages = accepted(gm, 2);
        JsonNode current = json(get(gm, "/api/v1/conversations/current"));
        assertThat(current.path("campaignId").asString()).isEqualTo(gm.campaign().toString());
        assertThat(current.path("hasSources").asBoolean()).isTrue();

        HttpResponse<String> asked = ask(gm, gm.campaign(), QUESTION);

        assertThat(asked.statusCode()).as(asked.body()).isEqualTo(201);
        assertThat(asked.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode turn = json(asked);
        assertThat(turn.path("language").asString()).isEqualTo("pt-BR");
        UUID turnId = UUID.fromString(turn.path("id").asString());
        TurnRow row = conversations.turns.get(turnId);
        assertThat(row.conversationId().toString()).isEqualTo(turn.path("conversationId").asString());
        assertThat(row.campaignTier()).isTrue();
        assertThat(row.systemTier()).isTrue();
        assertThat(row.modelKnowledge()).isFalse();
        JobRow job = jobs.row(row.jobId());
        assertThat(job.kind()).isEqualTo("question.answer");
        assertThat(job.lane()).isEqualTo("interactive");
        assertThat(stream.offered("gmshield:work:interactive")).contains(job.id());

        Claim claim = claim(job.id());
        assertThat(claim.input().path("question").asString()).isEqualTo(QUESTION);
        assertThat(claim.input().path("tiers").get(0).path("sources")).hasSize(1);
        // The passages route serves the turn's scope.
        HttpResponse<String> read = sendAsWorker("POST", claim.path("passages"),
                "{\"ids\":[\"" + passages.getFirst() + "\"]}", claim.grant());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(json(read).path("passages").get(0).path("text").asString()).isEqualTo(PASSAGE_TEXT + " 0");

        UUID call = Ids.newId();
        assertThat(postEvents(claim, 1, List.of(delta("Fire Bolt causa ")), List.of()).statusCode()).isEqualTo(200);
        HttpResponse<String> second = postEvents(claim, 2, List.of(delta("1d10 de dano de fogo."),
                citation(passages.getFirst(), 20)), List.of(usage(call, "chatcmpl-1", 120, 9)));
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        assertThat(json(second).path("stop").asBoolean()).isFalse();
        assertThat(report(claim, "{\"outcome\":\"done\"}").statusCode()).isEqualTo(204);

        List<Event> events = streamAll(gm, turnId, null);

        assertThat(events).extracting(Event::id).containsExactly("1", "2", "3", "4");
        assertThat(events).extracting(Event::name).containsExactly("delta", "delta", "citation", "done");
        assertThat(JSON.readTree(events.get(0).data()).path("text").asString()).isEqualTo("Fire Bolt causa ");
        assertThat(JSON.readTree(events.get(2).data()).path("passageId").asString())
                .isEqualTo(passages.getFirst().toString());
        assertThat(JSON.readTree(events.get(2).data()).path("offset").asInt()).isEqualTo(20);
        JsonNode done = JSON.readTree(events.get(3).data());
        assertThat(done.path("silent").asBoolean()).isFalse();
        assertThat(done.path("references")).hasSize(1);
        JsonNode reference = done.path("references").get(0);
        assertThat(reference.path("number").asInt()).isEqualTo(1);
        assertThat(reference.path("materialName").asString()).isEqualTo(MATERIAL_NAME);
        assertThat(strings(reference.path("sectionPath"))).isEqualTo(SECTION);
        assertThat(reference.path("page").asInt()).isEqualTo(1);
        assertThat(strings(reference.path("passageIds"))).containsExactly(passages.getFirst().toString());
        assertThat(reference.path("removed").asBoolean()).isFalse();
        // One usage row for the call, with the attempt's model; no price.
        UsageRow recorded = usage.rows.get(call);
        assertThat(recorded.jobId()).isEqualTo(job.id());
        assertThat(recorded.ownerId()).isEqualTo(gm.id());
        assertThat(recorded.provider()).isEqualTo("azure-openai");
        assertThat(recorded.tier()).isEqualTo("full");
        assertThat(recorded.responseId()).isEqualTo("chatcmpl-1");
        assertThat(recorded.input()).isEqualTo(120);
        assertThat(recorded.output()).isEqualTo(9);
        // A usage posted without estimated (the previous worker, during the rollout) is not an estimate.
        assertThat(recorded.estimated()).isFalse();
        // No line carries the question or an answer's text.
        assertThat(logged.list).allSatisfy(event -> assertThat(event.getFormattedMessage() + " "
                + event.getKeyValuePairs()).doesNotContain("Fire Bolt").doesNotContain("dano"));
    }

    @Test
    void theQuestionAndTheEventsAreStoredOnlyEncrypted() throws Exception {
        Gm gm = signUp();
        accepted(gm, 1);
        UUID turnId = turnId(ask(gm, gm.campaign(), QUESTION));
        Claim claim = claim(conversations.turns.get(turnId).jobId());
        postEvents(claim, 1, List.of(delta("Resposta secreta sobre Fire Bolt")), List.of());

        assertThat(new String(conversations.turns.get(turnId).question(), StandardCharsets.ISO_8859_1))
                .doesNotContain("Fire Bolt").doesNotContain("Quanto");
        List<EventBatchRow> stored = conversations.batches.values().stream()
                .filter(batch -> batch.turnId().equals(turnId)).toList();
        assertThat(stored).hasSize(1);
        assertThat(new String(stored.getFirst().payload(), StandardCharsets.ISO_8859_1))
                .doesNotContain("Resposta").doesNotContain("delta").doesNotContain("Fire Bolt");
    }

    @Test
    void aReopenedStreamGivesOnlyTheEventsAfterLastEventIdWithoutDuplicates() throws Exception {
        Gm gm = signUp();
        accepted(gm, 1);
        UUID turnId = turnId(ask(gm, gm.campaign(), QUESTION));
        Claim claim = claim(conversations.turns.get(turnId).jobId());
        postEvents(claim, 1, List.of(delta("a"), delta("b"), delta("c")), List.of());
        postEvents(claim, 2, List.of(delta("d"), delta("e")), List.of());
        postEvents(claim, 3, List.of(delta("f"), delta("g")), List.of());
        report(claim, "{\"outcome\":\"done\"}");

        List<Event> reopened = streamAll(gm, turnId, "3");

        assertThat(reopened).extracting(Event::id).containsExactly("4", "5", "6", "7", "8");
        assertThat(reopened.subList(0, 4)).extracting(event -> JSON.readTree(event.data()).path("text").asString())
                .containsExactly("d", "e", "f", "g");
        assertThat(reopened.getLast().name()).isEqualTo("done");
        // A Last-Event-ID that is not a sequence replays everything.
        assertThat(streamAll(gm, turnId, "nope")).hasSize(8);
    }

    @Test
    void aRepeatedBatchIsAcceptedAndStoredOnceWithItsUsageOnce() throws Exception {
        Gm gm = signUp();
        accepted(gm, 1);
        UUID turnId = turnId(ask(gm, gm.campaign(), QUESTION));
        Claim claim = claim(conversations.turns.get(turnId).jobId());
        UUID call = Ids.newId();
        postEvents(claim, 1, List.of(delta("um ")), List.of());

        for (int time = 0; time < 2; time++) {
            HttpResponse<String> posted = postEvents(claim, 2, List.of(delta("dois")), List.of(usage(call, null, 5, 1)));
            assertThat(posted.statusCode()).as(posted.body()).isEqualTo(200);
        }

        assertThat(conversations.batches.values().stream().filter(batch -> batch.turnId().equals(turnId)))
                .extracting(EventBatchRow::batch).containsExactlyInAnyOrder(1, 2);
        assertThat(usage.rows.values().stream().filter(row -> row.callId().equals(call))).hasSize(1);
        report(claim, "{\"outcome\":\"done\"}");
        assertThat(streamAll(gm, turnId, null)).extracting(Event::id).containsExactly("1", "2", "3");
    }

    @Test
    void aGapIsRefusedWithNothingStored() throws Exception {
        Gm gm = signUp();
        accepted(gm, 1);
        UUID turnId = turnId(ask(gm, gm.campaign(), QUESTION));
        Claim claim = claim(conversations.turns.get(turnId).jobId());
        postEvents(claim, 1, List.of(delta("um ")), List.of());

        HttpResponse<String> gap = postEvents(claim, 3, List.of(delta("três")), List.of(usage(Ids.newId(), null, 1, 1)));

        assertProblem(gap, 409, "job.batch_out_of_order");
        assertThat(conversations.batches.values().stream().filter(batch -> batch.turnId().equals(turnId))).hasSize(1);
        assertThat(usage.rows.values().stream().filter(row -> row.jobId().equals(claim.jobId()))).isEmpty();
        // The first batch of a claim is 1.
        Gm other = signUp();
        accepted(other, 1);
        Claim fresh = claim(conversations.anyTurnOfJob(jobOf(turnId(ask(other, other.campaign(), QUESTION))))
                .orElseThrow().jobId());
        assertProblem(postEvents(fresh, 2, List.of(delta("x")), List.of()), 409, "job.batch_out_of_order");
    }

    @Test
    void aModelThatFailsAfterABatchWasAcceptedEndsTheJobWithAnErrorEventAndItIsNeverOfferedAgain() throws Exception {
        Gm gm = signUp();
        accepted(gm, 1);
        UUID turnId = turnId(ask(gm, gm.campaign(), "fake:outage Quanto dano causa Fire Bolt?"));
        UUID jobId = jobOf(turnId);
        Claim claim = claim(jobId);
        postEvents(claim, 1, List.of(delta("Fire Bolt ")), List.of());

        assertThat(report(claim, "{\"outcome\":\"failed\",\"code\":\"model.unavailable\"}").statusCode())
                .isEqualTo(204);

        assertThat(jobs.row(jobId).state()).isEqualTo("failed");
        assertThat(jobs.row(jobId).outcomeCode()).isEqualTo("model.unavailable");
        List<Event> events = streamAll(gm, turnId, null);
        assertThat(events).extracting(Event::name).containsExactly("delta", "error");
        assertThat(JSON.readTree(events.getLast().data()).path("code").asString()).isEqualTo("model.unavailable");
        assertThat(sendAsWorker("POST", "/internal/v1/jobs/" + jobId + "/claims", "{\"providers\":[\"azure-openai\"]}",
                null).statusCode()).isEqualTo(409);
    }

    @Test
    void anEarlyFailureIsRetriedAndTheOpenStreamGetsNoEventOnlyItsKeepAlive() throws Exception {
        Gm gm = signUp();
        accepted(gm, 1);
        UUID turnId = turnId(ask(gm, gm.campaign(), QUESTION));
        UUID jobId = jobOf(turnId);
        Claim claim = claim(jobId);
        try (Stream open = open(gm, turnId, null)) {
            assertThat(open.status()).isEqualTo(200);
            assertThat(open.contentType()).startsWith("text/event-stream");

            assertThat(report(claim, "{\"outcome\":\"failed\",\"code\":\"retrieval.embedding_unavailable\"}")
                    .statusCode()).isEqualTo(204);

            assertThat(jobs.row(jobId).state()).isIn("waiting", "offered");
            List<String> lines = open.linesFor(Duration.ofMillis(1200));
            assertThat(lines).contains(": keep-alive").noneMatch(line -> line.startsWith("event:"));

            // The retry answers; the open stream gets it.
            Claim retry = claim(jobId);
            postEvents(retry, 1, List.of(delta("enfim")), List.of());
            report(retry, "{\"outcome\":\"done\"}");
            assertThat(open.events(Duration.ofSeconds(5))).extracting(Event::name).containsExactly("delta", "done");
        }
    }

    @Test
    void leavingTheStreamNeverTouchesTheJob() throws Exception {
        Gm gm = signUp();
        accepted(gm, 1);
        UUID turnId = turnId(ask(gm, gm.campaign(), QUESTION));
        UUID jobId = jobOf(turnId);
        Claim claim = claim(jobId);
        try (Stream open = open(gm, turnId, null)) {
            postEvents(claim, 1, List.of(delta("a")), List.of());
            assertThat(open.events(Duration.ofSeconds(1))).extracting(Event::id).containsExactly("1");
        }
        Thread.sleep(600);

        assertThat(jobs.row(jobId).state()).isEqualTo("running");
        assertThat(postEvents(claim, 2, List.of(delta("b")), List.of()).statusCode()).isEqualTo(200);
        assertThat(report(claim, "{\"outcome\":\"done\"}").statusCode()).isEqualTo(204);
        assertThat(streamAll(gm, turnId, "1")).extracting(Event::id).containsExactly("2", "3");
    }

    @Test
    void aLapsedClaimThatDeliveredEndsTheJobInsteadOfOfferingItAgain() throws Exception {
        Gm gm = signUp();
        accepted(gm, 1);
        UUID delivered = jobOf(turnId(ask(gm, gm.campaign(), QUESTION)));
        UUID silent = jobOf(turnId(ask(gm, gm.campaign(), "What is Fire Bolt?")));
        postEvents(claim(delivered), 1, List.of(delta("a")), List.of());
        claim(silent);

        clock.advance(Duration.ofSeconds(100));
        sweep.sweep();

        assertThat(jobs.row(delivered).state()).isEqualTo("failed");
        assertThat(jobs.row(delivered).outcomeCode()).isEqualTo("job.claim_lapsed");
        assertThat(jobs.row(silent).state()).isIn("waiting", "offered");
        UUID turnId = conversations.anyTurnOfJob(delivered).orElseThrow().id();
        assertThat(streamAll(gm, turnId, null)).extracting(Event::name).containsExactly("delta", "error");
    }

    @Test
    void aCampaignWithNothingAcceptedHasNoSourcesAndAskingStillWorks() throws Exception {
        Gm gm = signUp();

        assertThat(json(get(gm, "/api/v1/conversations/current")).path("hasSources").asBoolean()).isFalse();
        HttpResponse<String> asked = ask(gm, gm.campaign(), "What does Fire Bolt do?");

        assertThat(asked.statusCode()).as(asked.body()).isEqualTo(201);
        assertThat(json(asked).path("language").asString()).isEqualTo("en");
        Claim claim = claim(jobOf(turnId(asked)));
        assertThat(claim.input().path("tiers").get(0).path("sources")).isEmpty();
        assertThat(claim.input().path("tiers").get(1).path("sources")).isEmpty();
    }

    @Test
    void aBlankOrTooLongQuestionRecordsNothing() throws Exception {
        Gm gm = signUp();
        int turns = conversations.turns.size();
        int rows = jobs.rows.size();
        String longest = "😀".repeat(4000);

        for (String question : new String[] {"", "   \n\t", longest + "a"}) {
            assertProblem(ask(gm, gm.campaign(), question), 400, "request.invalid");
        }
        assertProblem(send(gm, "POST", "/api/v1/campaigns/" + gm.campaign() + "/turns", "{}"), 400,
                "request.invalid");
        assertProblem(send(gm, "POST", "/api/v1/campaigns/" + gm.campaign() + "/turns", "{nope"), 400,
                "request.invalid");
        assertThat(conversations.turns).hasSize(turns);
        assertThat(jobs.rows).hasSize(rows);
        assertThat(ask(gm, gm.campaign(), longest).statusCode()).isEqualTo(201);
    }

    @Test
    void anotherOwnersCampaignOrTurnIsNotFoundAndNothingIsRecordedOrStreamed() throws Exception {
        Gm gm = signUp();
        Gm them = signUp();
        UUID theirTurn = turnId(ask(them, them.campaign(), QUESTION));
        int turns = conversations.turns.size();

        assertProblem(ask(gm, them.campaign(), QUESTION), 404, "request.not_found");
        assertProblem(ask(gm, UUID.randomUUID(), QUESTION), 404, "request.not_found");
        assertProblem(send(gm, "POST", "/api/v1/campaigns/not-an-id/turns", "{\"question\":\"x\"}"), 404,
                "request.not_found");
        assertThat(conversations.turns).hasSize(turns);
        for (String path : List.of("/api/v1/turns/" + theirTurn + "/events", "/api/v1/turns/" + UUID.randomUUID()
                + "/events", "/api/v1/turns/nope/events")) {
            HttpResponse<String> refused = get(gm, path);
            assertProblem(refused, 404, "request.not_found");
            assertThat(refused.body()).doesNotContain(theirTurn.toString());
        }
        // Without a session, nothing at all.
        HttpResponse<String> anonymous = client.send(HttpRequest.newBuilder(uri("/api/v1/conversations/current"))
                .header("Authorization", "Bearer " + CREDENTIAL).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
    }

    // --- the internal route's other refusals

    @Test
    void eventsNeedTheLiveGrantAValidBatchAndAKindThatStreams() throws Exception {
        Gm gm = signUp();
        accepted(gm, 1);
        UUID turnId = turnId(ask(gm, gm.campaign(), QUESTION));
        Claim claim = claim(jobOf(turnId));
        String good = eventsBody(1, List.of(delta("a")), List.of());

        assertProblem(sendAsWorker("POST", claim.path("events"), good, "A".repeat(43)), 403, "job.grant_refused");
        assertProblem(sendAsWorker("POST", claim.path("events"), good, null), 403, "job.grant_refused");
        for (String bad : List.of(eventsBody(0, List.of(delta("a")), List.of()),
                "{\"batch\":1,\"events\":[{\"type\":\"tool\",\"text\":\"a\"}]}",
                "{\"batch\":1,\"events\":[{\"type\":\"delta\",\"text\":\"\"}]}",
                "{\"batch\":1,\"events\":[{\"type\":\"citation\",\"passageId\":\"" + UUID.randomUUID() + "\"}]}",
                "{\"batch\":1,\"events\":[{\"type\":\"citation\",\"passageId\":\"" + UUID.randomUUID()
                        + "\",\"offset\":-1}]}",
                "{\"batch\":1}",
                "{\"batch\":1,\"events\":[],\"usage\":[{\"input\":1,\"cacheRead\":0,\"cacheWrite\":0,"
                        + "\"output\":1,\"reasoning\":0}]}",
                "{\"batch\":1,\"events\":[],\"usage\":[{\"callId\":\"" + UUID.randomUUID() + "\",\"input\":-1,"
                        + "\"cacheRead\":0,\"cacheWrite\":0,\"output\":1,\"reasoning\":0}]}")) {
            assertProblem(sendAsWorker("POST", claim.path("events"), bad, claim.grant()), 400, "job.events_invalid");
        }
        // The limits: 500 events, 16,384 code points a delta, 16 usages, a response id of 256 printable characters.
        Map<String, Object> deltaWithPassage = delta("a");
        deltaWithPassage.put("passageId", UUID.randomUUID().toString());
        Map<String, Object> citationWithText = citation(UUID.randomUUID(), 0);
        citationWithText.put("text", "a");
        List<String> pastTheLimits = List.of(
                eventsBody(1, java.util.Collections.nCopies(501, delta("a")), List.of()),
                eventsBody(1, List.of(delta("😀".repeat(16_385))), List.of()),
                eventsBody(1, List.of(deltaWithPassage), List.of()),
                eventsBody(1, List.of(citationWithText), List.of()),
                eventsBody(1, List.of(), java.util.stream.IntStream.range(0, 17)
                        .mapToObj(i -> usage(Ids.newId(), null, 1, 1)).toList()),
                eventsBody(1, List.of(), List.of(usage(Ids.newId(), "r".repeat(257), 1, 1))),
                eventsBody(1, List.of(), List.of(usage(Ids.newId(), "chatcmpl 1", 1, 1))));
        for (String bad : pastTheLimits) {
            assertProblem(sendAsWorker("POST", claim.path("events"), bad, claim.grant()), 400, "job.events_invalid");
        }
        assertThat(usage.rows.values().stream().filter(row -> row.jobId().equals(claim.jobId()))).isEmpty();
        for (String malformed : List.of("{\"batch\":\"x\"", "{\"batch\":1,\"events\":[],\"usage\":[{\"input\":1}]}")) {
            assertProblem(sendAsWorker("POST", claim.path("events"), malformed, claim.grant()), 400,
                    "request.invalid");
        }
        assertThat(conversations.batches.values().stream().filter(batch -> batch.turnId().equals(turnId))).isEmpty();
        // An empty batch with usage only is a batch.
        UUID call = Ids.newId();
        assertThat(postEvents(claim, 1, List.of(), List.of(usage(call, null, 3, 0))).statusCode()).isEqualTo(200);
        assertThat(usage.rows).containsKey(call);
        // A done claim posts nothing more.
        report(claim, "{\"outcome\":\"done\"}");
        assertProblem(postEvents(claim, 2, List.of(delta("b")), List.of()), 403, "job.grant_refused");
        assertThat(streamAll(gm, turnId, null)).extracting(Event::id, Event::name)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("1", "done"));
    }

    @Test
    void aBatchWithUsageOnlyIsNotADeliveryAndTheFailedAttemptIsRetried() throws Exception {
        Gm gm = signUp();
        accepted(gm, 1);
        UUID turnId = turnId(ask(gm, gm.campaign(), QUESTION));
        UUID jobId = jobOf(turnId);
        Claim claim = claim(jobId);
        assertThat(postEvents(claim, 1, List.of(), List.of(usage(Ids.newId(), null, 3, 0))).statusCode())
                .isEqualTo(200);

        assertThat(report(claim, "{\"outcome\":\"failed\",\"code\":\"model.unavailable\"}").statusCode())
                .isEqualTo(204);

        assertThat(jobs.row(jobId).state()).isIn("waiting", "offered");
        assertThat(conversations.batches.values().stream().filter(batch -> batch.turnId().equals(turnId)))
                .extracting(EventBatchRow::claimId).doesNotContainNull();
    }

    // --- Story 2.5: sources under every answer

    @Test
    void citationsOnTwoPlacesBecomeTwoReferencesNumberedByFirstCitation() throws Exception {
        Gm gm = signUp();
        Source source = source(gm, gm.campaign(), List.of(3, 7));
        UUID a = source.passages().get(0);
        UUID b = source.passages().get(1);

        UUID turnId = answered(gm, QUESTION, List.of(delta("Um."), citation(a, 3), delta(" Dois."), citation(b, 9),
                delta(" Três."), citation(a, 15)));

        JsonNode done = done(gm, turnId, null);
        assertThat(done.path("silent").asBoolean()).isFalse();
        assertThat(done.path("references")).hasSize(2);
        JsonNode first = done.path("references").get(0);
        JsonNode second = done.path("references").get(1);
        assertThat(first.path("number").asInt()).isEqualTo(1);
        assertThat(strings(first.path("passageIds"))).containsExactly(a.toString());
        assertThat(first.path("page").asInt()).isEqualTo(3);
        assertThat(second.path("number").asInt()).isEqualTo(2);
        assertThat(strings(second.path("passageIds"))).containsExactly(b.toString());
        assertThat(second.path("page").asInt()).isEqualTo(7);
        assertThat(second.path("materialName").asString()).isEqualTo(MATERIAL_NAME);
        assertThat(strings(second.path("sectionPath"))).isEqualTo(SECTION);
        // Stored by value with the turn: version, material, page and passages in the clear, name and path encrypted.
        List<TurnReferenceRow> stored = references.anyOfTurn(turnId);
        assertThat(stored).extracting(TurnReferenceRow::number).containsExactly(1, 2);
        assertThat(stored.getFirst().versionId()).isEqualTo(source.versionId());
        assertThat(stored.getFirst().materialId()).isEqualTo(source.materialId());
        assertThat(stored.getFirst().ownerId()).isEqualTo(gm.id());
        assertThat(stored.get(1).passageIds()).containsExactly(b);
    }

    @Test
    void passagesCitedAtTheSamePlaceShareOneReference() throws Exception {
        Gm gm = signUp();
        List<UUID> passages = accepted(gm, 2);

        UUID turnId = answered(gm, QUESTION, List.of(delta("Um."), citation(passages.get(1), 3), delta(" Dois."),
                citation(passages.get(0), 9), citation(passages.get(1), 9)));

        JsonNode done = done(gm, turnId, null);
        assertThat(done.path("references")).hasSize(1);
        assertThat(done.path("references").get(0).path("number").asInt()).isEqualTo(1);
        assertThat(strings(done.path("references").get(0).path("passageIds")))
                .containsExactly(passages.get(1).toString(), passages.get(0).toString());
    }

    @Test
    void aForeignOrOutOfScopeIdIsDroppedAndTheOtherReferencesStay() throws Exception {
        Gm gm = signUp();
        Gm them = signUp();
        List<UUID> mine = accepted(gm, 1);
        UUID theirs = accepted(them, 1).getFirst();
        UUID outside = source(gm, null, List.of(2)).passages().getFirst();
        UUID unknown = Ids.newId();

        UUID turnId = answered(gm, QUESTION, List.of(delta("Um."), citation(theirs, 3), citation(mine.getFirst(), 3),
                citation(outside, 3), citation(unknown, 3)));

        JsonNode done = done(gm, turnId, null);
        assertThat(done.path("references")).hasSize(1);
        assertThat(strings(done.path("references").get(0).path("passageIds")))
                .containsExactly(mine.getFirst().toString());
        // The line names the dropped count, never an id or a text.
        assertThat(logged.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage()).isEqualTo("conversation.references_stored");
            Map<String, Object> pairs = new LinkedHashMap<>();
            event.getKeyValuePairs().forEach(pair -> pairs.put(pair.key, pair.value));
            assertThat(pairs).containsEntry("turnId", turnId).containsEntry("cited", 4).containsEntry("dropped", 3)
                    .containsEntry("references", 1);
        });
        assertThat(logged.list).allSatisfy(event -> assertThat(event.getFormattedMessage() + " "
                + event.getKeyValuePairs()).doesNotContain(theirs.toString()).doesNotContain(outside.toString())
                .doesNotContain(unknown.toString()).doesNotContain(MATERIAL_NAME).doesNotContain("Magias"));
    }

    @Test
    void anAnswerWhoseEveryCitationIsDroppedIsSilent() throws Exception {
        Gm gm = signUp();
        accepted(gm, 1);
        Gm them = signUp();
        UUID theirs = accepted(them, 1).getFirst();

        UUID turnId = answered(gm, QUESTION, List.of(delta("Algo."), citation(theirs, 5), citation(Ids.newId(), 5)));

        JsonNode done = done(gm, turnId, null);
        assertThat(done.path("silent").asBoolean()).isTrue();
        assertThat(done.path("references")).isEmpty();
        assertThat(references.anyOfTurn(turnId)).isEmpty();
    }

    @Test
    void anAnswerWithNoCitationIsSilent() throws Exception {
        Gm gm = signUp();
        accepted(gm, 1);

        UUID turnId = answered(gm, QUESTION, List.of(delta("Ainda não sei: as fontes não dizem nada sobre isso.")));

        JsonNode done = done(gm, turnId, null);
        assertThat(done.path("silent").asBoolean()).isTrue();
        assertThat(done.path("references")).isEmpty();
        // An answer with no event at all is silent too.
        assertThat(done(gm, answered(gm, QUESTION, List.of()), null).path("silent").asBoolean()).isTrue();
    }

    @Test
    void aReplayBeforeDoneSendsTheSameReferencesWithRemovedAsOfNow() throws Exception {
        Gm gm = signUp();
        Source kept = source(gm, gm.campaign(), List.of(1));
        Source discarded = source(gm, gm.campaign(), List.of(2));
        Source deleted = source(gm, gm.campaign(), List.of(3));
        UUID turnId = answered(gm, QUESTION, List.of(delta("Um."), citation(kept.passages().getFirst(), 3),
                citation(discarded.passages().getFirst(), 3), citation(deleted.passages().getFirst(), 3)));
        JsonNode before = done(gm, turnId, null);
        assertThat(before.path("references")).extracting(reference -> reference.path("removed").asBoolean())
                .containsExactly(false, false, false);

        as(gm.id(), () -> {
            materials.discardVersion(discarded.versionId(), clock.instant());
            materials.deletePassages(deleted.versionId());
            materials.deleteVersion(deleted.versionId());
            return materials.deleteMaterial(deleted.materialId());
        });

        JsonNode after = done(gm, turnId, "2");
        assertThat(after.path("references")).hasSize(3);
        assertThat(after.path("references")).extracting(reference -> reference.path("number").asInt())
                .containsExactly(1, 2, 3);
        assertThat(after.path("references")).extracting(reference -> reference.path("removed").asBoolean())
                .containsExactly(false, true, true);
        // Stored by value: the removed sources still show their name, section path and page.
        assertThat(after.path("references").get(2).path("materialName").asString()).isEqualTo(MATERIAL_NAME);
        assertThat(after.path("references").get(2).path("page").asInt()).isEqualTo(3);
        assertThat(strings(after.path("references").get(2).path("passageIds")))
                .containsExactly(deleted.passages().getFirst().toString());
    }

    @Test
    void aVersionDiscardedWithItsRemovalFinishedShowsItsReferenceRemoved() throws Exception {
        Gm gm = signUp();
        Source source = source(gm, gm.campaign(), List.of(1));
        UUID turnId = answered(gm, QUESTION, List.of(delta("Um."), citation(source.passages().getFirst(), 3)));

        // Discarded, then its removal finished (VersionRemoval clears the flag): the permanent state.
        as(gm.id(), () -> {
            materials.discardVersion(source.versionId(), clock.instant());
            return materials.clearRemovalPending(source.versionId(), clock.instant());
        });

        assertThat(materials.versions.get(source.versionId()).removalPending()).isFalse();
        assertThat(done(gm, turnId, null).path("references").get(0).path("removed").asBoolean()).isTrue();
    }

    @Test
    void aFailedAnswerStoresNoReference() throws Exception {
        Gm gm = signUp();
        List<UUID> passages = accepted(gm, 1);
        UUID turnId = turnId(ask(gm, gm.campaign(), QUESTION));
        Claim claim = claim(jobOf(turnId));
        postEvents(claim, 1, List.of(delta("Fire Bolt "), citation(passages.getFirst(), 5)), List.of());

        report(claim, "{\"outcome\":\"failed\",\"code\":\"model.unavailable\"}");

        assertThat(streamAll(gm, turnId, null)).extracting(Event::name).containsExactly("delta", "citation", "error");
        assertThat(references.anyOfTurn(turnId)).isEmpty();
    }

    @Test
    void everyCompletedTurnHasAReferenceOrIsSilent() throws Exception {
        Gm gm = signUp();
        List<UUID> passages = accepted(gm, 2);
        UUID cited = answered(gm, QUESTION, List.of(delta("Um."), citation(passages.getFirst(), 3)));
        UUID uncited = answered(gm, QUESTION, List.of(delta("as fontes não dizem nada sobre isso.")));
        UUID allDropped = answered(gm, QUESTION, List.of(delta("Um."), citation(Ids.newId(), 3)));

        for (UUID turnId : List.of(cited, uncited, allDropped)) {
            assertThat(conversations.turns.get(turnId).modelKnowledge()).isFalse();
            JsonNode done = done(gm, turnId, null);
            JsonNode replayed = done(gm, turnId, "1");
            assertThat(replayed).isEqualTo(done);
            assertThat(done.path("references").isEmpty()).isEqualTo(references.anyOfTurn(turnId).isEmpty());
        }
        assertThat(done(gm, cited, null).path("silent").asBoolean()).isFalse();
        assertThat(done(gm, uncited, null).path("silent").asBoolean()).isTrue();
        assertThat(done(gm, allDropped, null).path("silent").asBoolean()).isTrue();
    }

    @Test
    void aReferencesNameAndSectionPathAreStoredOnlyEncrypted() throws Exception {
        Gm gm = signUp();
        List<UUID> passages = accepted(gm, 1);
        UUID turnId = answered(gm, QUESTION, List.of(delta("Um."), citation(passages.getFirst(), 3)));

        TurnReferenceRow row = references.anyOfTurn(turnId).getFirst();

        assertThat(new String(row.materialName(), StandardCharsets.ISO_8859_1)).doesNotContain("SRD")
                .doesNotContain("Livro");
        assertThat(new String(row.sectionPath(), StandardCharsets.ISO_8859_1)).doesNotContain("Magias")
                .doesNotContain("Fire Bolt");
        assertThat(row.toString()).doesNotContain("SRD").doesNotContain("Magias");
        // Under another row's binding the ciphertext does not open.
        assertThat(as(gm.id(), () -> {
            try {
                vault.decryptText(new io.github.gmshield.vault.service.Binding("turn_reference", "material_name",
                        Ids.newId(), gm.id()), row.materialName());
                return false;
            } catch (RuntimeException refused) {
                return true;
            }
        })).isTrue();
    }

    // --- Story 2.6: stop an answer

    @Test
    void aStopMidAnswerEndsTheTurnAtOnceWithItsReferencesAndTheNextBatchIsToldToStop() throws Exception {
        Gm gm = signUp();
        List<UUID> passages = accepted(gm, 1);
        UUID turnId = turnId(ask(gm, gm.campaign(), QUESTION));
        UUID jobId = jobOf(turnId);
        Claim claim = claim(jobId);
        for (HttpResponse<String> posted : List.of(postEvents(claim, 1, List.of(delta("Fire Bolt ")), List.of()),
                postEvents(claim, 2, List.of(delta("causa 1d10"), citation(passages.getFirst(), 10)), List.of()),
                postEvents(claim, 3, List.of(delta(" de dano")), List.of()))) {
            assertThat(posted.statusCode()).as(posted.body()).isEqualTo(200);
            assertThat(json(posted).path("stop").asBoolean()).isFalse();
        }

        try (Stream open = open(gm, turnId, "4")) {
            HttpResponse<String> stopped = stopTurn(gm, turnId);

            assertThat(stopped.statusCode()).as(stopped.body()).isEqualTo(204);
            assertThat(stopped.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
            // The open stream ends at once with done, before the worker hears of the stop.
            List<Event> live = open.events(Duration.ofSeconds(2));
            assertThat(live).extracting(Event::id, Event::name)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("5", "done"));
        }
        assertThat(conversations.turns.get(turnId).stoppedAt()).isEqualTo(clock.instant());
        assertThat(jobs.row(jobId).state()).isEqualTo("running");

        UUID call = Ids.newId();
        HttpResponse<String> fourth = postEvents(claim, 4, List.of(delta(" de fogo.")),
                List.of(usage(call, "chatcmpl-9", 300, 12, true)));
        assertThat(fourth.statusCode()).as(fourth.body()).isEqualTo(200);
        assertThat(json(fourth).path("stop").asBoolean()).isTrue();
        assertThat(conversations.batches.values().stream().filter(batch -> batch.turnId().equals(turnId)))
                .extracting(EventBatchRow::batch).doesNotContain(4);
        assertThat(usage.rows.get(call).estimated()).isTrue();
        assertThat(usage.rows.get(call).output()).isEqualTo(12);
        assertThat(report(claim, "{\"outcome\":\"done\"}").statusCode()).isEqualTo(204);
        assertThat(jobs.row(jobId).state()).isEqualTo("done");

        List<Event> events = streamAll(gm, turnId, null);
        assertThat(events).extracting(Event::name).containsExactly("delta", "delta", "citation", "delta", "done");
        JsonNode done = done(gm, turnId, null);
        assertThat(done.path("stopped").asBoolean()).isTrue();
        assertThat(done.path("silent").asBoolean()).isFalse();
        assertThat(done.path("references")).extracting(reference -> reference.path("number").asInt())
                .containsExactly(1);
        assertThat(references.anyOfTurn(turnId)).hasSize(1);
        // Stopping again changes nothing.
        assertThat(stopTurn(gm, turnId).statusCode()).isEqualTo(204);
        assertThat(streamAll(gm, turnId, null)).hasSize(5);
        assertThat(logged.list).allSatisfy(event -> assertThat(event.getFormattedMessage() + " "
                + event.getKeyValuePairs()).doesNotContain("Fire Bolt").doesNotContain("dano"));
    }

    @Test
    void aStopWithNothingCitedIsStoppedAndNeverSilent() throws Exception {
        Gm gm = signUp();
        accepted(gm, 1);
        UUID turnId = turnId(ask(gm, gm.campaign(), QUESTION));
        Claim claim = claim(jobOf(turnId));
        postEvents(claim, 1, List.of(delta("Fire Bolt ")), List.of());

        assertThat(stopTurn(gm, turnId).statusCode()).isEqualTo(204);

        assertThat(streamAll(gm, turnId, null)).extracting(Event::name).containsExactly("delta", "done");
        JsonNode done = done(gm, turnId, null);
        assertThat(done.path("stopped").asBoolean()).isTrue();
        assertThat(done.path("silent").asBoolean()).isFalse();
        assertThat(done.path("references")).isEmpty();
        assertThat(references.anyOfTurn(turnId)).isEmpty();
    }

    @Test
    void aStopBeforeTheClaimCancelsTheJobAndEndsTheTurnWithNoText() throws Exception {
        Gm gm = signUp();
        accepted(gm, 1);
        UUID turnId = turnId(ask(gm, gm.campaign(), QUESTION));
        UUID jobId = jobOf(turnId);
        assertThat(jobs.row(jobId).state()).isEqualTo("offered");

        assertThat(stopTurn(gm, turnId).statusCode()).isEqualTo(204);

        assertThat(jobs.row(jobId).state()).isEqualTo("cancelled");
        assertThat(sendAsWorker("POST", "/internal/v1/jobs/" + jobId + "/claims", "{\"providers\":[\"azure-openai\"]}",
                null).statusCode()).isEqualTo(409);
        List<Event> events = streamAll(gm, turnId, null);
        assertThat(events).extracting(Event::id, Event::name)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("1", "done"));
        assertThat(done(gm, turnId, null).path("stopped").asBoolean()).isTrue();
    }

    @Test
    void aStopAfterTheEndChangesNothing() throws Exception {
        Gm gm = signUp();
        accepted(gm, 1);
        UUID finished = answered(gm, QUESTION, List.of(delta("Um.")));
        UUID failedTurn = turnId(ask(gm, gm.campaign(), QUESTION));
        Claim claim = claim(jobOf(failedTurn));
        postEvents(claim, 1, List.of(delta("a")), List.of());
        report(claim, "{\"outcome\":\"failed\",\"code\":\"model.unavailable\"}");
        long batches = conversations.batches.size();

        for (UUID turnId : List.of(finished, failedTurn)) {
            assertThat(stopTurn(gm, turnId).statusCode()).isEqualTo(204);
            assertThat(conversations.turns.get(turnId).stoppedAt()).isNull();
        }

        assertThat(conversations.batches).hasSize((int) batches);
        assertThat(done(gm, finished, null).path("stopped").asBoolean()).isFalse();
        assertThat(streamAll(gm, failedTurn, null)).extracting(Event::name).containsExactly("delta", "error");
        assertThat(jobs.row(jobOf(finished)).state()).isEqualTo("done");
        assertThat(jobs.row(jobOf(failedTurn)).state()).isEqualTo("failed");
    }

    @Test
    void aStopThenALapseFailsTheJobWithoutOfferingItAgainOrASecondFinalEvent() throws Exception {
        Gm gm = signUp();
        accepted(gm, 1);
        UUID turnId = turnId(ask(gm, gm.campaign(), QUESTION));
        UUID jobId = jobOf(turnId);
        claim(jobId);
        assertThat(stopTurn(gm, turnId).statusCode()).isEqualTo(204);

        clock.advance(Duration.ofSeconds(100));
        sweep.sweep();

        assertThat(jobs.row(jobId).state()).isEqualTo("failed");
        assertThat(jobs.row(jobId).outcomeCode()).isEqualTo("job.claim_lapsed");
        assertThat(stream.offered("gmshield:work:interactive").stream().filter(jobId::equals)).hasSize(1);
        assertThat(streamAll(gm, turnId, null)).extracting(Event::name).containsExactly("done");
        assertThat(logged.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage()).isEqualTo("job.failed");
            assertThat(event.getKeyValuePairs().toString()).contains(jobId.toString()).contains("job.claim_lapsed");
        });
    }

    @Test
    void aStopThenAFailureEndsTheJobWithoutAnErrorEvent() throws Exception {
        Gm gm = signUp();
        List<UUID> passages = accepted(gm, 1);
        UUID turnId = turnId(ask(gm, gm.campaign(), QUESTION));
        UUID jobId = jobOf(turnId);
        Claim claim = claim(jobId);
        postEvents(claim, 1, List.of(delta("Fire Bolt"), citation(passages.getFirst(), 9)), List.of());
        stopTurn(gm, turnId);
        int stored = references.anyOfTurn(turnId).size();

        assertThat(report(claim, "{\"outcome\":\"failed\",\"code\":\"model.unavailable\"}").statusCode())
                .isEqualTo(204);

        assertThat(jobs.row(jobId).state()).isEqualTo("failed");
        assertThat(streamAll(gm, turnId, null)).extracting(Event::name).containsExactly("delta", "citation", "done");
        assertThat(references.anyOfTurn(turnId)).hasSize(stored).hasSize(1);
    }

    @Test
    void anotherOwnersTurnCannotBeStopped() throws Exception {
        Gm gm = signUp();
        Gm them = signUp();
        accepted(them, 1);
        UUID theirTurn = turnId(ask(them, them.campaign(), QUESTION));
        UUID theirJob = jobOf(theirTurn);

        for (String id : List.of(theirTurn.toString(), UUID.randomUUID().toString(), "nope")) {
            HttpResponse<String> refused = send(gm, "POST", "/api/v1/turns/" + id + "/stop", "");
            assertProblem(refused, 404, "request.not_found");
            assertThat(refused.body()).doesNotContain(theirTurn.toString());
        }

        assertThat(jobs.row(theirJob).state()).isEqualTo("offered");
        assertThat(conversations.anyTurnOfJob(theirJob).orElseThrow().stoppedAt()).isNull();
        assertThat(conversations.batches.values().stream().filter(batch -> batch.turnId().equals(theirTurn)))
                .isEmpty();
    }

    // --- helpers

    private HttpResponse<String> stopTurn(Gm gm, UUID turnId) throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/api/v1/turns/" + turnId + "/stop"))
                .header("Authorization", "Bearer " + CREDENTIAL).header("X-Session-Token", gm.session())
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private List<UUID> accepted(Gm gm, int count) {
        return source(gm, gm.campaign(), java.util.Collections.nCopies(count, 1)).passages();
    }

    /** A material, its version and its passages: one per page given, in that order. */
    record Source(UUID materialId, UUID versionId, List<UUID> passages) {
    }

    /** An accepted material of {@code campaign} (none: out of every scope), one passage per page given. */
    private Source source(Gm gm, UUID campaign, List<Integer> pages) {
        return as(gm.id(), () -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            UUID materialId = Ids.newId();
            materials.insertMaterial(new MaterialRow(materialId, gm.id(),
                    TestMaterials.materialName(vault, materialId, gm.id(), MATERIAL_NAME), now, "rulebook", null,
                    campaign));
            UUID versionId = Ids.newId();
            materials.insertVersion(new VersionRow(versionId, gm.id(), materialId, "ready", "pdf", 1000L, now, now));
            List<PassageRow> rows = new ArrayList<>();
            for (int ordinal = 0; ordinal < pages.size(); ordinal++) {
                rows.add(TestMaterials.passage(vault, Ids.newId(), gm.id(), versionId, ordinal, pages.get(ordinal),
                        PASSAGE_TEXT + " " + ordinal, SECTION, now));
            }
            materials.insertPassages(rows);
            return new Source(materialId, versionId, rows.stream().map(PassageRow::id).toList());
        });
    }

    /** Asks, claims, posts the events in one batch and reports done; answers the turn. */
    private UUID answered(Gm gm, String question, List<Map<String, Object>> events) throws Exception {
        UUID turnId = turnId(ask(gm, gm.campaign(), question));
        Claim claim = claim(jobOf(turnId));
        if (!events.isEmpty()) {
            assertThat(postEvents(claim, 1, events, List.of()).statusCode()).isEqualTo(200);
        }
        assertThat(report(claim, "{\"outcome\":\"done\"}").statusCode()).isEqualTo(204);
        return turnId;
    }

    /**
     * The data of the turn's done event, after the invariant: silent exactly when not stopped and with no reference
     * (Story 2.6), and the turn has one final event, its last.
     */
    private JsonNode done(Gm gm, UUID turnId, String lastEventId) throws Exception {
        List<Event> events = streamAll(gm, turnId, lastEventId);
        assertThat(events).isNotEmpty();
        assertThat(events.getLast().name()).isEqualTo("done");
        assertThat(events).filteredOn(event -> "done".equals(event.name()) || "error".equals(event.name())).hasSize(1);
        JsonNode data = JSON.readTree(events.getLast().data());
        assertThat(data.path("stopped").isBoolean()).isTrue();
        assertThat(data.path("silent").isBoolean()).isTrue();
        assertThat(data.path("references").isArray()).isTrue();
        assertThat(data.path("silent").asBoolean())
                .isEqualTo(!data.path("stopped").asBoolean() && data.path("references").isEmpty());
        return data;
    }

    private static List<String> strings(JsonNode array) {
        List<String> found = new ArrayList<>();
        array.forEach(item -> found.add(item.asString()));
        return found;
    }

    private <T> T as(UUID owner, Supplier<T> work) {
        return OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status -> work.get()));
    }

    private UUID jobOf(UUID turnId) {
        return conversations.turns.get(turnId).jobId();
    }

    private static UUID turnId(HttpResponse<String> asked) {
        assertThat(asked.statusCode()).as(asked.body()).isEqualTo(201);
        return UUID.fromString(json(asked).path("id").asString());
    }

    private HttpResponse<String> ask(Gm gm, UUID campaign, String question) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        return send(gm, "POST", "/api/v1/campaigns/" + campaign + "/turns", JSON.writeValueAsString(body));
    }

    private Claim claim(UUID jobId) throws Exception {
        HttpResponse<String> claimed = sendAsWorker("POST", "/internal/v1/jobs/" + jobId + "/claims",
                "{\"providers\":[\"azure-openai\"]}", null);
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(201);
        JsonNode reply = json(claimed);
        assertThat(reply.path("model").path("provider").asString()).isEqualTo("azure-openai");
        return new Claim(jobId, reply.path("claimId").asString(), reply.path("grant").asString(), reply.path("input"));
    }

    private HttpResponse<String> report(Claim claim, String outcome) throws Exception {
        return sendAsWorker("POST", claim.path("outcome"), outcome, claim.grant());
    }

    private HttpResponse<String> postEvents(Claim claim, int batch, List<Map<String, Object>> events,
                                            List<Map<String, Object>> calls) throws Exception {
        return sendAsWorker("POST", claim.path("events"), eventsBody(batch, events, calls), claim.grant());
    }

    private static String eventsBody(int batch, List<Map<String, Object>> events, List<Map<String, Object>> calls) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batch", batch);
        body.put("events", events);
        body.put("usage", calls);
        return JSON.writeValueAsString(body);
    }

    private static Map<String, Object> delta(String text) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "delta");
        event.put("text", text);
        return event;
    }

    private static Map<String, Object> citation(UUID passageId, int offset) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "citation");
        event.put("passageId", passageId.toString());
        event.put("offset", offset);
        return event;
    }

    private static Map<String, Object> usage(UUID callId, String responseId, long input, long output) {
        return usage(callId, responseId, input, output, null);
    }

    private static Map<String, Object> usage(UUID callId, String responseId, long input, long output,
                                             Boolean estimated) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("callId", callId.toString());
        if (responseId != null) {
            call.put("responseId", responseId);
        }
        call.put("input", input);
        call.put("cacheRead", 0);
        call.put("cacheWrite", 0);
        call.put("output", output);
        call.put("reasoning", 0);
        if (estimated != null) {
            call.put("estimated", estimated);
        }
        return call;
    }

    private static void assertProblem(HttpResponse<String> answer, int status, String code) {
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(status);
        assertThat(json(answer).path("code").asString()).as(answer.body()).isEqualTo(code);
    }

    /** Every event of a turn whose answer has ended, from after {@code lastEventId}. */
    private List<Event> streamAll(Gm gm, UUID turnId, String lastEventId) throws Exception {
        try (Stream open = open(gm, turnId, lastEventId)) {
            assertThat(open.status()).isEqualTo(200);
            return open.events(Duration.ofSeconds(5));
        }
    }

    private Stream open(Gm gm, UUID turnId, String lastEventId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/v1/turns/" + turnId + "/events"))
                .header("Authorization", "Bearer " + CREDENTIAL).header("X-Session-Token", gm.session())
                .header("Accept", "text/event-stream").GET();
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        return new Stream(response);
    }

    /** An open event stream, read line by line on a thread of its own. */
    static final class Stream implements AutoCloseable {

        private static final String END = "\u0000end";
        private final HttpResponse<InputStream> response;
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

        Stream(HttpResponse<InputStream> response) {
            this.response = response;
            Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(),
                        StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                    }
                } catch (java.io.IOException closed) {
                    // the test closed the stream
                } finally {
                    lines.add(END);
                }
            });
        }

        int status() {
            return response.statusCode();
        }

        String contentType() {
            return response.headers().firstValue("Content-Type").orElse("");
        }

        /** The lines that arrive within {@code within}. */
        List<String> linesFor(Duration within) throws InterruptedException {
            List<String> found = new ArrayList<>();
            long deadline = System.nanoTime() + within.toNanos();
            while (System.nanoTime() < deadline) {
                String line = lines.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (line == null || END.equals(line)) {
                    break;
                }
                found.add(line);
            }
            return found;
        }

        /** The events until the server ends the stream (after done or error), within {@code within}. */
        List<Event> events(Duration within) throws InterruptedException {
            List<Event> events = new ArrayList<>();
            long deadline = System.nanoTime() + within.toNanos();
            String id = null;
            String name = null;
            StringBuilder data = new StringBuilder();
            while (true) {
                String line = lines.poll(Math.max(deadline - System.nanoTime(), 0), TimeUnit.NANOSECONDS);
                if (line == null || END.equals(line)) {
                    return events;
                }
                if (line.isEmpty()) {
                    if (name != null) {
                        events.add(new Event(id, name, data.toString()));
                        if (name.equals("done") || name.equals("error")) {
                            return events;
                        }
                    }
                    id = null;
                    name = null;
                    data.setLength(0);
                } else if (line.startsWith("id:")) {
                    id = line.substring(3).strip();
                } else if (line.startsWith("event:")) {
                    name = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).strip());
                }
            }
        }

        @Override
        public void close() throws java.io.IOException {
            response.body().close();
        }
    }

    private HttpResponse<String> get(Gm gm, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Session-Token", gm.session()).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(Gm gm, String method, String path, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Session-Token", gm.session()).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendAsWorker(String method, String path, String json, String grant) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + WORKER_CREDENTIAL)
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        if (grant != null) {
            request.header("X-Job-Grant", grant);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private Gm signUp() throws Exception {
        String sub = "conv-" + UUID.randomUUID();
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
        HttpResponse<String> campaign = client.send(HttpRequest.newBuilder(uri("/api/v1/campaigns/current"))
                .header("Authorization", "Bearer " + CREDENTIAL).header("X-Session-Token", session).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new Gm(row.id(), session, UUID.fromString(json(campaign).path("id").asString()));
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
