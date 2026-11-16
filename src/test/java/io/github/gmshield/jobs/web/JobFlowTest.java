package io.github.gmshield.jobs.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.gmshield.HttpTestSupport;
import io.github.gmshield.jobs.data.InMemoryJobStorage;
import io.github.gmshield.jobs.data.InMemoryJobStream;
import io.github.gmshield.jobs.data.InMemoryProbeJobStore;
import io.github.gmshield.jobs.data.JobRow;
import io.github.gmshield.jobs.data.JobTestStores;
import io.github.gmshield.jobs.data.MutableClock;
import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.JobSweep;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.jobs.service.Lane;
import io.github.gmshield.jobs.service.ProbeJobs;
import io.github.gmshield.jobs.data.ProbeJobRow;
import io.github.gmshield.shared.data.InMemoryTransactionManager;
import io.github.gmshield.shared.service.JsonLineLogFormatter;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.shared.service.TraceContext;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GMSHIELD_MODE=test")
@ActiveProfiles(value = {"test-mode", "unit"}, inheritProfiles = false)
@Import({JobTestStores.class, JobFlowTest.Handlers.class})
class JobFlowTest extends HttpTestSupport {

    /** A kind whose handler gives a JSON array as its input: never a valid claim. */
    static final String ARRAY_INPUT = "array-input";

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class Handlers {
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

    static final String PROBES = "/internal/v1/test-mode/probe-jobs";
    static final String INTERACTIVE = "gmshield:work:interactive";
    static final String INGESTION = "gmshield:work:ingestion";
    static final Instant START = Instant.parse("2026-11-16T12:00:00Z");

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

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void reset() {
        storage.rows.clear();
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
                TraceContext.newTraceId(), START, null, null, null, null, null, START, START));

        assertProblem(claim(id), 409, "job.not_offered");
        assertThat(storage.row(id).state()).isEqualTo("failed");
        assertThat(storage.row(id).outcomeCode()).isEqualTo("job.unknown_kind");
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
