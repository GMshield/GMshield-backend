package io.github.gmshield.jobs.data;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.JobSweep;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.jobs.service.Lane;
import io.github.gmshield.jobs.service.ProbeJobs;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.shared.service.TraceContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 1.12 against a real PostgreSQL and Redis (test mode: postgres:5432, redis:6379): create and rollback, the
 * offer and its message, the claim, the second claim, grants, lapses and attempts, a lost message (XDEL), a flushed
 * Redis (FLUSHALL: the group is created again and offered jobs are offered again), trimming and the ingestion round
 * robin. The sweep is driven by the test, on a clock the test moves.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
            "gmshield.materials.upload-sweep.enabled=false",
            "gmshield.materials.version-removal-sweep.enabled=false",
            "gmshield.library.deletion-sweep.enabled=false",
            "gmshield.materials.index-retry-sweep.enabled=false",
            "gmshield.files.dir=${java.io.tmpdir}/gmshield-job-it",
            "gmshield.jobs.output-max-bytes=6MB", "gmshield.jobs.output-claim-max-bytes=10MB"})
@ActiveProfiles("test-mode")
class JobIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String WORKER_CREDENTIAL = "gmshield-test-mode-worker-credential-not-a-secret";
    private static final String INTERACTIVE = "gmshield:work:interactive";
    private static final String INGESTION = "gmshield:work:ingestion";

    /** A clock the test moves; it starts at the real time, because Redis stream ids carry Redis's own clock. */
    static final class TestClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** A test-only kind that calls a model (Story 2.10): no production kind does yet. */
    static final String TEST_MODEL = "test.model";

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }

        @Bean
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
                public boolean callsModel(JobRef job) {
                    return true;
                }

                @Override
                public JsonNode input(JobRef job) {
                    return null;
                }

                @Override
                public void completed(JobRef job, JsonNode result) {
                    // Nothing to record: the kind exists for the claim rules only.
                }

                @Override
                public void failed(JobRef job, String code) {
                    // Nothing to record: the kind exists for the claim rules only.
                }
            };
        }
    }

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JobSweep sweep;
    @Autowired
    private Jobs jobs;
    @Autowired
    private ProbeJobStore probes;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private TestClock clock;
    @Autowired
    private io.github.gmshield.jobs.service.JobFiles jobFiles;
    @Autowired
    private io.github.gmshield.vault.service.FileStore files;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void cleanSlate() {
        jdbc.sql("delete from job_output").update();
        jdbc.sql("delete from job_probe").update();
        jdbc.sql("delete from job").update();
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        clock.now.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        sweep.ensureGroups();
    }

    @Test
    void aJobCreatedInATransactionThatRollsBackLeavesNoRowAndNoMessage() {
        UUID owner = UUID.randomUUID();
        OwnerContext.runAs(owner, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jobs.create(ProbeJobs.KIND, Lane.INTERACTIVE, job -> probes.insert(new ProbeJobRow(job.id(), 0, 0)));
            status.setRollbackOnly();
        }));

        assertThat(jdbc.sql("select count(*) from job").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from job_probe").query(Long.class).single()).isZero();
        assertThat(redis.opsForStream().size(INTERACTIVE)).isZero();
    }

    @Test
    void aCommittedJobIsOfferedWithOneMessageHoldingOnlyItsIdAndAWorkerOfTheGroupReadsIt() throws Exception {
        UUID id = createProbe("interactive", 0, 0);

        assertThat(state(id)).isEqualTo("offered");
        List<MapRecord<String, Object, Object>> messages = redis.opsForStream().range(INTERACTIVE, Range.unbounded());
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().getValue()).isEqualTo(Map.of("job", id.toString()));

        List<MapRecord<String, Object, Object>> read = redis.opsForStream().read(Consumer.from("workers", "it"),
                StreamReadOptions.empty().count(10), StreamOffset.create(INTERACTIVE, ReadOffset.lastConsumed()));
        assertThat(read).extracting(record -> record.getValue().get("job")).containsExactly(id.toString());
    }

    @Test
    void aClaimRunsTheJobASecondClaimIsRefusedAndOnlyTheLiveGrantActs() throws Exception {
        String trace = TraceContext.newTraceId();
        UUID a = createProbe("interactive", 2500, 0, trace);
        UUID b = createProbe("interactive", 0, 0);

        JsonNode claimA = json(post("/internal/v1/jobs/" + a + "/claims", null), 201);
        JsonNode claimB = json(post("/internal/v1/jobs/" + b + "/claims", null), 201);

        assertThat(claimA.path("attempt").asInt()).isEqualTo(1);
        assertThat(claimA.path("input").path("workMs").asInt()).isEqualTo(2500);
        assertThat(TraceContext.fromTraceparent(claimA.path("traceparent").asString())).contains(trace);
        assertThat(state(a)).isEqualTo("running");
        assertThat(jdbc.sql("select trace_id from job where id = ?").param(a).query(String.class).single()).isEqualTo(trace);
        byte[] stored = jdbc.sql("select grant_hash from job where id = ?").param(a).query(byte[].class).single();
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(claimA.path("grant").asString());
        assertProblem(post("/internal/v1/jobs/" + a + "/claims", null), 409, "job.claimed");

        String renewA = "/internal/v1/jobs/" + a + "/claims/" + claimA.path("claimId").asString() + "/renewals";
        assertProblem(post(renewA, null, "X-Job-Grant", claimB.path("grant").asString()), 403, "job.grant_refused");
        assertProblem(post("/internal/v1/jobs/" + b + "/claims/" + claimB.path("claimId").asString() + "/renewals", null,
                "X-Job-Grant", claimA.path("grant").asString()), 403, "job.grant_refused");
        assertThat(post(renewA, null, "X-Job-Grant", claimA.path("grant").asString()).statusCode()).isEqualTo(200);

        String outcomeA = "/internal/v1/jobs/" + a + "/claims/" + claimA.path("claimId").asString() + "/outcome";
        assertThat(post(outcomeA, "{\"outcome\":\"done\",\"result\":{\"workedMs\":2500}}", "X-Job-Grant",
                claimA.path("grant").asString()).statusCode()).isEqualTo(204);
        assertThat(state(a)).isEqualTo("done");
        assertProblem(post("/internal/v1/jobs/" + a + "/claims", null), 409, "job.not_offered");
        assertProblem(get("/internal/v1/version", false), 401, "service.unauthenticated");
    }

    @Test
    void aModelKindsClaimRecordsItsModelOnTheRowAndAWorkerWithoutItsProviderEndsTheJobWithoutAnAttempt()
            throws Exception {
        UUID answered = createModelJob();
        UUID refused = createModelJob();

        JsonNode claim = json(post("/internal/v1/jobs/" + answered + "/claims", "{\"providers\":[\"azure-openai\"]}"),
                201);
        assertProblem(post("/internal/v1/jobs/" + refused + "/claims", "{\"providers\":[]}"), 409, "job.not_offered");

        assertThat(claim.path("model").path("provider").asString()).isEqualTo("azure-openai");
        assertThat(model(answered)).containsExactly("azure-openai", "gpt-6.1-terra", "full");
        String outcome = "/internal/v1/jobs/" + answered + "/claims/" + claim.path("claimId").asString() + "/outcome";
        assertThat(post(outcome, "{\"outcome\":\"done\"}", "X-Job-Grant", claim.path("grant").asString()).statusCode())
                .isEqualTo(204);
        assertThat(state(answered)).isEqualTo("done");
        assertThat(model(answered)).containsExactly("azure-openai", "gpt-6.1-terra", "full");

        assertThat(state(refused)).isEqualTo("failed");
        assertThat(jdbc.sql("select outcome_code from job where id = ?").param(refused).query(String.class).single())
                .isEqualTo("job.provider_unavailable");
        assertThat(jdbc.sql("select attempt from job where id = ?").param(refused).query(Integer.class).single()).isZero();
        assertThat(model(refused)).containsExactly(null, null, null);
    }

    @Test
    void aLapsedClaimGoesBackToWaitingAndIsOfferedAgainUntilTheAttemptsRunOut() throws Exception {
        UUID id = createProbe("interactive", 0, 0);
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(json(post("/internal/v1/jobs/" + id + "/claims", null), 201).path("attempt").asInt()).isEqualTo(attempt);
            clock.advance(Duration.ofSeconds(91));
            sweep.sweep();
        }

        assertThat(state(id)).isEqualTo("failed");
        assertThat(jdbc.sql("select outcome_code from job where id = ?").param(id).query(String.class).single())
                .isEqualTo("job.attempts_exhausted");
        assertThat(jdbc.sql("select attempt from job where id = ?").param(id).query(Integer.class).single()).isEqualTo(3);
        assertThat(jobIds(INTERACTIVE)).containsExactly(id.toString(), id.toString(), id.toString());
    }

    @Test
    void anOfferWhoseMessageWasDeletedIsMadeAgainAfterTwoMinutes() throws Exception {
        UUID id = createProbe("interactive", 0, 0);
        String messageId = redis.opsForStream().range(INTERACTIVE, Range.unbounded()).getFirst().getId().getValue();
        redis.opsForStream().delete(INTERACTIVE, messageId);

        clock.advance(Duration.ofSeconds(100));
        sweep.sweep();
        assertThat(jobIds(INTERACTIVE)).isEmpty();
        clock.advance(Duration.ofSeconds(21));
        sweep.sweep();

        assertThat(jobIds(INTERACTIVE)).containsExactly(id.toString());
        assertThat(json(post("/internal/v1/jobs/" + id + "/claims", null), 201).path("attempt").asInt()).isEqualTo(1);
    }

    @Test
    void afterRedisIsFlushedTheGroupIsCreatedAgainAndEveryJobFinishes() throws Exception {
        UUID offered = createProbe("interactive", 0, 0);
        UUID running = createProbe("interactive", 0, 0);
        JsonNode claim = json(post("/internal/v1/jobs/" + running + "/claims", null), 201);

        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        sweep.sweep();
        assertThat(groups(INTERACTIVE)).containsExactly("workers");
        assertThat(groups(INGESTION)).containsExactly("workers");

        // The running job reports without Redis; the offered one is offered again after the re-offer delay.
        assertThat(post("/internal/v1/jobs/" + running + "/claims/" + claim.path("claimId").asString() + "/outcome",
                "{\"outcome\":\"done\"}", "X-Job-Grant", claim.path("grant").asString()).statusCode()).isEqualTo(204);
        clock.advance(Duration.ofSeconds(121));
        sweep.sweep();
        List<MapRecord<String, Object, Object>> read = redis.opsForStream().read(Consumer.from("workers", "it"),
                StreamReadOptions.empty().count(10), StreamOffset.create(INTERACTIVE, ReadOffset.lastConsumed()));
        assertThat(read).extracting(record -> record.getValue().get("job")).containsExactly(offered.toString());
        JsonNode second = json(post("/internal/v1/jobs/" + offered + "/claims", null), 201);
        assertThat(post("/internal/v1/jobs/" + offered + "/claims/" + second.path("claimId").asString() + "/outcome",
                "{\"outcome\":\"done\"}", "X-Job-Grant", second.path("grant").asString()).statusCode()).isEqualTo(204);

        assertThat(jdbc.sql("select state from job").query(String.class).list()).containsOnly("done");
    }

    @Test
    void theSweepTrimsMessagesOlderThanAnHour() throws Exception {
        createProbe("interactive", 0, 0);
        createProbe("interactive", 0, 0);
        assertThat(redis.opsForStream().size(INTERACTIVE)).isEqualTo(2);
        sweep.trimStreams();
        assertThat(redis.opsForStream().size(INTERACTIVE)).isEqualTo(2);

        clock.advance(Duration.ofMinutes(61));
        sweep.trimStreams();

        assertThat(redis.opsForStream().size(INTERACTIVE)).isZero();
        assertThat(groups(INTERACTIVE)).containsExactly("workers");
    }

    @Test
    void ingestionJobsGoOutOneAtATimeAlternatingBetweenOwners() throws Exception {
        UUID ownerA = UUID.fromString("0195a3c0-0000-7000-8000-00000000000a");
        UUID ownerB = UUID.fromString("0195a3c0-0000-7000-8000-00000000000b");
        UUID a1 = createAs(ownerA);
        clock.advance(Duration.ofSeconds(1));
        UUID a2 = createAs(ownerA);
        clock.advance(Duration.ofSeconds(1));
        UUID b1 = createAs(ownerB);

        List<UUID> order = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            List<UUID> offered = jdbc.sql("select id from job where state = 'offered'").query(UUID.class).list();
            assertThat(offered).hasSize(1);
            order.add(offered.getFirst());
            sweep.sweep();
            assertThat(jdbc.sql("select count(*) from job where state = 'offered'").query(Long.class).single()).isEqualTo(1);
            JsonNode claim = json(post("/internal/v1/jobs/" + offered.getFirst() + "/claims", null), 201);
            assertThat(post("/internal/v1/jobs/" + offered.getFirst() + "/claims/" + claim.path("claimId").asString()
                    + "/outcome", "{\"outcome\":\"done\"}", "X-Job-Grant", claim.path("grant").asString()).statusCode())
                    .isEqualTo(204);
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(order).containsExactly(a1, b1, a2);
        assertThat(jobIds(INGESTION)).containsExactly(a1.toString(), b1.toString(), a2.toString());
    }

    // ---- job outputs (Story 1.15), against PostgreSQL

    @Test
    void anOutputStillBeingWrittenIsSweptOnlyOnceItsJobEndedMoreThanTheGraceAgo() throws Exception {
        UUID id = createProbe("interactive", 0, 0);
        JsonNode claim = json(post("/internal/v1/jobs/" + id + "/claims", null), 201);
        UUID stored = store(id, claim, 1000).fileId();
        UUID writing = UUID.randomUUID();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("insert into job_output (id, job_id, owner_id, claim_id, state, created_at, updated_at)"
                        + " values (?, ?, ?, ?, 'writing', ?, ?)")
                .params(writing, id, ProbeJobs.OWNER, UUID.fromString(claim.path("claimId").asString()),
                        java.sql.Timestamp.from(now), java.sql.Timestamp.from(now)).update();
        files.writePiece(ProbeJobs.OWNER, writing, 0, new byte[] {1, 2, 3});
        sweep.sweep();
        assertThat(outputs(id)).containsExactlyInAnyOrder(stored, writing);   // the job still runs

        assertThat(post("/internal/v1/jobs/" + id + "/claims/" + claim.path("claimId").asString() + "/outcome",
                "{\"outcome\":\"done\"}", "X-Job-Grant", claim.path("grant").asString()).statusCode()).isEqualTo(204);
        sweep.sweep();
        // The stored output nobody adopted goes at once; the one still being written waits for its writer.
        assertThat(outputs(id)).containsExactly(writing);
        assertThat(files.fileExists(ProbeJobs.OWNER, stored)).isFalse();
        clock.advance(Duration.ofMinutes(10).minusSeconds(1));
        sweep.sweep();
        assertThat(outputs(id)).containsExactly(writing);
        assertThat(files.fileExists(ProbeJobs.OWNER, writing)).isTrue();
        clock.advance(Duration.ofSeconds(1));
        sweep.sweep();
        assertThat(outputs(id)).isEmpty();
        assertThat(files.fileExists(ProbeJobs.OWNER, writing)).isFalse();
    }

    @Test
    void aClaimsLimitCountsOnlyItsOwnStoredOutputs() throws Exception {
        UUID id = createProbe("interactive", 0, 0);
        JsonNode claim = json(post("/internal/v1/jobs/" + id + "/claims", null), 201);
        UUID claimId = UUID.fromString(claim.path("claimId").asString());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int nine = 9 * 1024 * 1024;
        // Neither counts: one is still being written, the other is another claim's.
        for (Object[] row : new Object[][] {{claimId, "writing"}, {UUID.randomUUID(), "stored"}}) {
            jdbc.sql("insert into job_output (id, job_id, owner_id, claim_id, state, size_bytes, piece_count, created_at,"
                            + " updated_at) values (?, ?, ?, ?, ?, ?, 3, ?, ?)")
                    .params(UUID.randomUUID(), id, ProbeJobs.OWNER, row[0], row[1], nine, java.sql.Timestamp.from(now),
                            java.sql.Timestamp.from(now)).update();
        }

        store(id, claim, 6 * 1024 * 1024);
        store(id, claim, 4 * 1024 * 1024);

        // 10 MiB stored by this claim: one byte more passes the claim's limit, and nothing of it remains.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store(id, claim, 1))
                .isInstanceOf(io.github.gmshield.shared.service.ApiException.class)
                .satisfies(thrown -> assertThat(((io.github.gmshield.shared.service.ApiException) thrown).problem().code())
                        .isEqualTo("job.output_too_large"));
        assertThat(jdbc.sql("select count(*) from job_output where job_id = ? and state = 'stored' and claim_id = ?")
                .params(id, claimId).query(Long.class).single()).isEqualTo(2);
    }

    private io.github.gmshield.jobs.service.JobFiles.Stored store(UUID id, JsonNode claim, int size) {
        return jobFiles.store(id, UUID.fromString(claim.path("claimId").asString()), claim.path("grant").asString(), -1,
                new java.io.ByteArrayInputStream(new byte[size]));
    }

    private List<UUID> outputs(UUID jobId) {
        return jdbc.sql("select id from job_output where job_id = ?").param(jobId).query(UUID.class).list();
    }

    // ---- helpers

    private UUID createProbe(String lane, int workMs, int failTimes, String... trace) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/internal/v1/test-mode/probe-jobs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"lane\":\"" + lane + "\",\"workMs\":" + workMs
                        + ",\"failTimes\":" + failTimes + "}"));
        if (trace.length > 0) {
            request.header("traceparent", "00-" + trace[0] + "-00f067aa0ba902b7-01");
        }
        HttpResponse<String> created = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return UUID.fromString(json(created, 201).path("id").asString());
    }

    private UUID createAs(UUID owner) {
        return OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status -> {
            JobRef job = jobs.create(ProbeJobs.KIND, Lane.INGESTION, ref -> probes.insert(new ProbeJobRow(ref.id(), 0, 0)));
            return job.id();
        }));
    }

    private UUID createModelJob() {
        return OwnerContext.callAs(UUID.randomUUID(), () -> new TransactionTemplate(transactions).execute(status ->
                jobs.create(TEST_MODEL, ref -> { }).id()));
    }

    private List<String> model(UUID id) {
        return jdbc.sql("select model_provider, model_id, model_tier from job where id = ?").param(id)
                .query((rs, n) -> java.util.Arrays.asList(rs.getString(1), rs.getString(2), rs.getString(3))).single();
    }

    private String state(UUID id) {
        return jdbc.sql("select state from job where id = ?").param(id).query(String.class).single();
    }

    private List<String> jobIds(String stream) {
        return redis.opsForStream().range(stream, Range.unbounded()).stream()
                .map(record -> String.valueOf(record.getValue().get("job"))).toList();
    }

    private List<String> groups(String stream) {
        return redis.opsForStream().groups(stream).stream().map(group -> group.groupName()).toList();
    }

    private HttpResponse<String> post(String path, String json, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + WORKER_CREDENTIAL)
                .POST(json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        if (headers.length > 0) {
            request.headers(headers);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, boolean credential) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (credential) {
            request.header("Authorization", "Bearer " + WORKER_CREDENTIAL);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(JSON.readTree(response.body()).path("code").asString()).isEqualTo(code);
    }
}
