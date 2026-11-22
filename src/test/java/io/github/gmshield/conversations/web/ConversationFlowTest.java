package io.github.gmshield.conversations.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.data.LibraryTestStores;
import io.github.gmshield.limits.data.InMemoryUsageStore;
import io.github.gmshield.limits.data.UsageRow;
import io.github.gmshield.materials.data.IndexChangeRow;
import io.github.gmshield.materials.data.InMemoryIndexStore;
import io.github.gmshield.materials.data.InMemoryMaterialStore;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialSystemRow;
import io.github.gmshield.materials.data.MaterialTestStores;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.materials.service.TestMaterials;
import io.github.gmshield.scope.data.InMemoryScopeStore;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.data.DataKeyStore;
import io.github.gmshield.vault.data.InMemoryDataKeyStore;
import io.github.gmshield.vault.service.FileStore;
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
 *
 * <p>Story 2.7: every backend row of its I/O matrix: a cited PDF page and the next one, a text page with its range, a
 * superseded version still served, a version removed after the answer, a version neither ready nor superseded, a
 * foreign turn, no such reference; and an unreadable page, the picture's bytes and headers, malformed ids and
 * numbers, and the materials route that still refuses {@code ground=reference}.
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
    private static final String CURRENT = "/api/v1/conversations/current";

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
    private InMemoryIndexStore index;
    @Autowired
    private InMemoryLibraryStore library;
    @Autowired
    private InMemoryScopeStore scopes;
    @Autowired
    private Jobs jobService;
    @Autowired
    private JobSweep sweep;
    @Autowired
    private MutableClock clock;
    @Autowired
    private Vault vault;
    @Autowired
    private FileStore files;
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
        assertThat(current.has("indexing")).isTrue();
        assertThat(current.path("indexing").isNull()).isTrue();
        assertThat(current.has("indexingFailed")).isTrue();
        assertThat(current.path("indexingFailed").isNull()).isTrue();

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

        JsonNode current = json(get(gm, "/api/v1/conversations/current"));
        assertThat(current.path("hasSources").asBoolean()).isFalse();
        assertThat(current.path("indexing").isNull()).isTrue();
        HttpResponse<String> asked = ask(gm, gm.campaign(), "What does Fire Bolt do?");

        assertThat(asked.statusCode()).as(asked.body()).isEqualTo(201);
        assertThat(json(asked).path("language").asString()).isEqualTo("en");
        Claim claim = claim(jobOf(turnId(asked)));
        assertThat(claim.input().path("tiers").get(0).path("sources")).isEmpty();
        assertThat(claim.input().path("tiers").get(1).path("sources")).isEmpty();
    }

    // --- the indexing count (the GM's notice)

    @Test
    void theCurrentConversationCountsThePassagesStillBeingPreparedForSearchUntilNoneIsLeft() throws Exception {
        Gm gm = signUp();
        Gm them = signUp();
        Source srd = source(gm, gm.campaign(), java.util.Collections.nCopies(450, 1));
        Source coimbra = source(gm, gm.campaign(), java.util.Collections.nCopies(5, 1));
        Source outside = source(gm, null, java.util.Collections.nCopies(3, 1));
        Source theirs = source(them, them.campaign(), java.util.Collections.nCopies(4, 1));
        // The count's line is at DEBUG (asked every 5 s per tab): let this test see it.
        Logger scopeLog = (Logger) LoggerFactory.getLogger(io.github.gmshield.scope.service.Scopes.class);
        ch.qos.logback.classic.Level level = scopeLog.getLevel();
        scopeLog.setLevel(ch.qos.logback.classic.Level.DEBUG);
        // Matrix row "Nothing pending": every in-scope version indexed.
        JsonNode before = json(get(gm, CURRENT));
        assertThat(before.has("indexing")).isTrue();
        assertThat(before.path("indexing").isNull()).isTrue();
        List<UUID> changes = new ArrayList<>();
        try {
            // The SRD in three runs, as an accept makes them; Coimbra's one run, ended; an out-of-scope version and
            // another owner's version pending.
            UUID first = indexChange(gm, srd, 0, 199);
            UUID second = indexChange(gm, srd, 200, 399);
            UUID third = indexChange(gm, srd, 400, 449);
            UUID coimbraRun = indexChange(gm, coimbra, 0, 4);
            changes.addAll(List.of(first, second, third, coimbraRun, indexChange(gm, outside, 0, 2),
                    indexChange(them, theirs, 0, 3)));
            as(gm.id(), () -> jobService.cancel(coimbraRun));

            // Matrix row "Indexing": every SRD passage pending, Coimbra and the others not counted.
            assertIndexing(gm, 450);
            assertIndexing(them, 4);
            assertThat(json(get(gm, CURRENT)).path("indexingFailed").isNull()).isTrue();
            // Matrix row "One finishes": a run ends (cancelled: neither pending nor failed), then the next fails; the
            // count only falls, and the failed run is reported apart, with a retry to come.
            as(gm.id(), () -> jobService.cancel(first));
            assertIndexing(gm, 250);
            assertThat(json(get(gm, CURRENT)).path("indexingFailed").isNull()).isTrue();
            jobs.rows.computeIfPresent(second, (id, row) -> row.released("failed", "job.attempts_exhausted",
                    clock.instant()));
            assertIndexing(gm, 50);
            assertIndexingFailed(gm, 200, true);
            // Matrix row "Failed change": the version's only unended job fails, so nothing is pending any more; both
            // failed runs are reported.
            jobs.rows.computeIfPresent(third, (id, row) -> row.released("failed", "job.attempts_exhausted",
                    clock.instant()));
            JsonNode after = json(get(gm, CURRENT));
            assertThat(after.has("indexing")).isTrue();
            assertThat(after.path("indexing").isNull()).isTrue();
            assertThat(after.path("hasSources").asBoolean()).isTrue();
            assertIndexingFailed(gm, 250, true);
            assertIndexingFailed(them, null, false);
            // Matrix row "Retries used": once every failed run has used its retries, none remains.
            for (UUID run : List.of(second, third)) {
                IndexChangeRow row = index.changes.get(run);
                index.changes.put(run, new IndexChangeRow(row.jobId(), row.ownerId(), row.versionId(),
                        row.firstOrdinal(), row.lastOrdinal(), null, IndexChangeRow.MAX_RETRIES));
            }
            assertIndexingFailed(gm, 250, false);
            assertThat(logged.list).filteredOn(event -> "scope.indexing".equals(event.getFormattedMessage()))
                    .isNotEmpty().allSatisfy(event -> assertThat(event.getLevel())
                            .isEqualTo(ch.qos.logback.classic.Level.DEBUG))
                    .allSatisfy(event -> assertThat(event.getKeyValuePairs().toString())
                            .doesNotContain(PASSAGE_TEXT).doesNotContain(MATERIAL_NAME));
        } finally {
            scopeLog.setLevel(level);
            // Leave no index job behind for the other tests' lanes, whatever happened.
            for (UUID change : changes) {
                as(jobs.row(change).ownerId(), () -> jobService.cancel(change));
            }
        }
    }

    @Test
    void anAnswersPassagesAndReferencesReadsNeverListItsPendingPassages() throws Exception {
        Gm gm = signUp();
        Source source = source(gm, gm.campaign(), List.of(1, 1, 2));
        UUID run = indexChange(gm, source, 0, 2);
        try {
            UUID turnId = turnId(ask(gm, gm.campaign(), QUESTION));
            Claim claim = claim(jobOf(turnId));
            // The question input lists them: the claim resolves the whole scope.
            assertThat(strings(claim.input().path("tiers").get(0).path("pending")))
                    .containsExactlyElementsOf(source.passages().stream().map(UUID::toString).toList());
            int from = logged.list.size();

            // Each passages read (up to ~50 a question while indexing) and the references check only the sources.
            HttpResponse<String> read = sendAsWorker("POST", claim.path("passages"),
                    "{\"ids\":[\"" + source.passages().getFirst() + "\"]}", claim.grant());
            assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
            assertThat(json(read).path("passages").get(0).path("tier").asString()).isEqualTo("campaign");
            assertThat(postEvents(claim, 1, List.of(delta("Um."), citation(source.passages().getFirst(), 3)),
                    List.of()).statusCode()).isEqualTo(200);
            assertThat(report(claim, "{\"outcome\":\"done\"}").statusCode()).isEqualTo(204);

            assertThat(done(gm, turnId, null).path("references")).hasSize(1);
            List<Map<String, Object>> resolved = new ArrayList<>();
            for (ILoggingEvent event : List.copyOf(logged.list.subList(from, logged.list.size()))) {
                if ("scope.resolved".equals(event.getFormattedMessage())) {
                    Map<String, Object> pairs = new LinkedHashMap<>();
                    event.getKeyValuePairs().forEach(pair -> pairs.put(pair.key, pair.value));
                    resolved.add(pairs);
                }
            }
            assertThat(resolved).hasSize(2).allSatisfy(pairs -> assertThat(pairs)
                    .containsEntry("pendingRead", false).containsEntry("pending", 0));
        } finally {
            as(gm.id(), () -> jobService.cancel(run));
        }
    }

    // --- Story 3.3: a material moved while a question runs

    @Test
    void aMaterialMovedOutMidQuestionIsRefusedAtTheNextFetchWithNoJobAndAStoredReferenceToItStillOpens()
            throws Exception {
        Gm gm = signUp();
        Paged source = pdf(gm, gm.campaign(), 14, 13);
        // An earlier answer cites it: its reference is stored with that turn.
        UUID earlier = answered(gm, QUESTION, List.of(delta("Um."), citation(source.passages().getFirst(), 3)));
        UUID turnId = turnId(ask(gm, gm.campaign(), QUESTION));
        Claim claim = claim(jobOf(turnId));
        String ids = "{\"ids\":[\"" + source.passages().getFirst() + "\"]}";
        assertThat(sendAsWorker("POST", claim.path("passages"), ids, claim.grant()).statusCode()).isEqualTo(200);
        HttpResponse<String> created = send(gm, "POST", "/api/v1/systems", "{\"name\":\"Pathfinder\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        int jobsBefore = jobs.rows.size();

        // The GM moves it to another system: out of this campaign's scope.
        HttpResponse<String> moved = send(gm, "PATCH", "/api/v1/materials/" + source.materialId(),
                "{\"version\":0,\"belonging\":{\"systems\":[\"" + json(created).path("id").asString() + "\"]}}");
        assertThat(moved.statusCode()).as(moved.body()).isEqualTo(200);
        assertThat(json(moved).path("version").asLong()).isEqualTo(1);

        // The same question's next fetch resolves its scope again, and refuses it.
        assertProblem(sendAsWorker("POST", claim.path("passages"), ids, claim.grant()), 403, "scope.passage_refused");
        // No job came of the move: no index change and no removal, so nothing is written to the index.
        assertThat(jobs.rows).hasSize(jobsBefore);
        // The reference stored before the move still opens its page.
        assertThat(referencePage(gm, earlier, "1", "13").statusCode()).isEqualTo(200);
    }

    @Test
    void anAccountWithNoCampaignHasNoCurrentConversation() throws Exception {
        Gm gm = signUp();
        library.campaigns.remove(gm.campaign());

        assertProblem(get(gm, CURRENT), 404, "request.not_found");
    }

    @Test
    void aCampaignsConversationCountsItsQuestionsAndIsNotFoundWithoutOneOrForAnotherOwner() throws Exception {
        Gm gm = signUp();
        Gm them = signUp();
        String path = "/api/v1/campaigns/" + gm.campaign() + "/conversation";

        // Story 3.2: no question yet, no conversation.
        assertProblem(get(gm, path), 404, "request.not_found");
        UUID conversation = UUID.fromString(json(ask(gm, gm.campaign(), QUESTION)).path("conversationId").asString());
        ask(gm, gm.campaign(), "Outra pergunta?");

        HttpResponse<String> found = get(gm, path);
        assertThat(found.statusCode()).as(found.body()).isEqualTo(200);
        assertThat(found.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(json(found).path("id").asString()).isEqualTo(conversation.toString());
        assertThat(json(found).path("questions").asLong()).isEqualTo(2);
        // Another owner's campaign, an unknown one and a malformed id are the same not found.
        for (String other : List.of("/api/v1/campaigns/" + gm.campaign() + "/conversation",
                "/api/v1/campaigns/" + UUID.randomUUID() + "/conversation", "/api/v1/campaigns/nope/conversation")) {
            HttpResponse<String> refused = get(them, other);
            assertProblem(refused, 404, "request.not_found");
            assertThat(refused.body()).doesNotContain(conversation.toString());
        }
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

    // --- Story 3.5: a closed scope holds across three tiers

    @Test
    void aCampaignNeverChangedReadsAsTheStartingScopeAtVersionZeroWithItsMaterialsOn() throws Exception {
        Gm gm = signUp();
        UUID system = systemOf(gm);
        Source own = source(gm, gm.campaign(), List.of(1));
        Source ofSystem = systemSource(gm, system);
        UUID sibling = campaign(gm, system, "Mesa irmã");
        Source ofSibling = source(gm, sibling, List.of(1));

        HttpResponse<String> read = get(gm, scopePath(gm.campaign()));

        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode scope = json(read);
        assertThat(scope.propertyNames()).containsExactlyInAnyOrder("version", "campaignTier", "systemTier",
                "otherTier", "modelKnowledge", "materials");
        assertThat(scope.path("version").asLong()).isZero();
        assertThat(scope.path("campaignTier").asBoolean()).isTrue();
        assertThat(scope.path("systemTier").asBoolean()).isTrue();
        assertThat(scope.path("otherTier").asBoolean()).isFalse();
        assertThat(scope.path("modelKnowledge").asBoolean()).isFalse();
        // Every tier's materials, whatever the switch, in tier order, each on.
        assertThat(scope.path("materials")).extracting(m -> m.path("id").asString(), m -> m.path("tier").asString(),
                        m -> m.path("on").asBoolean(), m -> m.path("name").asString())
                .containsExactly(tuple(own.materialId().toString(), "campaign", true, MATERIAL_NAME),
                        tuple(ofSystem.materialId().toString(), "system", true, MATERIAL_NAME),
                        tuple(ofSibling.materialId().toString(), "other", true, MATERIAL_NAME));
        assertThat(scopes.rows).doesNotContainKey(gm.campaign());
        // Another owner's campaign, an unknown one and a malformed id are not found.
        Gm other = signUp();
        assertNotFound(get(other, scopePath(gm.campaign())));
        assertNotFound(get(gm, scopePath(Ids.newId())));
        assertNotFound(get(gm, "/api/v1/campaigns/not-an-id/scope"));
        assertNotFound(send(other, "PUT", scopePath(gm.campaign()), scopeBody(0, true, true, true, false, List.of())));
        assertThat(scopes.rows).doesNotContainKey(gm.campaign());
    }

    @Test
    void withTheDefaultsAnotherCampaignsHouseRuleIsNeverFoundFetchedOrCited() throws Exception {
        Gm gm = signUp();
        UUID system = systemOf(gm);
        Source own = source(gm, gm.campaign(), List.of(1));
        UUID sibling = campaign(gm, system, "Mesa da regra da casa");
        Source houseRule = source(gm, sibling, List.of(1));

        UUID turnId = turnId(ask(gm, gm.campaign(), QUESTION));
        Claim claim = claim(jobOf(turnId));

        assertThat(claim.input().path("tiers")).extracting(tier -> tier.path("tier").asString())
                .containsExactly("campaign", "system", "other");
        assertThat(claim.input().path("tiers").get(2).path("sources")).isEmpty();
        assertThat(claim.input().toString()).doesNotContain(houseRule.versionId().toString())
                .doesNotContain(houseRule.passages().getFirst().toString());
        assertProblem(sendAsWorker("POST", claim.path("passages"), "{\"ids\":[\"" + houseRule.passages().getFirst()
                + "\"]}", claim.grant()), 403, "scope.passage_refused");
        postEvents(claim, 1, List.of(delta("Um."), citation(houseRule.passages().getFirst(), 1),
                citation(own.passages().getFirst(), 2)), List.of());
        report(claim, "{\"outcome\":\"done\"}");
        assertThat(done(gm, turnId, null).path("references")).hasSize(1).first()
                .satisfies(reference -> assertThat(strings(reference.path("passageIds")))
                        .containsExactly(own.passages().getFirst().toString()));
        assertThat(references.rows.values()).noneMatch(row -> row.versionId().equals(houseRule.versionId()));
    }

    @Test
    void theOtherTierSwitchedOnServesTheNextQuestionLabelledOther() throws Exception {
        Gm gm = signUp();
        UUID system = systemOf(gm);
        source(gm, gm.campaign(), List.of(1));
        UUID farSystem = system(gm, "Outro sistema");
        UUID farCampaign = campaign(gm, farSystem, "Campanha distante");
        Source ofFarCampaign = source(gm, farCampaign, List.of(1));
        Source ofFarSystem = systemSource(gm, farSystem);

        HttpResponse<String> changed = send(gm, "PUT", scopePath(gm.campaign()),
                scopeBody(0, true, true, true, true, List.of()));

        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(changed.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(json(changed).path("version").asLong()).isEqualTo(1);
        assertThat(json(changed).path("otherTier").asBoolean()).isTrue();
        assertThat(json(changed).path("modelKnowledge").asBoolean()).isTrue();
        assertThat(json(get(gm, scopePath(gm.campaign()))).path("version").asLong()).isEqualTo(1);
        UUID turnId = turnId(ask(gm, gm.campaign(), QUESTION));
        TurnRow row = conversations.turns.get(turnId);
        assertThat(row.otherTier()).isTrue();
        assertThat(row.modelKnowledge()).isTrue();
        Claim claim = claim(row.jobId());
        JsonNode other = claim.input().path("tiers").get(2);
        assertThat(other.path("tier").asString()).isEqualTo("other");
        assertThat(strings(other.path("sources"))).containsExactlyInAnyOrder(ofFarCampaign.versionId().toString(),
                ofFarSystem.versionId().toString());
        HttpResponse<String> read = sendAsWorker("POST", claim.path("passages"), "{\"ids\":[\""
                + ofFarCampaign.passages().getFirst() + "\",\"" + ofFarSystem.passages().getFirst() + "\"]}",
                claim.grant());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(json(read).path("passages")).extracting(passage -> passage.path("tier").asString())
                .containsExactly("other", "other");
        // The current conversation counts the other tier's sources too.
        assertThat(json(get(gm, CURRENT)).path("hasSources").asBoolean()).isTrue();
        assertThat(system).isNotEqualTo(farSystem);
    }

    @Test
    void aMaterialSwitchedOffLeavesItsTierAndItsPassagesAreRefused() throws Exception {
        Gm gm = signUp();
        Source own = source(gm, gm.campaign(), List.of(1));
        Source ofSystem = systemSource(gm, systemOf(gm));

        JsonNode changed = json(send(gm, "PUT", scopePath(gm.campaign()),
                scopeBody(0, true, true, false, false, List.of(ofSystem.materialId()))));

        assertThat(changed.path("materials")).extracting(m -> m.path("id").asString(), m -> m.path("on").asBoolean())
                .containsExactly(tuple(own.materialId().toString(), true),
                        tuple(ofSystem.materialId().toString(), false));
        Claim claim = claim(jobOf(turnId(ask(gm, gm.campaign(), QUESTION))));
        assertThat(claim.input().path("tiers").get(1).path("sources")).isEmpty();
        assertThat(conversations.turns.values()).filteredOn(turn -> turn.jobId().equals(claim.jobId()))
                .singleElement().satisfies(turn -> assertThat(turn.materialsOff())
                        .containsExactly(ofSystem.materialId()));
        assertProblem(sendAsWorker("POST", claim.path("passages"), "{\"ids\":[\"" + ofSystem.passages().getFirst()
                + "\"]}", claim.grant()), 403, "scope.passage_refused");
        // With every tier off the current conversation has no source.
        send(gm, "PUT", scopePath(gm.campaign()), scopeBody(1, false, false, false, false, List.of()));
        assertThat(json(get(gm, CURRENT)).path("hasSources").asBoolean()).isFalse();
    }

    @Test
    void aChangeMidAnswerLeavesThatTurnsFrozenScopeAndTheNextTurnUsesTheNewOne() throws Exception {
        Gm gm = signUp();
        Source own = source(gm, gm.campaign(), List.of(1));
        UUID sibling = campaign(gm, systemOf(gm), "Mesa irmã");
        Source ofSibling = source(gm, sibling, List.of(1));
        UUID first = turnId(ask(gm, gm.campaign(), QUESTION));
        Claim firstClaim = claim(jobOf(first));

        assertThat(send(gm, "PUT", scopePath(gm.campaign()), scopeBody(0, true, true, true, false,
                List.of(own.materialId()))).statusCode()).isEqualTo(200);

        // The turn being answered keeps its frozen scope: its own material still reads, the other tier does not.
        HttpResponse<String> read = sendAsWorker("POST", firstClaim.path("passages"), "{\"ids\":[\""
                + own.passages().getFirst() + "\"]}", firstClaim.grant());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertProblem(sendAsWorker("POST", firstClaim.path("passages"), "{\"ids\":[\""
                + ofSibling.passages().getFirst() + "\"]}", firstClaim.grant()), 403, "scope.passage_refused");
        postEvents(firstClaim, 1, List.of(delta("Um."), citation(own.passages().getFirst(), 1)), List.of());
        report(firstClaim, "{\"outcome\":\"done\"}");
        assertThat(done(gm, first, null).path("references")).hasSize(1);
        // The next turn uses the new one.
        Claim next = claim(jobOf(turnId(ask(gm, gm.campaign(), QUESTION))));
        assertThat(next.input().path("tiers").get(0).path("sources")).isEmpty();
        assertThat(strings(next.input().path("tiers").get(2).path("sources")))
                .containsExactly(ofSibling.versionId().toString());
        assertProblem(sendAsWorker("POST", next.path("passages"), "{\"ids\":[\"" + own.passages().getFirst()
                + "\"]}", next.grant()), 403, "scope.passage_refused");
    }

    @Test
    void aStaleChangeAnUnknownMaterialAndAMalformedBodyChangeNothing() throws Exception {
        Gm gm = signUp();
        Source own = source(gm, gm.campaign(), List.of(1));
        Gm other = signUp();
        Source theirs = source(other, other.campaign(), List.of(1));
        Source unready = source(gm, gm.campaign(), List.of(1));
        as(gm.id(), () -> materials.changeVersionState(unready.versionId(), "processing",
                clock.instant().truncatedTo(ChronoUnit.MICROS)));
        assertThat(send(gm, "PUT", scopePath(gm.campaign()), scopeBody(0, true, true, false, false, List.of()))
                .statusCode()).isEqualTo(200);

        assertProblem(send(gm, "PUT", scopePath(gm.campaign()), scopeBody(0, false, false, false, false, List.of())),
                409, "request.stale_version");
        for (UUID id : List.of(theirs.materialId(), unready.materialId(), Ids.newId())) {
            assertProblem(send(gm, "PUT", scopePath(gm.campaign()), scopeBody(1, true, true, false, false,
                    List.of(id))), 400, "scope.material_unknown");
        }
        List<String> invalid = new ArrayList<>(List.of("{}", "null", "{\"version\":1}",
                "{\"version\":1,\"campaignTier\":true,\"systemTier\":true,\"otherTier\":false,"
                        + "\"modelKnowledge\":false}",
                "{\"campaignTier\":true,\"systemTier\":true,\"otherTier\":false,\"modelKnowledge\":false,"
                        + "\"materialsOff\":[]}",
                "{\"version\":1,\"systemTier\":true,\"otherTier\":false,\"modelKnowledge\":false,"
                        + "\"materialsOff\":[]}",
                "{\"version\":-1,\"campaignTier\":true,\"systemTier\":true,\"otherTier\":false,"
                        + "\"modelKnowledge\":false,\"materialsOff\":[]}",
                "{\"version\":1,\"campaignTier\":true,\"systemTier\":true,\"otherTier\":false,"
                        + "\"modelKnowledge\":false,\"materialsOff\":[null]}",
                "{\"version\":1,\"campaignTier\":true,\"systemTier\":true,\"otherTier\":false,"
                        + "\"modelKnowledge\":false,\"materialsOff\":[\"nope\"]}"));
        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            tooMany.add(own.materialId());
        }
        invalid.add(scopeBody(1, true, true, false, false, tooMany));
        for (String body : invalid) {
            assertProblem(send(gm, "PUT", scopePath(gm.campaign()), body), 400, "request.invalid");
        }
        JsonNode stored = json(get(gm, scopePath(gm.campaign())));
        assertThat(stored.path("version").asLong()).isEqualTo(1);
        assertThat(stored.path("campaignTier").asBoolean()).isTrue();
        assertThat(stored.path("materials")).allSatisfy(material -> assertThat(material.path("on").asBoolean())
                .isTrue());
        // 1000 ids (repeats of one material) is within the limit.
        List<UUID> thousand = new ArrayList<>(tooMany.subList(0, 1000));
        assertThat(send(gm, "PUT", scopePath(gm.campaign()), scopeBody(1, true, true, false, false, thousand))
                .statusCode()).isEqualTo(200);
    }

    @Test
    void aPageOfTheCurrentScopeIsServedAndAnythingElseIsNotFound() throws Exception {
        Gm gm = signUp();
        Paged own = pdf(gm, gm.campaign(), 2, 1);
        UUID sibling = campaign(gm, systemOf(gm), "Mesa irmã");
        Paged ofSibling = pdf(gm, sibling, 2, 1);
        Gm other = signUp();
        Paged theirs = pdf(other, other.campaign(), 2, 1);

        HttpResponse<String> read = get(gm, sourcePath(gm.campaign(), own.versionId(), "1"));

        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode page = json(read);
        assertThat(page.propertyNames()).containsExactlyInAnyOrder("page", "pageTotal", "picture", "lines", "text",
                "passages", "outcome");
        assertThat(page.path("page").asInt()).isEqualTo(1);
        assertThat(page.path("pageTotal").asInt()).isEqualTo(2);
        assertThat(page.path("passages").get(0).path("id").asString()).isEqualTo(own.passages().getFirst().toString());
        HttpResponse<byte[]> picture = sourcePicture(gm, gm.campaign(), own.versionId(), "2");
        assertThat(picture.statusCode()).isEqualTo(200);
        assertThat(picture.body()).isEqualTo(own.pictures().get(2));
        assertThat(picture.headers().firstValue("Content-Type").orElseThrow()).isEqualTo("image/webp");
        assertThat(picture.headers().firstValue("X-Content-Type-Options").orElseThrow()).isEqualTo("nosniff");
        // Out of scope (the other tier is off), another owner's version or campaign, unknown, malformed, past the end.
        assertNotFound(get(gm, sourcePath(gm.campaign(), ofSibling.versionId(), "1")));
        assertThat(sourcePicture(gm, gm.campaign(), ofSibling.versionId(), "2").statusCode()).isEqualTo(404);
        assertNotFound(get(gm, sourcePath(gm.campaign(), theirs.versionId(), "1")));
        assertNotFound(get(other, sourcePath(gm.campaign(), own.versionId(), "1")));
        assertNotFound(get(other, sourcePath(other.campaign(), own.versionId(), "1")));
        assertNotFound(get(gm, sourcePath(gm.campaign(), Ids.newId(), "1")));
        assertNotFound(get(gm, sourcePath(gm.campaign(), own.versionId(), "3")));
        assertNotFound(get(gm, sourcePath(gm.campaign(), own.versionId(), "0")));
        assertNotFound(get(gm, "/api/v1/campaigns/" + gm.campaign() + "/sources/nope/pages/1"));
        // The scope decides: with the other tier on the sibling's page opens; with its material off, it does not.
        send(gm, "PUT", scopePath(gm.campaign()), scopeBody(0, true, true, true, false, List.of()));
        assertThat(get(gm, sourcePath(gm.campaign(), ofSibling.versionId(), "1")).statusCode()).isEqualTo(200);
        send(gm, "PUT", scopePath(gm.campaign()), scopeBody(1, true, true, true, false,
                List.of(ofSibling.materialId())));
        assertNotFound(get(gm, sourcePath(gm.campaign(), ofSibling.versionId(), "1")));
        assertThat(sibling).isNotEqualTo(gm.campaign());
    }

    @Test
    void aStoredReferenceStillOpensAfterItsMaterialIsSwitchedOff() throws Exception {
        Gm gm = signUp();
        Paged source = pdf(gm, gm.campaign(), 2, 1);
        UUID turnId = answered(gm, QUESTION, List.of(delta("Um."), citation(source.passages().getFirst(), 1)));

        send(gm, "PUT", scopePath(gm.campaign()), scopeBody(0, true, true, false, false,
                List.of(source.materialId())));

        assertThat(referencePage(gm, turnId, "1", "1").statusCode()).isEqualTo(200);
        assertThat(referencePicture(gm, turnId, "1", "2").statusCode()).isEqualTo(200);
        assertNotFound(get(gm, sourcePath(gm.campaign(), source.versionId(), "1")));
        // Nothing new is drawn from it.
        Claim next = claim(jobOf(turnId(ask(gm, gm.campaign(), QUESTION))));
        assertThat(next.input().path("tiers").get(0).path("sources")).isEmpty();
    }

    private static String scopePath(UUID campaign) {
        return "/api/v1/campaigns/" + campaign + "/scope";
    }

    private static String sourcePath(UUID campaign, UUID version, String page) {
        return "/api/v1/campaigns/" + campaign + "/sources/" + version + "/pages/" + page;
    }

    private HttpResponse<byte[]> sourcePicture(Gm gm, UUID campaign, UUID version, String page) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(sourcePath(campaign, version, page) + "/picture"))
                .header("Authorization", "Bearer " + CREDENTIAL).header("X-Session-Token", gm.session())
                .header("Accept", "image/webp").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String scopeBody(long version, boolean campaignTier, boolean systemTier, boolean otherTier,
                                    boolean modelKnowledge, List<UUID> materialsOff) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("campaignTier", campaignTier);
        body.put("systemTier", systemTier);
        body.put("otherTier", otherTier);
        body.put("modelKnowledge", modelKnowledge);
        body.put("materialsOff", materialsOff.stream().map(UUID::toString).toList());
        return JSON.writeValueAsString(body);
    }

    /** The GM's current campaign's system. */
    private UUID systemOf(Gm gm) throws Exception {
        return UUID.fromString(json(get(gm, "/api/v1/campaigns/" + gm.campaign())).path("system").path("id")
                .asString());
    }

    /** A new system of the GM. */
    private UUID system(Gm gm, String name) throws Exception {
        HttpResponse<String> created = send(gm, "POST", "/api/v1/systems", JSON.writeValueAsString(Map.of("name", name)));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(json(created).path("id").asString());
    }

    /** A new campaign of the GM's system; the GM's first campaign stays the current one. */
    private UUID campaign(Gm gm, UUID system, String name) throws Exception {
        HttpResponse<String> created = send(gm, "POST", "/api/v1/campaigns",
                JSON.writeValueAsString(Map.of("name", name, "systemId", system.toString())));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(send(gm, "PUT", "/api/v1/campaigns/current", "{\"campaignId\":\"" + gm.campaign() + "\"}")
                .statusCode()).isEqualTo(200);
        return UUID.fromString(json(created).path("id").asString());
    }

    /** An accepted material attached to the system, with one passage. */
    private Source systemSource(Gm gm, UUID system) {
        Source made = source(gm, null, List.of(1));
        as(gm.id(), () -> {
            materials.insertMaterialSystem(new MaterialSystemRow(Ids.newId(), gm.id(), made.materialId(), system,
                    clock.instant().truncatedTo(ChronoUnit.MICROS)));
            return null;
        });
        return made;
    }

    // --- Story 2.7: open the cited page in the source view

    private static final List<Double> BOX = List.of(0.1, 0.2, 0.9, 0.3);
    private static final List<Double> LINE_BOX = List.of(0.1, 0.2, 0.9, 0.25);
    private static final String LINE = "Fire Bolt | 1d10 | 13";

    @Test
    void aCitedPdfPageIsServedWithItsLinesItsCitedPassageAndItsWebpPictureAndTheNextPageToo() throws Exception {
        Gm gm = signUp();
        Paged source = pdf(gm, gm.campaign(), 14, 13);
        UUID turnId = answered(gm, QUESTION, List.of(delta("Um."), citation(source.passages().getFirst(), 3)));

        HttpResponse<String> read = referencePage(gm, turnId, "1", "13");

        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode page = json(read);
        assertThat(page.path("page").asInt()).isEqualTo(13);
        assertThat(page.path("pageTotal").asInt()).isEqualTo(14);
        assertThat(page.path("picture").toString()).isEqualTo("{\"width\":1545,\"height\":2000}");
        assertThat(page.path("lines").toString())
                .isEqualTo("[{\"text\":\"" + LINE + "\",\"box\":[0.1,0.2,0.9,0.25]}]");
        assertThat(page.path("text").isNull()).isTrue();
        assertThat(page.path("passages").toString()).isEqualTo("[{\"id\":\"" + source.passages().getFirst()
                + "\",\"boxes\":[[0.1,0.2,0.9,0.3]]}]");
        assertThat(page.path("outcome").asString()).isEqualTo("read_directly");
        assertThat(page.propertyNames()).containsExactlyInAnyOrder("page", "pageTotal", "picture", "lines", "text",
                "passages", "outcome");

        HttpResponse<byte[]> picture = referencePicture(gm, turnId, "1", "13");
        assertThat(picture.statusCode()).isEqualTo(200);
        assertThat(picture.body()).isEqualTo(source.pictures().get(13));
        assertThat(picture.headers().firstValue("Content-Type").orElseThrow()).isEqualTo("image/webp");
        assertThat(picture.headers().firstValue("Content-Length").orElseThrow())
                .isEqualTo(String.valueOf(source.pictures().get(13).length));
        assertThat(picture.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(picture.headers().firstValue("X-Content-Type-Options").orElseThrow()).isEqualTo("nosniff");

        // The next page: served from the same version, nothing on it cited.
        JsonNode next = json(referencePage(gm, turnId, "1", "14"));
        assertThat(next.path("page").asInt()).isEqualTo(14);
        assertThat(next.path("passages")).isEmpty();
        assertThat(referencePicture(gm, turnId, "1", "14").body()).isEqualTo(source.pictures().get(14));
        // Any page from 1 to the page total; one past it is not found.
        assertThat(referencePage(gm, turnId, "1", "1").statusCode()).isEqualTo(200);
        assertNotFound(referencePage(gm, turnId, "1", "15"));
        assertThat(referencePicture(gm, turnId, "1", "15").statusCode()).isEqualTo(404);
        // Lines carry ids and numbers, never the page's text, the name or the section.
        assertThat(logged.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .isEqualTo("conversation.reference_page"));
        assertThat(logged.list).allSatisfy(event -> assertThat(event.getFormattedMessage() + " "
                + event.getKeyValuePairs()).doesNotContain("Fire Bolt").doesNotContain(MATERIAL_NAME)
                .doesNotContain("Magias"));
    }

    @Test
    void aTextPageIsServedWithItsTextAndTheCitedPassagesRangeAndHasNoPicture() throws Exception {
        Gm gm = signUp();
        String text = "x".repeat(40) + "Fire Bolt: a mote of fire, 1d10 Fire damage, at a target within range."
                + "y".repeat(30);
        Paged source = as(gm.id(), () -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Paged made = material(gm, gm.campaign(), "md", 3);
            PassageRow passage = TestMaterials.passage(vault, Ids.newId(), gm.id(), made.versionId(), 0, 2,
                    text.substring(40, 95), SECTION, null, List.of(40, 95), now);
            materials.insertPassages(List.of(passage));
            TestMaterials.textPage(materials, vault, gm.id(), made.versionId(), 2, text, now);
            return new Paged(made.materialId(), made.versionId(), List.of(passage.id()), Map.of());
        });
        UUID turnId = answered(gm, QUESTION, List.of(delta("Um."), citation(source.passages().getFirst(), 3)));

        JsonNode page = json(referencePage(gm, turnId, "1", "2"));

        assertThat(page.path("picture").isNull()).isTrue();
        assertThat(page.path("lines")).isEmpty();
        assertThat(page.path("text").asString()).isEqualTo(text);
        assertThat(page.path("passages").toString()).isEqualTo("[{\"id\":\"" + source.passages().getFirst()
                + "\",\"range\":[40,95]}]");
        assertThat(page.path("outcome").isNull()).isTrue();
        // A text page has no picture.
        assertNotFound(referencePicture(gm, turnId, "1", "2"));
    }

    @Test
    void aSupersededVersionsPageIsStillServedNeverTheNewerOne() throws Exception {
        Gm gm = signUp();
        Paged first = pdf(gm, gm.campaign(), 14, 13);
        UUID turnId = answered(gm, QUESTION, List.of(delta("Um."), citation(first.passages().getFirst(), 3)));

        // A newer version of the same material is accepted: the cited one is superseded.
        byte[] newer = webp(77);
        as(gm.id(), () -> {
            Instant now = clock.instant().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
            UUID second = Ids.newId();
            materials.insertVersion(new VersionRow(second, gm.id(), first.materialId(), "ready", "pdf", 1000L, now,
                    now, 14, "2", null));
            TestMaterials.picturePage(materials, vault, files, gm.id(), second, 13, newer, 100, 100, List.of(), now);
            return materials.changeVersionState(first.versionId(), "superseded", now);
        });

        JsonNode page = json(referencePage(gm, turnId, "1", "13"));
        assertThat(page.path("picture").toString()).isEqualTo("{\"width\":1545,\"height\":2000}");
        assertThat(page.path("passages").get(0).path("id").asString())
                .isEqualTo(first.passages().getFirst().toString());
        assertThat(referencePicture(gm, turnId, "1", "13").body()).isEqualTo(first.pictures().get(13));
    }

    @Test
    void aReferenceToAVersionSupersededByTheGmsAcceptStillOpensItsOldPage() throws Exception {
        Gm gm = signUp();
        Paged first = pdf(gm, gm.campaign(), 14, 13);
        UUID turnId = answered(gm, QUESTION, List.of(delta("Um."), citation(first.passages().getFirst(), 3)));
        // Story 3.4: a replacement reached review; the GM accepts it through the material's action.
        as(gm.id(), () -> {
            Instant now = clock.instant().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
            materials.insertVersion(new VersionRow(Ids.newId(), gm.id(), first.materialId(), "needs_review", "pdf",
                    1000L, now, now, 14, "2", null));
            return true;
        });

        HttpResponse<String> accepted = send(gm, "POST", "/api/v1/materials/" + first.materialId() + "/accept", "");

        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(materials.versions.get(first.versionId()).state()).isEqualTo("superseded");
        JsonNode page = json(referencePage(gm, turnId, "1", "13"));
        assertThat(page.path("passages").get(0).path("id").asString())
                .isEqualTo(first.passages().getFirst().toString());
        assertThat(referencePicture(gm, turnId, "1", "13").body()).isEqualTo(first.pictures().get(13));
    }

    @Test
    void aVersionRemovedAfterTheAnswerIsNotServed() throws Exception {
        Gm gm = signUp();
        Paged discarded = pdf(gm, gm.campaign(), 14, 13);
        Paged deleted = pdf(gm, gm.campaign(), 14, 13);
        UUID turnId = answered(gm, QUESTION, List.of(delta("Um."), citation(discarded.passages().getFirst(), 3),
                citation(deleted.passages().getFirst(), 5)));
        assertThat(referencePage(gm, turnId, "1", "13").statusCode()).isEqualTo(200);
        assertThat(referencePage(gm, turnId, "2", "13").statusCode()).isEqualTo(200);

        as(gm.id(), () -> {
            materials.discardVersion(discarded.versionId(), clock.instant());
            materials.deletePageViews(deleted.versionId());
            materials.deletePassages(deleted.versionId());
            materials.deleteVersion(deleted.versionId());
            return materials.deleteMaterial(deleted.materialId());
        });

        for (String number : List.of("1", "2")) {
            assertNotFound(referencePage(gm, turnId, number, "13"));
            assertNotFound(referencePicture(gm, turnId, number, "13"));
        }
        // Its removal finished, the version stays removed.
        as(gm.id(), () -> materials.clearRemovalPending(discarded.versionId(), clock.instant()));
        assertNotFound(referencePage(gm, turnId, "1", "13"));
    }

    @Test
    void aVersionNeitherReadyNorSupersededIsNotServed() throws Exception {
        Gm gm = signUp();
        Paged source = pdf(gm, gm.campaign(), 14, 13);
        UUID turnId = answered(gm, QUESTION, List.of(delta("Um."), citation(source.passages().getFirst(), 3)));
        assertThat(referencePage(gm, turnId, "1", "13").statusCode()).isEqualTo(200);

        for (String state : List.of("needs_review", "processing", "failed")) {
            as(gm.id(), () -> materials.changeVersionState(source.versionId(), state, clock.instant()));
            assertNotFound(referencePage(gm, turnId, "1", "13"));
            assertNotFound(referencePicture(gm, turnId, "1", "13"));
        }
        // Back to ready, it is served again: only the state refused it.
        as(gm.id(), () -> materials.changeVersionState(source.versionId(), "ready", clock.instant()));
        assertThat(referencePage(gm, turnId, "1", "13").statusCode()).isEqualTo(200);
    }

    @Test
    void anotherOwnersTurnAnUnknownReferenceAndMalformedPathsAreAllNotFound() throws Exception {
        Gm gm = signUp();
        Gm them = signUp();
        Paged source = pdf(gm, gm.campaign(), 14, 13);
        Paged other = pdf(gm, gm.campaign(), 14, 13);
        UUID turnId = answered(gm, QUESTION, List.of(delta("Um."), citation(source.passages().getFirst(), 3),
                citation(other.passages().getFirst(), 5)));
        UUID silent = answered(gm, QUESTION, List.of(delta("as fontes não dizem nada sobre isso.")));
        logged.list.clear();

        // Another owner's turn: nothing is read.
        assertNotFound(referencePage(them, turnId, "1", "13"));
        HttpResponse<byte[]> refusedPicture = referencePicture(them, turnId, "1", "13");
        assertThat(refusedPicture.statusCode()).isEqualTo(404);
        // Asked for as image/webp, the refusal is still a problem detail, not a 406 or an empty body.
        assertThat(refusedPicture.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        assertThat(JSON.readTree(new String(refusedPicture.body(), StandardCharsets.UTF_8)).path("code").asString())
                .isEqualTo("request.not_found");
        assertThat(logged.list).noneMatch(event -> "scope.reference_page".equals(event.getFormattedMessage()));
        // No such reference: number 9 on a turn with 2, and any number on a turn with none.
        assertNotFound(referencePage(gm, turnId, "9", "13"));
        assertNotFound(referencePicture(gm, turnId, "3", "13"));
        assertNotFound(referencePage(gm, silent, "1", "13"));
        assertNotFound(referencePage(gm, Ids.newId(), "1", "13"));
        for (String id : List.of("not-a-uuid", turnId.toString().toUpperCase().replace('-', '_'), "0")) {
            assertNotFound(get(gm, "/api/v1/turns/" + id + "/references/1/pages/13"));
        }
        for (String number : List.of("0", "01", "-1", "1.0", "x", "99999999")) {
            assertNotFound(referencePage(gm, turnId, number, "13"));
            assertNotFound(referencePage(gm, turnId, "1", number));
            assertThat(referencePicture(gm, turnId, "1", number).statusCode()).as(number).isEqualTo(404);
        }
        // Both references are served for their owner, each from its own version.
        assertThat(json(referencePage(gm, turnId, "2", "13")).path("passages").get(0).path("id").asString())
                .isEqualTo(other.passages().getFirst().toString());
        assertThat(referencePicture(gm, turnId, "2", "13").body()).isEqualTo(other.pictures().get(13));
        // The materials route still serves the review ground only: the reference ground is this path.
        HttpResponse<String> refused = get(gm, "/api/v1/materials/" + source.materialId()
                + "/pages/13?ground=reference");
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused).path("code").asString()).isEqualTo("page.ground_unknown");
    }

    @Test
    void anUnreadablePageWithNoViewIsServedEmptyAndAPageWithNoViewOtherwiseIsNotFound() throws Exception {
        Gm gm = signUp();
        Paged source = pdf(gm, gm.campaign(), 14, 13);
        as(gm.id(), () -> {
            TestMaterials.outcome(materials, gm.id(), source.versionId(), 2, 2, "unreadable", clock.instant());
            return null;
        });
        UUID turnId = answered(gm, QUESTION, List.of(delta("Um."), citation(source.passages().getFirst(), 3)));

        JsonNode unreadable = json(referencePage(gm, turnId, "1", "2"));

        assertThat(unreadable.path("page").asInt()).isEqualTo(2);
        assertThat(unreadable.path("pageTotal").asInt()).isEqualTo(14);
        assertThat(unreadable.path("picture").isNull()).isTrue();
        assertThat(unreadable.path("lines")).isEmpty();
        assertThat(unreadable.path("text").isNull()).isTrue();
        assertThat(unreadable.path("passages")).isEmpty();
        assertThat(unreadable.path("outcome").asString()).isEqualTo("unreadable");
        assertNotFound(referencePicture(gm, turnId, "1", "2"));
        // Page 3 has no view and no outcome: not found.
        assertNotFound(referencePage(gm, turnId, "1", "3"));
    }

    /** A material, its version, its passages and its pages' pictures by page. */
    record Paged(UUID materialId, UUID versionId, List<UUID> passages, Map<Integer, byte[]> pictures) {
    }

    /** A ready material of {@code campaign} whose version has {@code pageTotal} pages and nothing on them yet. */
    private Paged material(Gm gm, UUID campaign, String format, int pageTotal) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID materialId = Ids.newId();
        materials.insertMaterial(new MaterialRow(materialId, gm.id(),
                TestMaterials.materialName(vault, materialId, gm.id(), MATERIAL_NAME), now, "rulebook", null,
                campaign));
        UUID versionId = Ids.newId();
        materials.insertVersion(new VersionRow(versionId, gm.id(), materialId, "ready", format, 1000L, now, now,
                pageTotal, "2", null));
        return new Paged(materialId, versionId, List.of(), Map.of());
    }

    /**
     * An accepted PDF of {@code campaign}: pages 1 to {@code pageTotal}, read directly, the last two with a picture and
     * a line ({@code cited} among them), and one passage boxed on {@code cited}.
     */
    private Paged pdf(Gm gm, UUID campaign, int pageTotal, int cited) {
        return as(gm.id(), () -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Paged made = material(gm, campaign, "pdf", pageTotal);
            Map<Integer, byte[]> pictures = new LinkedHashMap<>();
            for (int page = 1; page <= pageTotal; page++) {
                if (page >= pageTotal - 1) {
                    byte[] picture = webp(1000 + page);
                    pictures.put(page, picture);
                    TestMaterials.picturePage(materials, vault, files, gm.id(), made.versionId(), page, picture, 1545,
                            2000, List.of(new TestMaterials.Line(LINE, LINE_BOX)), now);
                } else if (page == 1) {
                    TestMaterials.picturePage(materials, vault, files, gm.id(), made.versionId(), page, webp(500),
                            1545, 2000, List.of(), now);
                }
            }
            TestMaterials.outcome(materials, gm.id(), made.versionId(), pageTotal - 1, pageTotal, "read_directly", now);
            PassageRow passage = TestMaterials.passage(vault, Ids.newId(), gm.id(), made.versionId(), 0, cited,
                    PASSAGE_TEXT, SECTION, List.of(BOX), null, now);
            materials.insertPassages(List.of(passage));
            return new Paged(made.materialId(), made.versionId(), List.of(passage.id()), Map.copyOf(pictures));
        });
    }

    /** Some bytes that start like a WebP file. */
    private static byte[] webp(int size) {
        byte[] bytes = new byte[size];
        new java.util.Random(size).nextBytes(bytes);
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        return bytes;
    }

    private HttpResponse<String> referencePage(Gm gm, UUID turnId, String number, String page) throws Exception {
        return get(gm, "/api/v1/turns/" + turnId + "/references/" + number + "/pages/" + page);
    }

    private HttpResponse<byte[]> referencePicture(Gm gm, UUID turnId, String number, String page) throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/api/v1/turns/" + turnId + "/references/" + number + "/pages/"
                        + page + "/picture")).header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Session-Token", gm.session()).header("Accept", "image/webp").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void assertNotFound(HttpResponse<?> answer) {
        assertThat(answer.statusCode()).as(String.valueOf(answer.body())).isEqualTo(404);
        if (answer.body() instanceof String body) {
            assertThat(JSON.readTree(body).path("code").asString()).isEqualTo("request.not_found");
        }
    }

    /** An index change of the source's passages {@code first} to {@code last}, as a job that has not ended. */
    private UUID indexChange(Gm gm, Source source, int first, int last) {
        return as(gm.id(), () -> jobService.create("index.change", job -> index.insertChange(new IndexChangeRow(
                job.id(), gm.id(), source.versionId(), first, last, null))).id());
    }

    /** {@code indexingFailed} as {@code {passages, retrying}}, or null when {@code passages} is. */
    private void assertIndexingFailed(Gm gm, Integer passages, boolean retrying) throws Exception {
        HttpResponse<String> current = get(gm, CURRENT);
        assertThat(current.statusCode()).as(current.body()).isEqualTo(200);
        JsonNode failed = json(current).path("indexingFailed");
        assertThat(json(current).has("indexingFailed")).isTrue();
        if (passages == null) {
            assertThat(failed.isNull()).as(current.body()).isTrue();
            return;
        }
        assertThat(failed.path("passages").asInt()).as(current.body()).isEqualTo(passages);
        assertThat(failed.path("retrying").isBoolean()).isTrue();
        assertThat(failed.path("retrying").asBoolean()).as(current.body()).isEqualTo(retrying);
        assertThat(failed.size()).isEqualTo(2);
    }

    private void assertIndexing(Gm gm, int remaining) throws Exception {
        HttpResponse<String> current = get(gm, CURRENT);
        assertThat(current.statusCode()).as(current.body()).isEqualTo(200);
        JsonNode indexing = json(current).path("indexing");
        assertThat(indexing.path("remaining").asInt()).as(current.body()).isEqualTo(remaining);
        assertThat(indexing.size()).isEqualTo(1);
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
