package io.github.gmshield.jobs.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.gmshield.HttpTestSupport;
import io.github.gmshield.jobs.data.InMemoryJobStorage;
import io.github.gmshield.jobs.data.InMemoryJobStream;
import io.github.gmshield.jobs.data.InMemoryProbeJobStore;
import io.github.gmshield.jobs.data.JobOutputRow;
import io.github.gmshield.jobs.data.JobRow;
import io.github.gmshield.jobs.data.JobTestStores;
import io.github.gmshield.jobs.data.MutableClock;
import io.github.gmshield.jobs.data.ProbeJobRow;
import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.JobSweep;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.jobs.service.Lane;
import io.github.gmshield.jobs.service.ProbeJobs;
import io.github.gmshield.shared.data.InMemoryTransactionManager;
import io.github.gmshield.shared.service.JsonLineLogFormatter;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.shared.service.TraceContext;
import io.github.gmshield.vault.service.FileStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Story 1.12 without a database or Redis: every backend row of the I/O matrix that needs no real service, through
 * the internal API as the worker calls it (test mode, so the probe kind and its route exist). The same rows against
 * PostgreSQL and Redis are in the integration tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"GMSHIELD_MODE=test",
    "gmshield.jobs.output-max-bytes=6MB", "gmshield.jobs.output-claim-max-bytes=10MB"})
@ActiveProfiles(value = {"test-mode", "unit"}, inheritProfiles = false)
@Import({JobTestStores.class, JobFlowTest.Handlers.class})
class JobFlowTest extends HttpTestSupport {

    /** A kind whose handler gives a JSON array as its input: never a valid claim. */
    static final String ARRAY_INPUT = "array-input";
    /** A test-only kind that calls a model (Story 2.10): no production kind does yet. */
    static final String TEST_MODEL = "test.model";

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class Handlers {
        /** The files of a job are sealed with the owner's data key (Story 1.15): kept in memory here. */
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        io.github.gmshield.vault.data.DataKeyStore inMemoryDataKeys() {
            return new io.github.gmshield.vault.data.InMemoryDataKeyStore();
        }

        @org.springframework.context.annotation.Bean
        StatedRecipes statedRecipes() {
            return new StatedRecipes();
        }

        @org.springframework.context.annotation.Bean
        io.github.gmshield.jobs.service.JobHandler modelHandler() {
            return new io.github.gmshield.jobs.service.JobHandler() {
                @Override
                public String kind() {
                    return TEST_MODEL;
                }

                @Override
                public Lane lane() {
                    return Lane.INTERACTIVE;
                }

                @Override
                public boolean callsModel() {
                    return true;
                }

                @Override
                public JsonNode input(JobRef job) {
                    return tools.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("prompt", "fake:tool");
                }

                @Override
                public void completed(JobRef job, JsonNode result) {
                }

                @Override
                public void failed(JobRef job, String code) {
                    LoggerFactory.getLogger(JobFlowTest.class).atInfo().addKeyValue("jobId", job.id())
                            .addKeyValue("code", code).log("test-model.failed");
                }
            };
        }

        @org.springframework.context.annotation.Bean
        io.github.gmshield.jobs.service.JobHandler arrayInputHandler() {
            return new io.github.gmshield.jobs.service.JobHandler() {
                @Override
                public String kind() {
                    return ARRAY_INPUT;
                }

                @Override
                public Lane lane() {
                    return Lane.INGESTION;
                }

                @Override
                public JsonNode input(JobRef job) {
                    return tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add(1);
                }

                @Override
                public void completed(JobRef job, JsonNode result) {
                }

                @Override
                public void failed(JobRef job, String code) {
                    LoggerFactory.getLogger(JobFlowTest.class).atInfo().addKeyValue("jobId", job.id())
                            .addKeyValue("code", code).log("array-input.failed");
                }
            };
        }
    }

    /** The index recipes published as stated at a claim (Story 2.1). */
    static class StatedRecipes {
        final List<String> stated = new java.util.concurrent.CopyOnWriteArrayList<>();

        @org.springframework.context.event.EventListener
        void on(io.github.gmshield.jobs.service.IndexRecipeStated event) {
            stated.add(event.recipe());
        }
    }

    static final String PROBES = "/internal/v1/test-mode/probe-jobs";
    static final String INTERACTIVE = "gmshield:work:interactive";
    static final String INGESTION = "gmshield:work:ingestion";
    static final Instant START = Instant.parse("2026-11-16T12:00:00Z");

    @Autowired
    StatedRecipes recipes;
    @Autowired
    InMemoryJobStorage storage;
    @Autowired
    InMemoryJobStream stream;
    @Autowired
    InMemoryProbeJobStore probes;
    @Autowired
    MutableClock clock;
    @Autowired
    JobSweep sweep;
    @Autowired
    Jobs jobs;
    @Autowired
    InMemoryTransactionManager transactions;
    @Autowired
    FileStore files;
    @Autowired
    io.github.gmshield.jobs.service.JobFiles jobFiles;
    @org.springframework.beans.factory.annotation.Value("${gmshield.files.dir}")
    java.nio.file.Path filesDir;

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void reset() {
        storage.rows.clear();
        storage.outputs.clear();
        probes.rows.clear();
        stream.clear();
        clock.set(START);
        logged.start();
        root.addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        root.detachAppender(logged);
        logged.stop();
    }

    // ---- create and offer

    @Test
    void aJobCreatedInATransactionThatRollsBackLeavesNoRowAndNoMessage() {
        UUID owner = UUID.randomUUID();
        JobRef[] created = new JobRef[1];
        OwnerContext.runAs(owner, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            created[0] = jobs.create(ProbeJobs.KIND, Lane.INTERACTIVE,
                    job -> probes.insert(new ProbeJobRow(job.id(), 0, 0)));
            status.setRollbackOnly();
        }));

        assertThat(created[0]).isNotNull();
        assertThat(storage.rows).isEmpty();
        assertThat(probes.rows).isEmpty();
        assertThat(stream.streams.values()).allMatch(List::isEmpty);
    }

    @Test
    void aCommittedInteractiveJobIsOfferedOnceWithOnlyItsId() throws Exception {
        UUID id = createProbe("interactive", 0, 0, null);

        JobRow row = storage.row(id);
        assertThat(row.state()).isEqualTo("offered");
        assertThat(row.offeredAt()).isEqualTo(START);
        assertThat(row.attempt()).isZero();
        assertThat(row.ownerId()).isEqualTo(ProbeJobs.OWNER);
        assertThat(stream.offered(INTERACTIVE)).containsExactly(id);
        assertThat(stream.offered(INGESTION)).isEmpty();
    }

    @Test
    void whenRedisIsDownTheJobStaysWaitingAndTheSweepOffersItLater() throws Exception {
        stream.down.set(true);
        UUID id = createProbe("interactive", 0, 0, null);

        assertThat(storage.row(id).state()).isEqualTo("waiting");
        sweep.sweep();   // Redis still down: nothing changes, nothing throws
        assertThat(storage.row(id).state()).isEqualTo("waiting");

        stream.down.set(false);
        sweep.sweep();

        assertThat(storage.row(id).state()).isEqualTo("offered");
        assertThat(stream.offered(INTERACTIVE)).containsExactly(id);
        assertThat(stream.groups).containsExactlyInAnyOrder(INTERACTIVE, INGESTION);
    }

    // ---- claim

    @Test
    void aClaimMayStateTheIndexRecipeAndEachNewOneIsPublishedOnceWhateverTheClaimsAnswer() throws Exception {
        String recipe = "r" + UUID.randomUUID().toString().substring(0, 8);
        UUID first = createProbe("interactive", 0, 0, null);
        UUID second = createProbe("interactive", 0, 0, null);
        UUID third = createProbe("interactive", 0, 0, null);
        recipes.stated.clear();

        // A worker before contract 1.7.0 sends no body: its claim works and states nothing.
        assertThat(claim(first).statusCode()).isEqualTo(201);
        assertThat(recipes.stated).isEmpty();
        // A recipe that is not one is refused before the claim: the job stays offered.
        for (String body : List.of("{\"indexRecipe\":\"two words\"}", "{\"indexRecipe\":\"\"}",
                "{\"indexRecipe\":\"" + "x".repeat(33) + "\"}")) {
            assertProblem(sendAsWorker("POST", "/internal/v1/jobs/" + second + "/claims", body), 400, "request.invalid");
        }
        assertThat(storage.row(second).state()).isEqualTo("offered");
        assertThat(recipes.stated).isEmpty();

        assertThat(sendAsWorker("POST", "/internal/v1/jobs/" + second + "/claims", "{\"indexRecipe\":\"" + recipe
                + "\"}").statusCode()).isEqualTo(201);
        assertThat(recipes.stated).containsExactly(recipe);
        // The same recipe again, on any claim: nothing more. Another one, even on a refused claim: published.
        assertThat(sendAsWorker("POST", "/internal/v1/jobs/" + third + "/claims", "{\"indexRecipe\":\"" + recipe
                + "\"}").statusCode()).isEqualTo(201);
        assertThat(sendAsWorker("POST", "/internal/v1/jobs/" + third + "/claims", "{\"indexRecipe\":\"" + recipe
                + "-2\"}").statusCode()).isEqualTo(409);
        assertThat(sendAsWorker("POST", "/internal/v1/jobs/" + third + "/claims", "{}").statusCode()).isEqualTo(409);
        assertThat(recipes.stated).containsExactly(recipe, recipe + "-2");
    }

    @Test
    void aClaimRunsTheJobAndAnswersAttemptGrantTimesInputAndTraceparent() throws Exception {
        String trace = TraceContext.newTraceId();
        UUID id = createProbe("interactive", 1500, 1, trace);

        HttpResponse<String> response = claim(id);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode claim = json(response);
        assertThat(claim.path("attempt").asInt()).isEqualTo(1);
        assertThat(claim.path("kind").asString()).isEqualTo("probe");
        assertThat(claim.path("grant").asString()).matches("[A-Za-z0-9_-]{43}");
        assertThat(Instant.parse(claim.path("renewAt").asString())).isEqualTo(START.plusSeconds(30));
        assertThat(Instant.parse(claim.path("lapsesAt").asString())).isEqualTo(START.plusSeconds(90));
        assertThat(claim.path("input").path("workMs").asInt()).isEqualTo(1500);
        assertThat(claim.path("input").path("failTimes").asInt()).isEqualTo(1);
        assertThat(TraceContext.fromTraceparent(claim.path("traceparent").asString())).contains(trace);

        JobRow row = storage.row(id);
        assertThat(row.state()).isEqualTo("running");
        assertThat(row.attempt()).isEqualTo(1);
        assertThat(row.claimId()).hasToString(claim.path("claimId").asString());
        assertThat(row.grantHash()).hasSize(32)
                .isNotEqualTo(claim.path("grant").asString().getBytes(StandardCharsets.US_ASCII));
        assertThat(row.traceId()).isEqualTo(trace);
        // The grant appears nowhere but in the answer.
        assertThat(logText()).doesNotContain(claim.path("grant").asString());
    }

    @Test
    void aSecondClaimIsRefusedWhileTheFirstIsLiveAndOnlyOfferedJobsCanBeClaimed() throws Exception {
        UUID id = createProbe("interactive", 0, 0, null);
        assertThat(claim(id).statusCode()).isEqualTo(201);

        assertProblem(claim(id), 409, "job.claimed");

        stream.down.set(true);
        UUID waiting = createProbe("interactive", 0, 0, null);
        stream.down.set(false);
        assertProblem(claim(waiting), 409, "job.not_offered");
        assertProblem(claim(UUID.randomUUID()), 404, "request.not_found");
    }

    @Test
    void aClaimThatSeesAJobStillBeingOfferedWaitsForTheOfferAndSucceeds() throws Exception {
        stream.down.set(true);
        UUID id = createProbe("interactive", 0, 0, null);
        stream.down.set(false);
        assertThat(storage.row(id).state()).isEqualTo("waiting");
        // The offer commits while the claim waits for the row's lock.
        storage.beforeNextLockedRead = () -> storage.rows.put(id, storage.row(id).offered(START));

        HttpResponse<String> response = claim(id);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(storage.row(id).state()).isEqualTo("running");
        assertThat(storage.row(id).attempt()).isEqualTo(1);
    }

    @Test
    void aHandlerWhoseInputFailsEndsTheJobFailedInsteadOfOfferingItForever() throws Exception {
        UUID id = createProbe("interactive", 0, 0, null);
        probes.rows.remove(id);   // the probe handler's input() now throws

        assertProblem(claim(id), 409, "job.not_offered");

        JobRow row = storage.row(id);
        assertThat(row.state()).isEqualTo("failed");
        assertThat(row.outcomeCode()).isEqualTo("job.input_failed");
        assertThat(row.attempt()).isZero();
        assertThat(row.grantHash()).isNull();
        assertThat(logEvents()).contains("probe.failed");
        clock.advance(Duration.ofMinutes(3));
        sweep.sweep();
        assertThat(stream.offered(INTERACTIVE)).containsExactly(id);
    }

    @Test
    void anInputThatIsNotAJsonObjectEndsTheJobFailedAndTheIngestionLaneMovesOn() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID broken = OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status ->
                jobs.create(ARRAY_INPUT, job -> { }).id()));
        clock.advance(Duration.ofSeconds(1));
        UUID next = createAs(owner, Lane.INGESTION);
        assertThat(storage.row(next).state()).isEqualTo("waiting");

        assertProblem(claim(broken), 409, "job.not_offered");

        assertThat(storage.row(broken).state()).isEqualTo("failed");
        assertThat(storage.row(broken).outcomeCode()).isEqualTo("job.input_failed");
        assertThat(logEvents()).contains("array-input.failed");
        assertThat(storage.row(next).state()).isEqualTo("offered");
        assertThat(claim(next).statusCode()).isEqualTo(201);
    }

    @Test
    void aJobOfAKindWithoutAHandlerEndsFailedAtItsClaim() throws Exception {
        UUID id = UUID.randomUUID();
        storage.rows.put(id, new JobRow(id, UUID.randomUUID(), "gone", "interactive", "offered", 0,
                TraceContext.newTraceId(), START, null, null, null, null, null, START, START, null, null, null));

        assertProblem(claim(id), 409, "job.not_offered");
        assertThat(storage.row(id).state()).isEqualTo("failed");
        assertThat(storage.row(id).outcomeCode()).isEqualTo("job.unknown_kind");
    }

    // ---- models (Story 2.10)

    @Test
    void aModelKindsClaimAnswersTheResolvedModelRecordsItAndEachRetryResolvesItAgain() throws Exception {
        UUID id = createAs(UUID.randomUUID(), TEST_MODEL);

        HttpResponse<String> first = claimStating(id, "{\"providers\":[\"anthropic\",\"azure-openai\"]}");

        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        JsonNode model = json(first).path("model");
        assertThat(model.path("provider").asString()).isEqualTo("azure-openai");
        assertThat(model.path("model").asString()).isEqualTo("gpt-6.1-terra");
        assertThat(model.path("tier").asString()).isEqualTo("full");
        JobRow row = storage.row(id);
        assertThat(List.of(row.modelProvider(), row.modelId(), row.modelTier()))
                .containsExactly("azure-openai", "gpt-6.1-terra", "full");
        assertThat(row.attempt()).isEqualTo(1);
        assertThat(logText()).contains("\"modelProvider\":\"azure-openai\"");

        // The attempt fails; the row keeps a model of an older setting until the next claim resolves it again.
        assertThat(outcome(id, json(first).path("claimId").asString(), json(first).path("grant").asString(),
                "{\"outcome\":\"failed\",\"code\":\"model.unavailable\"}").statusCode()).isEqualTo(204);
        assertThat(storage.row(id).state()).isEqualTo("offered");
        storage.rows.put(id, storage.row(id).withModel("anthropic", "claude-sonnet-5-5", "lightweight"));
        HttpResponse<String> second = claimStating(id, "{\"providers\":[\"azure-openai\"]}");

        assertThat(second.statusCode()).as(second.body()).isEqualTo(201);
        assertThat(json(second).path("attempt").asInt()).isEqualTo(2);
        assertThat(json(second).path("model").path("provider").asString()).isEqualTo("azure-openai");
        assertThat(List.of(storage.row(id).modelProvider(), storage.row(id).modelId(), storage.row(id).modelTier()))
                .containsExactly("azure-openai", "gpt-6.1-terra", "full");

        // Once the job ends, the row names the model that answered.
        assertThat(outcome(id, json(second).path("claimId").asString(), json(second).path("grant").asString(),
                "{\"outcome\":\"done\"}").statusCode()).isEqualTo(204);
        assertThat(storage.row(id).state()).isEqualTo("done");
        assertThat(storage.row(id).modelProvider()).isEqualTo("azure-openai");
    }

    @Test
    void aWorkerWithoutTheResolvedProvidersKeyIsRefusedAndTheJobEndsFailedWithoutUsingAnAttempt() throws Exception {
        for (String body : new String[] {null, "{}", "{\"providers\":[]}", "{\"providers\":[\"anthropic\"]}"}) {
            UUID id = createAs(UUID.randomUUID(), TEST_MODEL);

            assertProblem(claimStating(id, body), 409, "job.not_offered");

            JobRow row = storage.row(id);
            assertThat(row.state()).as(body).isEqualTo("failed");
            assertThat(row.outcomeCode()).isEqualTo("job.provider_unavailable");
            assertThat(row.attempt()).isZero();
            assertThat(row.grantHash()).isNull();
            assertThat(row.modelProvider()).isNull();
            assertProblem(claimStating(id, "{\"providers\":[\"azure-openai\"]}"), 409, "job.not_offered");
        }
        assertThat(logEvents()).contains("test-model.failed");
        // The line that ends the job names the resolved provider and what the worker stated.
        assertThat(logText()).contains("\"modelProvider\":\"azure-openai\"").contains("\"statedProviders\":\"anthropic\"");
    }

    @Test
    void providersThatAreNotWordsOrMoreThanEightAreRefusedBeforeTheClaim() throws Exception {
        UUID id = createAs(UUID.randomUUID(), TEST_MODEL);
        String nine = "[" + String.join(",", java.util.Collections.nCopies(9, "\"openai\"")) + "]";

        for (String providers : List.of("[\"Azure\"]", "[\"\"]", "[\"" + "a".repeat(33) + "\"]", "[null]", nine)) {
            assertProblem(claimStating(id, "{\"providers\":" + providers + "}"), 400, "request.invalid");
        }
        assertThat(storage.row(id).state()).isEqualTo("offered");
        assertThat(storage.row(id).attempt()).isZero();
    }

    @Test
    void aKindThatCallsNoModelClaimsAsBeforeWithoutAModelWhateverTheProvidersStated() throws Exception {
        UUID silent = createProbe("interactive", 0, 0, null);
        UUID stating = createProbe("interactive", 0, 0, null);

        HttpResponse<String> plain = claim(silent);
        HttpResponse<String> stated = claimStating(stating, "{\"providers\":[\"anthropic\"]}");

        for (HttpResponse<String> response : List.of(plain, stated)) {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            assertThat(json(response).has("model")).isFalse();
        }
        assertThat(storage.row(silent).modelProvider()).isNull();
        assertThat(storage.row(stating).modelProvider()).isNull();
    }

    // ---- credential and grant

    @Test
    void withoutTheWorkersCredentialEveryInternalRouteIsRefused() throws Exception {
        UUID id = createProbe("interactive", 0, 0, null);
        JsonNode claim = json(claim(id));
        String claimPath = "/internal/v1/jobs/" + id + "/claims";
        String[][] wrong = {{}, {"Authorization", "Bearer " + CREDENTIAL}, {"Authorization", "Bearer x" + WORKER_CREDENTIAL},
                {"Authorization", "Bearer " + WORKER_CREDENTIAL, "Authorization", "Bearer " + WORKER_CREDENTIAL}};
        for (String[] headers : wrong) {
            assertProblem(getWithout("/internal/v1/version", headers), 401, "service.unauthenticated");
            for (String path : new String[] {claimPath, claimPath + "/" + claim.path("claimId").asString() + "/renewals"}) {
                String[] withGrant = java.util.Arrays.copyOf(headers, headers.length + 2);
                withGrant[headers.length] = "X-Job-Grant";
                withGrant[headers.length + 1] = claim.path("grant").asString();
                assertProblem(postWithout(path, withGrant), 401, "service.unauthenticated");
            }
        }
        assertThat(storage.row(id).claimId()).hasToString(claim.path("claimId").asString());
    }

    @Test
    void aWrongMissingExpiredOrReplacedGrantOrAnotherJobsIsRefused() throws Exception {
        UUID a = createProbe("interactive", 0, 0, null);
        UUID b = createProbe("interactive", 0, 0, null);
        JsonNode claimA = json(claim(a));
        JsonNode claimB = json(claim(b));
        String grantA = claimA.path("grant").asString();
        String claimIdA = claimA.path("claimId").asString();

        // Job A's grant on job B, with B's claim id and with A's.
        assertProblem(renew(b, claimB.path("claimId").asString(), grantA), 403, "job.grant_refused");
        assertProblem(renew(b, claimIdA, grantA), 403, "job.grant_refused");
        // Another claim id on A's own job, no grant, a malformed one, a well-formed wrong one.
        assertProblem(renew(a, UUID.randomUUID().toString(), grantA), 403, "job.grant_refused");
        assertProblem(sendAsWorker("POST", "/internal/v1/jobs/" + a + "/claims/" + claimIdA + "/renewals", null), 403,
                "job.grant_refused");
        assertProblem(renew(a, claimIdA, "not a grant"), 403, "job.grant_refused");
        assertProblem(renew(a, claimIdA, "A".repeat(43)), 403, "job.grant_refused");
        assertProblem(outcome(a, claimIdA, "A".repeat(43), "{\"outcome\":\"done\"}"), 403, "job.grant_refused");
        assertThat(storage.row(a).state()).isEqualTo("running");

        // Expired: past the lapse time, before any sweep.
        clock.advance(Duration.ofSeconds(91));
        assertProblem(renew(a, claimIdA, grantA), 403, "job.grant_refused");
        // Replaced: after the lapse, a new claim; the earlier grant no longer acts.
        sweep.sweep();
        JsonNode second = json(claim(a));
        assertThat(second.path("attempt").asInt()).isEqualTo(2);
        assertProblem(renew(a, claimIdA, grantA), 403, "job.grant_refused");
        assertProblem(renew(a, second.path("claimId").asString(), grantA), 403, "job.grant_refused");
        assertThat(renew(a, second.path("claimId").asString(), second.path("grant").asString()).statusCode()).isEqualTo(200);
    }

    @Test
    void aRenewalMovesTheClaimsTimes() throws Exception {
        UUID id = createProbe("interactive", 0, 0, null);
        JsonNode claim = json(claim(id));
        clock.advance(Duration.ofSeconds(60));

        HttpResponse<String> renewed = renew(id, claim.path("claimId").asString(), claim.path("grant").asString());

        assertThat(renewed.statusCode()).isEqualTo(200);
        assertThat(Instant.parse(json(renewed).path("renewAt").asString())).isEqualTo(START.plusSeconds(90));
        assertThat(Instant.parse(json(renewed).path("lapsesAt").asString())).isEqualTo(START.plusSeconds(150));
        clock.advance(Duration.ofSeconds(80));   // past the first lapse time, before the renewed one
        sweep.sweep();
        assertThat(storage.row(id).state()).isEqualTo("running");
    }

    // ---- outcome, retries, lapse

    @Test
    void aDoneOutcomeEndsTheJobAndItsGrant() throws Exception {
        UUID id = createProbe("interactive", 0, 0, null);
        JsonNode claim = json(claim(id));
        String claimId = claim.path("claimId").asString();
        String grant = claim.path("grant").asString();

        HttpResponse<String> done = outcome(id, claimId, grant, "{\"outcome\":\"done\",\"result\":{\"workedMs\":0}}");

        assertThat(done.statusCode()).isEqualTo(204);
        JobRow row = storage.row(id);
        assertThat(row.state()).isEqualTo("done");
        assertThat(row.grantHash()).isNull();
        assertThat(row.outcomeCode()).isNull();
        assertProblem(renew(id, claimId, grant), 403, "job.grant_refused");
        assertProblem(outcome(id, claimId, grant, "{\"outcome\":\"done\"}"), 403, "job.grant_refused");
        assertProblem(claim(id), 409, "job.not_offered");
    }

    @Test
    void anOutcomeThatIsNotDoneOrFailedOrHasAWrongCodeIsInvalid() throws Exception {
        UUID id = createProbe("interactive", 0, 0, null);
        JsonNode claim = json(claim(id));
        for (String body : new String[] {"{\"outcome\":\"maybe\"}", "{}", "{\"outcome\":\"done\",\"code\":\"probe.failed\"}",
                "{\"outcome\":\"failed\",\"code\":\"Not A Code\"}", "{\"outcome\":\"failed\",\"code\":\"nodot\"}",
                "{\"outcome\":\"failed\",\"result\":{}}", "not json",
                "{\"outcome\":\"failed\",\"code\":\"probe." + "x".repeat(30) + ".y" + "y".repeat(28) + "\"}"}) {
            assertThat(outcome(id, claim.path("claimId").asString(), claim.path("grant").asString(), body).statusCode())
                    .as(body).isEqualTo(400);
        }
        assertThat(storage.row(id).state()).isEqualTo("running");
    }

    @Test
    void aFailedOutcomeGoesBackToWaitingUntilTheLastAttemptThenEndsWithItsCode() throws Exception {
        UUID id = createProbe("interactive", 0, 3, null);
        for (int attempt = 1; attempt <= 3; attempt++) {
            JsonNode claim = json(claim(id));
            assertThat(claim.path("attempt").asInt()).isEqualTo(attempt);
            assertThat(outcome(id, claim.path("claimId").asString(), claim.path("grant").asString(),
                    "{\"outcome\":\"failed\",\"code\":\"probe.failed\"}").statusCode()).isEqualTo(204);
            if (attempt < 3) {
                assertThat(storage.row(id).state()).as("attempt %d", attempt).isEqualTo("offered");
            }
        }
        JobRow row = storage.row(id);
        assertThat(row.state()).isEqualTo("failed");
        assertThat(row.outcomeCode()).isEqualTo("probe.failed");
        assertThat(row.attempt()).isEqualTo(3);
        assertThat(stream.offered(INTERACTIVE)).containsExactly(id, id, id);
        assertThat(logEvents()).contains("probe.failed");
    }

    @Test
    void aLapsedClaimGoesBackToWaitingIsOfferedAgainAndTheThirdLapseFailsTheJob() throws Exception {
        UUID id = createProbe("interactive", 0, 0, null);
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(json(claim(id)).path("attempt").asInt()).isEqualTo(attempt);
            clock.advance(Duration.ofSeconds(89));
            sweep.sweep();
            assertThat(storage.row(id).state()).isEqualTo("running");
            clock.advance(Duration.ofSeconds(1));
            sweep.sweep();
            if (attempt < 3) {
                assertThat(storage.row(id).state()).isEqualTo("offered");
                assertThat(storage.row(id).attempt()).isEqualTo(attempt);
            }
        }
        JobRow row = storage.row(id);
        assertThat(row.state()).isEqualTo("failed");
        assertThat(row.outcomeCode()).isEqualTo("job.attempts_exhausted");
        assertThat(stream.offered(INTERACTIVE)).containsExactly(id, id, id);
        assertThat(logEvents()).contains("job.claim_lapsed", "probe.failed");
    }

    @Test
    void anOfferWhoseMessageWasLostIsMadeAgainAfterTwoMinutes() throws Exception {
        UUID id = createProbe("interactive", 0, 0, null);
        stream.streams.get(INTERACTIVE).clear();   // as XDEL, or a Redis that lost it

        clock.advance(Duration.ofSeconds(119));
        sweep.sweep();
        assertThat(stream.offered(INTERACTIVE)).isEmpty();
        clock.advance(Duration.ofSeconds(1));
        sweep.sweep();

        assertThat(stream.offered(INTERACTIVE)).containsExactly(id);
        assertThat(storage.row(id).offeredAt()).isEqualTo(START.plusSeconds(120));
        assertThat(json(claim(id)).path("attempt").asInt()).isEqualTo(1);
    }

    @Test
    void theSweepTrimsMessagesOlderThanAnHourAndCreatesMissingGroups() throws Exception {
        UUID old = createProbe("interactive", 0, 0, null);
        outcomeDone(old);
        clock.advance(Duration.ofMinutes(59));
        UUID recent = createProbe("interactive", 0, 0, null);
        outcomeDone(recent);
        clock.advance(Duration.ofMinutes(2));

        sweep.sweep();

        assertThat(stream.offered(INTERACTIVE)).containsExactly(recent);
        assertThat(stream.groups).containsExactlyInAnyOrder(INTERACTIVE, INGESTION);
        stream.groups.clear();   // a flushed Redis
        sweep.sweep();
        assertThat(stream.groups).containsExactlyInAnyOrder(INTERACTIVE, INGESTION);
    }

    @Test
    void ingestionJobsGoOutOneAtATimeAlternatingBetweenOwners() throws Exception {
        UUID ownerA = UUID.fromString("0195a3c0-0000-7000-8000-00000000000a");
        UUID ownerB = UUID.fromString("0195a3c0-0000-7000-8000-00000000000b");
        UUID a1 = createAs(ownerA, Lane.INGESTION);
        clock.advance(Duration.ofSeconds(1));
        UUID a2 = createAs(ownerA, Lane.INGESTION);
        clock.advance(Duration.ofSeconds(1));
        UUID b1 = createAs(ownerB, Lane.INGESTION);

        List<UUID> order = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            assertThat(storage.rows.values().stream().filter(row -> row.state().equals("offered"))).hasSize(1);
            UUID offered = storage.rows.values().stream().filter(row -> row.state().equals("offered")).findFirst()
                    .orElseThrow().id();
            order.add(offered);
            sweep.sweep();   // nothing else goes out while it is offered or running
            outcomeDone(offered);
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(order).containsExactly(a1, b1, a2);
        assertThat(stream.offered(INGESTION)).containsExactly(a1, b1, a2);
        assertThat(storage.rows.values()).allMatch(row -> row.state().equals("done"));
    }

    @Test
    void anInteractiveJobIsOfferedWhileTheIngestionLaneIsBusy() throws Exception {
        UUID ingestion = createProbe("ingestion", 0, 0, null);
        claim(ingestion);
        UUID waitingIngestion = createProbe("ingestion", 0, 0, null);
        UUID interactive = createProbe("interactive", 0, 0, null);

        assertThat(storage.row(waitingIngestion).state()).isEqualTo("waiting");
        assertThat(storage.row(interactive).state()).isEqualTo("offered");
        assertThat(claim(interactive).statusCode()).isEqualTo(201);
    }

    // ---- retry (Story 1.15)

    @Test
    void aFailedOutcomeWithRetryFalseEndsTheJobAtOnceAndRetryIsForFailedOnly() throws Exception {
        UUID id = createProbe("interactive", 0, 3, null);
        JsonNode claim = json(claim(id));
        assertThat(outcome(id, claim.path("claimId").asString(), claim.path("grant").asString(),
                "{\"outcome\":\"done\",\"retry\":false}").statusCode()).isEqualTo(400);
        assertThat(outcome(id, claim.path("claimId").asString(), claim.path("grant").asString(),
                "{\"outcome\":\"failed\",\"retry\":\"no\"}").statusCode()).isEqualTo(400);
        assertThat(storage.row(id).state()).isEqualTo("running");

        assertThat(outcome(id, claim.path("claimId").asString(), claim.path("grant").asString(),
                "{\"outcome\":\"failed\",\"code\":\"probe.password\",\"retry\":false}").statusCode()).isEqualTo(204);

        JobRow row = storage.row(id);
        assertThat(row.state()).isEqualTo("failed");
        assertThat(row.attempt()).isEqualTo(1);
        assertThat(row.outcomeCode()).isEqualTo("probe.password");
        assertThat(stream.offered(INTERACTIVE)).containsExactly(id);
        assertThat(logEvents()).contains("probe.failed");

        UUID retried = createProbe("interactive", 0, 3, null);
        JsonNode first = json(claim(retried));
        outcome(retried, first.path("claimId").asString(), first.path("grant").asString(),
                "{\"outcome\":\"failed\",\"code\":\"probe.failed\",\"retry\":true}");
        assertThat(storage.row(retried).state()).isEqualTo("offered");
    }

    // ---- files (Story 1.15)

    @Test
    void anOutputIsStreamedIntoSealedPiecesUnderAMintedIdAndAnsweredWithItsSizeAndSha256() throws Exception {
        UUID id = createProbe("interactive", 0, 0, null);
        JsonNode claim = json(claim(id));
        byte[] content = bytes(5 * 1024 * 1024 + 123);

        HttpResponse<String> stored = postOutput(id, claim, content, true);

        assertThat(stored.statusCode()).as(stored.body()).isEqualTo(201);
        assertThat(stored.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode body = json(stored);
        UUID fileId = UUID.fromString(body.path("fileId").asString());
        assertThat(body.path("size").asLong()).isEqualTo(content.length);
        assertThat(body.path("sha256").asString()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content)));
        JobOutputRow row = storage.outputs.get(fileId);
        assertThat(row.state()).isEqualTo("stored");
        assertThat(row.jobId()).isEqualTo(id);
        assertThat(row.ownerId()).isEqualTo(ProbeJobs.OWNER);
        assertThat(row.claimId()).hasToString(claim.path("claimId").asString());
        assertThat(row.sizeBytes()).isEqualTo(content.length);
        assertThat(row.pieceCount()).isEqualTo(2);
        assertThat(readBack(ProbeJobs.OWNER, fileId, 2)).isEqualTo(content);
        // A body without a length (chunked) is stored the same way.
        HttpResponse<String> chunked = postOutput(id, claim, bytes(1000), false);
        assertThat(chunked.statusCode()).as(chunked.body()).isEqualTo(201);
        assertThat(json(chunked).path("size").asLong()).isEqualTo(1000);
        assertThat(logText()).doesNotContain(claim.path("grant").asString());
    }

    @Test
    void anOutputPastItsLimitOrTheClaimsIsRefusedAndLeavesNothing() throws Exception {
        UUID id = createProbe("interactive", 0, 0, null);
        JsonNode claim = json(claim(id));
        int mib = 1024 * 1024;
        java.util.Set<String> before = fileFolders();

        // A declared length past the limit is refused before the body is read: only the headers are sent, since a
        // client still writing a body the server never reads may see the connection reset before the answer.
        assertDeclaredLengthRefused(id, claim, 6L * mib + 1);
        // Without a length the first 4 MiB piece is written before the limit is passed: it is removed again.
        assertProblem(postOutput(id, claim, bytes(6 * mib + 1), false), 413, "job.output_too_large");
        assertProblem(postOutput(id, claim, new byte[0], true), 400, "request.invalid");
        assertThat(storage.outputs).isEmpty();
        assertThat(fileFolders()).isEqualTo(before);

        assertThat(postOutput(id, claim, bytes(6 * mib), true).statusCode()).isEqualTo(201);
        assertThat(postOutput(id, claim, bytes(4 * mib), false).statusCode()).isEqualTo(201);
        java.util.Set<String> stored = fileFolders();
        // 10 MiB stored for this claim: one more byte passes the claim's limit.
        assertProblem(postOutput(id, claim, bytes(1), false), 413, "job.output_too_large");
        assertThat(storage.outputs.values()).hasSize(2).allMatch(row -> row.state().equals("stored"));
        assertThat(fileFolders()).isEqualTo(stored).hasSize(before.size() + 2);
    }

    @Test
    void filesNeedTheLiveClaimsGrantAndAnInputTheHandlerOpens() throws Exception {
        UUID a = createProbe("interactive", 0, 0, null);
        UUID b = createProbe("interactive", 0, 0, null);
        JsonNode claimA = json(claim(a));
        JsonNode claimB = json(claim(b));
        java.util.Set<String> before = fileFolders();
        String outputsA = "/internal/v1/jobs/" + a + "/claims/" + claimA.path("claimId").asString() + "/outputs";

        for (String[] headers : new String[][] {{}, {"X-Job-Grant", "A".repeat(43)},
                {"X-Job-Grant", claimB.path("grant").asString()}, {"X-Job-Grant", "not a grant"}}) {
            assertProblem(sendBytesAsWorker(outputsA, bytes(10), headers), 403, "job.grant_refused");
            assertProblem(getAsWorker("/internal/v1/jobs/" + a + "/claims/" + claimA.path("claimId").asString()
                    + "/inputs/source", headers), 403, "job.grant_refused");
        }
        // B's grant with B's claim id on job A, and A's grant with another claim id.
        assertProblem(sendBytesAsWorker("/internal/v1/jobs/" + a + "/claims/" + claimB.path("claimId").asString()
                + "/outputs", bytes(10), "X-Job-Grant", claimB.path("grant").asString()), 403, "job.grant_refused");
        assertProblem(sendBytesAsWorker("/internal/v1/jobs/" + a + "/claims/" + UUID.randomUUID() + "/outputs",
                bytes(10), "X-Job-Grant", claimA.path("grant").asString()), 403, "job.grant_refused");
        // The probe's handler opens no input.
        assertProblem(getAsWorker("/internal/v1/jobs/" + a + "/claims/" + claimA.path("claimId").asString()
                + "/inputs/source", "X-Job-Grant", claimA.path("grant").asString()), 404, "request.not_found");
        assertThat(storage.outputs).isEmpty();

        // An ended claim: nothing more is stored.
        outcome(a, claimA.path("claimId").asString(), claimA.path("grant").asString(), "{\"outcome\":\"done\"}");
        assertProblem(postOutput(a, claimA, bytes(10), true), 403, "job.grant_refused");
        // A lapsed claim, before any sweep.
        clock.advance(Duration.ofSeconds(91));
        assertProblem(postOutput(b, claimB, bytes(10), true), 403, "job.grant_refused");
        assertThat(storage.outputs).isEmpty();
        assertThat(fileFolders()).isEqualTo(before);
    }

    @Test
    void aClaimThatEndsWhileAnOutputArrivesStoresNothing() throws Exception {
        int mib = 1024 * 1024;
        for (int size : new int[] {4 * mib + 10, 4 * mib}) {
            UUID id = createProbe("interactive", 0, 0, null);
            JsonNode claim = json(claim(id));
            java.util.Set<String> before = fileFolders();
            // The claim lapses once the first 4 MiB have arrived: either before the next piece (stillLive), or, with
            // nothing after them, between the last piece and the output's record (the locked re-check).
            java.io.InputStream body = new java.io.InputStream() {
                private int sent;

                @Override
                public int read() {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    if (sent >= 4 * mib && clock.instant().isBefore(START.plusSeconds(91))) {
                        clock.advance(Duration.ofSeconds(91));
                    }
                    if (sent >= size) {
                        return -1;
                    }
                    int count = Math.min(length, size - sent);
                    sent += count;
                    return count;
                }
            };

            assertThatThrownBy(() -> jobFiles.store(id,
                            UUID.fromString(claim.path("claimId").asString()), claim.path("grant").asString(), -1, body))
                    .isInstanceOf(io.github.gmshield.shared.service.ApiException.class)
                    .satisfies(thrown -> assertThat(((io.github.gmshield.shared.service.ApiException) thrown).problem()
                            .code()).isEqualTo("job.grant_refused"));

            assertThat(storage.outputs).as("size %d", size).isEmpty();
            assertThat(fileFolders()).as("size %d", size).isEqualTo(before);
            clock.set(START);
        }
    }

    @Test
    void anOutputNoDoneOutcomeAdoptsIsRemovedByTheSweepOnceItsJobEnded() throws Exception {
        UUID id = createProbe("interactive", 0, 0, null);
        JsonNode claim = json(claim(id));
        UUID fileId = UUID.fromString(json(postOutput(id, claim, bytes(3000), true)).path("fileId").asString());

        sweep.sweep();   // the job is still running: its output stays
        assertThat(storage.outputs).containsKey(fileId);
        assertThat(files.fileExists(ProbeJobs.OWNER, fileId)).isTrue();

        outcome(id, claim.path("claimId").asString(), claim.path("grant").asString(),
                "{\"outcome\":\"failed\",\"code\":\"probe.failed\",\"retry\":false}");
        sweep.sweep();

        assertThat(storage.outputs).isEmpty();
        assertThat(files.fileExists(ProbeJobs.OWNER, fileId)).isFalse();
        assertThat(logEvents()).contains("job.output_removed");
    }

    @Test
    void anOutputStillMarkedAsWritingIsLeftToItsWriterForTenMinutesAfterItsJobEnded() throws Exception {
        UUID id = createProbe("interactive", 0, 0, null);
        outcomeDone(id);
        UUID writing = UUID.randomUUID();
        storage.outputs.put(writing, new JobOutputRow(writing, id, ProbeJobs.OWNER, storage.row(id).claimId(), "writing",
                0, 0, START, START));
        files.writePiece(ProbeJobs.OWNER, writing, 0, bytes(10));

        clock.advance(Duration.ofMinutes(10).minusSeconds(1));
        sweep.sweep();
        assertThat(storage.outputs).containsKey(writing);
        clock.advance(Duration.ofSeconds(1));
        sweep.sweep();
        assertThat(storage.outputs).doesNotContainKey(writing);
        assertThat(files.fileExists(ProbeJobs.OWNER, writing)).isFalse();
    }

    // ---- cancel (Story 1.16)

    @Test
    void aRunningJobCancelledByItsOwnerEndsItsClaimSoItsLaterRenewalOutputAndReportAreRefused() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID id = createAs(owner, Lane.INGESTION);
        JsonNode claim = json(claim(id));
        String claimId = claim.path("claimId").asString();
        String grant = claim.path("grant").asString();

        assertThat(cancelAs(UUID.randomUUID(), id)).as("another owner's job").isFalse();
        assertThat(storage.row(id).state()).isEqualTo("running");
        assertThat(cancelAs(owner, id)).isTrue();

        JobRow cancelled = storage.row(id);
        assertThat(cancelled.state()).isEqualTo("cancelled");
        assertThat(cancelled.grantHash()).isNull();
        assertThat(cancelled.attempt()).isEqualTo(1);
        assertProblem(renew(id, claimId, grant), 403, "job.grant_refused");
        assertProblem(postOutput(id, claim, bytes(1000), true), 403, "job.grant_refused");
        assertProblem(outcome(id, claimId, grant, "{\"outcome\":\"done\"}"), 403, "job.grant_refused");
        assertThat(storage.row(id).state()).isEqualTo("cancelled");
        assertThat(storage.outputs).isEmpty();
        // Ended: a second cancel changes nothing, and neither does a claim.
        assertThat(cancelAs(owner, id)).isFalse();
        assertProblem(claim(id), 409, "job.not_offered");
        assertThat(logEvents()).contains("job.cancelled");
    }

    @Test
    void waitingAndOfferedJobsCancelTooAndTheIngestionLaneMovesOn() {
        UUID owner = UUID.randomUUID();
        UUID first = createAs(owner, Lane.INGESTION);
        UUID second = createAs(owner, Lane.INGESTION);
        assertThat(storage.row(first).state()).isEqualTo("offered");
        assertThat(storage.row(second).state()).isEqualTo("waiting");

        assertThat(cancelAs(owner, second)).isTrue();
        assertThat(storage.row(second).state()).isEqualTo("cancelled");
        assertThat(storage.row(first).state()).isEqualTo("offered");

        UUID third = createAs(owner, Lane.INGESTION);
        assertThat(cancelAs(owner, first)).isTrue();
        // The lane was busy with the offered job: once it is cancelled, the next one is offered.
        assertThat(storage.row(first).state()).isEqualTo("cancelled");
        assertThat(storage.row(third).state()).isEqualTo("offered");
    }

    @Test
    void cancelNeedsATransaction() {
        UUID owner = UUID.randomUUID();
        UUID id = createAs(owner, Lane.INTERACTIVE);
        assertThatThrownBy(() -> OwnerContext.runAs(owner, () -> jobs.cancel(id)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(storage.row(id).state()).isEqualTo("offered");
    }

    // ---- trace

    @Test
    void everyBackendLineAboutAJobCarriesTheTraceIdOfTheRequestThatCreatedIt() throws Exception {
        String trace = TraceContext.newTraceId();
        UUID id = createProbe("interactive", 0, 1, trace);
        JsonNode first = json(claim(id));
        outcome(id, first.path("claimId").asString(), first.path("grant").asString(),
                "{\"outcome\":\"failed\",\"code\":\"probe.failed\"}");
        JsonNode second = json(claim(id));
        clock.advance(Duration.ofSeconds(10));
        renew(id, second.path("claimId").asString(), second.path("grant").asString(), second.path("traceparent").asString());
        outcome(id, second.path("claimId").asString(), second.path("grant").asString(), "{\"outcome\":\"done\"}",
                second.path("traceparent").asString());

        List<ILoggingEvent> jobLines = logged.list.stream().filter(event -> event.getKeyValuePairs() != null
                && event.getKeyValuePairs().stream().anyMatch(pair -> pair.key.equals("jobId") && id.toString()
                .equals(String.valueOf(pair.value)))).toList();
        assertThat(jobLines).extracting(ILoggingEvent::getMessage)
                .contains("job.created", "job.offered", "job.claimed", "job.retrying", "job.done");
        assertThat(jobLines).allSatisfy(event -> assertThat(event.getMDCPropertyMap().get("traceId")).isEqualTo(trace));
        // The worker's calls that carry the job's traceparent are logged under it too.
        assertThat(logged.list.stream().filter(event -> "request.completed".equals(event.getMessage())
                && trace.equals(event.getMDCPropertyMap().get("traceId")))).hasSizeGreaterThanOrEqualTo(3);
    }

    // ---- helpers

    private UUID createProbe(String lane, int workMs, int failTimes, String trace) throws Exception {
        String body = "{\"lane\":\"" + lane + "\",\"workMs\":" + workMs + ",\"failTimes\":" + failTimes + "}";
        HttpResponse<String> created = trace == null
                ? postJson(PROBES, body)
                : postJson(PROBES, body, "traceparent", "00-" + trace + "-00f067aa0ba902b7-01");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(json(created).path("id").asString());
    }

    private boolean cancelAs(UUID owner, UUID id) {
        return OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status -> jobs.cancel(id)));
    }

    private UUID createAs(UUID owner, String kind) {
        return OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status ->
                jobs.create(kind, job -> { }).id()));
    }

    private HttpResponse<String> claimStating(UUID id, String body) throws Exception {
        return sendAsWorker("POST", "/internal/v1/jobs/" + id + "/claims", body);
    }

    private UUID createAs(UUID owner, Lane lane) {
        return OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status ->
                jobs.create(ProbeJobs.KIND, lane, job -> probes.insert(new ProbeJobRow(job.id(), 0, 0))).id()));
    }

    private void outcomeDone(UUID id) throws Exception {
        JsonNode claim = json(claim(id));
        assertThat(outcome(id, claim.path("claimId").asString(), claim.path("grant").asString(),
                "{\"outcome\":\"done\"}").statusCode()).isEqualTo(204);
    }

    private HttpResponse<String> claim(UUID id) throws Exception {
        return sendAsWorker("POST", "/internal/v1/jobs/" + id + "/claims", null);
    }

    private HttpResponse<String> renew(UUID id, String claimId, String grant, String... traceparent) throws Exception {
        List<String> headers = new ArrayList<>(List.of("X-Job-Grant", grant));
        if (traceparent.length > 0) {
            headers.addAll(List.of("traceparent", traceparent[0]));
        }
        return sendAsWorker("POST", "/internal/v1/jobs/" + id + "/claims/" + claimId + "/renewals", null,
                headers.toArray(String[]::new));
    }

    private HttpResponse<String> outcome(UUID id, String claimId, String grant, String body, String... traceparent)
            throws Exception {
        List<String> headers = new ArrayList<>(List.of("X-Job-Grant", grant));
        if (traceparent.length > 0) {
            headers.addAll(List.of("traceparent", traceparent[0]));
        }
        return sendAsWorker("POST", "/internal/v1/jobs/" + id + "/claims/" + claimId + "/outcome", body,
                headers.toArray(String[]::new));
    }

    private HttpResponse<String> postJson(String path, String body, String... headers) throws Exception {
        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return java.net.http.HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithout(String path, String... headers) throws Exception {
        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder(uri(path))
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody());
        if (headers.length > 0) {
            request.headers(headers);
        }
        return java.net.http.HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postOutput(UUID id, JsonNode claim, byte[] content, boolean withLength) throws Exception {
        HttpRequest.BodyPublisher body = withLength ? HttpRequest.BodyPublishers.ofByteArray(content)
                : HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(content));
        HttpRequest request = HttpRequest.newBuilder(uri("/internal/v1/jobs/" + id + "/claims/"
                        + claim.path("claimId").asString() + "/outputs"))
                .header("Authorization", "Bearer " + WORKER_CREDENTIAL).header("Content-Type", "application/octet-stream")
                .header("X-Job-Grant", claim.path("grant").asString()).POST(body).build();
        return java.net.http.HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Sends an output's headers with {@code Content-Length: length} and no body, half-closes the connection, and
     * expects 413 {@code job.output_too_large}: the refusal must come from the declared length alone.
     */
    private void assertDeclaredLengthRefused(UUID id, JsonNode claim, long length) throws Exception {
        java.net.URI target = uri("/internal/v1/jobs/" + id + "/claims/" + claim.path("claimId").asString()
                + "/outputs");
        String head = "POST " + target.getRawPath() + " HTTP/1.1\r\n"
                + "Host: " + target.getHost() + ":" + target.getPort() + "\r\n"
                + "Authorization: Bearer " + WORKER_CREDENTIAL + "\r\n"
                + "X-Job-Grant: " + claim.path("grant").asString() + "\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Connection: close\r\n\r\n";
        String answer;
        try (java.net.Socket socket = new java.net.Socket(target.getHost(), target.getPort())) {
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            socket.shutdownOutput();
            answer = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(answer).as(answer).startsWith("HTTP/1.1 413 ");
        String body = answer.substring(answer.indexOf('{'), answer.lastIndexOf('}') + 1);
        assertThat(tools.jackson.databind.json.JsonMapper.builder().build().readTree(body).path("code").asString())
                .isEqualTo("job.output_too_large");
    }

    private HttpResponse<String> sendBytesAsWorker(String path, byte[] content, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + WORKER_CREDENTIAL).header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(content));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return java.net.http.HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The file folders of the probe owner in the file store: one per stored output, whatever its pieces. */
    private java.util.Set<String> fileFolders() throws java.io.IOException {
        java.nio.file.Path folder = filesDir.resolve(ProbeJobs.OWNER.toString());
        if (!java.nio.file.Files.isDirectory(folder)) {
            return java.util.Set.of();
        }
        try (java.util.stream.Stream<java.nio.file.Path> entries = java.nio.file.Files.list(folder)) {
            return entries.map(entry -> entry.getFileName().toString()).collect(java.util.stream.Collectors.toSet());
        }
    }

    private byte[] readBack(UUID owner, UUID fileId, int pieces) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < pieces; i++) {
            out.writeBytes(files.readPiece(owner, fileId, i));
        }
        return out.toByteArray();
    }

    private static byte[] bytes(int size) {
        byte[] content = new byte[size];
        for (int i = 0; i < size; i++) {
            content[i] = (byte) (i * 31 + (i >>> 11));
        }
        return content;
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response).path("code").asString()).isEqualTo(code);
    }

    private List<String> logEvents() {
        return logged.list.stream().map(ILoggingEvent::getMessage).toList();
    }

    private String logText() {
        JsonLineLogFormatter formatter = new JsonLineLogFormatter();
        return String.join("", logged.list.stream().map(formatter::format).toList());
    }
}
