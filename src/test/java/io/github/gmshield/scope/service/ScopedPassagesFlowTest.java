package io.github.gmshield.scope.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.gmshield.HttpTestSupport;
import io.github.gmshield.accounts.data.AccountStore;
import io.github.gmshield.accounts.data.InMemoryAccountStore;
import io.github.gmshield.accounts.data.InMemorySessionStore;
import io.github.gmshield.accounts.data.InMemorySignInAttemptStore;
import io.github.gmshield.accounts.data.SessionStore;
import io.github.gmshield.accounts.data.SignInAttemptStore;
import io.github.gmshield.audit.data.AuditStore;
import io.github.gmshield.audit.data.InMemoryAuditStore;
import io.github.gmshield.jobs.data.InMemoryJobStorage;
import io.github.gmshield.jobs.data.JobMemoryStores;
import io.github.gmshield.jobs.service.JobHandler;
import io.github.gmshield.jobs.service.JobPassage;
import io.github.gmshield.jobs.service.JobRef;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.jobs.service.Lane;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.data.LibraryTestStores;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.data.IndexChangeRow;
import io.github.gmshield.materials.data.InMemoryIndexStore;
import io.github.gmshield.materials.data.InMemoryMaterialStore;
import io.github.gmshield.materials.data.MaterialTestStores;
import io.github.gmshield.materials.service.IndexChanges;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.data.DataKeyStore;
import io.github.gmshield.vault.data.InMemoryDataKeyStore;
import io.github.gmshield.vault.service.Vault;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Story 2.2's internal endpoint without a database or Redis, through {@code POST
 * /internal/v1/jobs/{jobId}/claims/{claimId}/passages} as the worker calls it. No production kind reads passages until
 * Story 2.4, so a test-only handler ({@link ScopedHandler}) is given a campaign per job and answers through
 * {@link Scopes}: passages of both tiers labelled, a refusal for any id out of the scope with nothing returned, a
 * kind without passages (an index change), a bad grant, and the 1 to 100 ids. Every assertion is on passage ids; no
 * model is called.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GMSHIELD_MODE=test")
@ActiveProfiles(value = {"test-mode", "unit"}, inheritProfiles = false)
@Import(ScopedPassagesFlowTest.Stores.class)
class ScopedPassagesFlowTest extends HttpTestSupport {

    @TestConfiguration(proxyBeanMethods = false)
    @Import({LibraryTestStores.class, MaterialTestStores.class, JobMemoryStores.class})
    static class Stores {
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
        SessionStore inMemorySessions() {
            return new InMemorySessionStore(java.time.Clock.systemUTC());
        }

        @Bean
        @Primary
        AuditStore inMemoryAudit() {
            return new InMemoryAuditStore();
        }

        @Bean
        ScopedHandler scopedHandler(Scopes scopes) {
            return new ScopedHandler(scopes);
        }
    }

    /**
     * A test-only kind whose jobs read passages within a campaign's starting scope, as Story 2.4's answer handler
     * will: the test gives each job its campaign.
     */
    static class ScopedHandler implements JobHandler {

        static final String KIND = "test.scoped";
        final Map<UUID, UUID> campaigns = new ConcurrentHashMap<>();
        /** Whether each call of {@link #passages} ran in a read-only transaction, in call order. */
        final List<Boolean> readOnly = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final Scopes scopes;

        ScopedHandler(Scopes scopes) {
            this.scopes = scopes;
        }

        @Override
        public String kind() {
            return KIND;
        }

        @Override
        public Lane lane() {
            return Lane.INTERACTIVE;
        }

        @Override
        public JsonNode input(JobRef job) {
            return JsonNodeFactory.instance.objectNode();
        }

        @Override
        public List<JobPassage> passages(JobRef job, List<UUID> ids) {
            readOnly.add(TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            ResolvedScope scope = scopes.resolve(campaigns.get(job.id()), ScopeSettings.STARTING);
            return scopes.passages(scope, ids).stream().map(passage -> new JobPassage(passage.id(),
                    passage.tier().word(), passage.text(), passage.sectionPath(), passage.page())).toList();
        }

        @Override
        public void completed(JobRef job, JsonNode result) {
            // nothing to record
        }

        @Override
        public void failed(JobRef job, String code) {
            // nothing to record
        }
    }

    @Autowired
    private ScopedHandler handler;
    @Autowired
    private Jobs jobs;
    @Autowired
    private InMemoryJobStorage jobRows;
    @Autowired
    private Library library;
    @Autowired
    private InMemoryLibraryStore libraryStore;
    @Autowired
    private InMemoryMaterialStore materials;
    @Autowired
    private InMemoryIndexStore index;
    @Autowired
    private Vault vault;
    @Autowired
    private PlatformTransactionManager transactions;

    private ScopeFixture fixture;
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    /** The I/O matrix's library: A of the campaign, B of its system, C of another campaign of the same system. */
    private record Matrix(ScopeFixture.Starter gm, UUID a, List<UUID> aPassages, List<UUID> bPassages,
                          List<UUID> cPassages) {
    }

    /** A live claim: its job, claim id and grant. */
    private record Claim(UUID jobId, String claimId, String grant) {

        String passages() {
            return "/internal/v1/jobs/" + jobId + "/claims/" + claimId + "/passages";
        }
    }

    @BeforeEach
    void start() {
        fixture = new ScopeFixture(library, libraryStore, materials, index, vault, transactions);
        // One ingestion job at a time: end whatever an earlier test left offered, running or waiting.
        jobRows.rows.replaceAll((id, row) -> row.state().equals("offered") || row.state().equals("running")
                || row.state().equals("waiting") ? row.released("cancelled", null, row.updatedAt()) : row);
        logged.start();
        root.addAppender(logged);
    }

    @AfterEach
    void stop() {
        root.detachAppender(logged);
        logged.stop();
    }

    private Matrix matrix() {
        ScopeFixture.Starter gm = fixture.starter();
        UUID a = fixture.material(gm.owner(), gm.campaign(), null, "ready").getFirst();
        UUID b = fixture.material(gm.owner(), null, gm.system(), "ready").getFirst();
        UUID c = fixture.material(gm.owner(), fixture.otherCampaign(gm), null, "ready").getFirst();
        return new Matrix(gm, a, fixture.passages(gm.owner(), a, "Texto A", 3),
                fixture.passages(gm.owner(), b, "Texto B", 2), fixture.passages(gm.owner(), c, "Texto C", 1));
    }

    @Test
    void passagesOfBothTiersAreAnsweredLabelledInTheOrderAskedEachOnce() throws Exception {
        Matrix lib = matrix();
        Claim claim = scopedClaim(lib.gm(), lib.gm().campaign());
        UUID b1 = lib.bPassages().get(1);
        UUID a0 = lib.aPassages().getFirst();
        handler.readOnly.clear();

        HttpResponse<String> answer = passages(claim, List.of(b1, a0, b1));

        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
        assertThat(answer.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode read = json(answer).path("passages");
        assertThat(read.size()).isEqualTo(2);
        assertThat(read.get(0).path("id").asString()).isEqualTo(b1.toString());
        assertThat(read.get(0).path("tier").asString()).isEqualTo("system");
        assertThat(read.get(0).path("text").asString()).isEqualTo("Texto B 1");
        assertThat(read.get(0).path("sectionPath")).extracting(JsonNode::asString)
                .containsExactly("Capítulo", "Seção 1");
        assertThat(read.get(0).path("page").asInt()).isEqualTo(1);
        assertThat(read.get(1).path("id").asString()).isEqualTo(a0.toString());
        assertThat(read.get(1).path("tier").asString()).isEqualTo("campaign");
        assertThat(read.get(1).path("text").asString()).isEqualTo("Texto A 0");
        // The handler ran in a read-only transaction.
        assertThat(handler.readOnly).containsExactly(true);
        assertNoTextLogged();
        assertThat(logged.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .isEqualTo("job.passages_read"));
    }

    @Test
    void anIdOfAnotherCampaignADiscardedVersionOrAnotherOwnerAmongGoodOnesIsRefusedWithNothingReturned()
            throws Exception {
        Matrix lib = matrix();
        ScopeFixture.Starter them = fixture.starter();
        UUID theirVersion = fixture.material(them.owner(), them.campaign(), null, "ready").getFirst();
        UUID theirs = fixture.passages(them.owner(), theirVersion, "Texto T", 1).getFirst();
        UUID discarded = fixture.material(lib.gm().owner(), lib.gm().campaign(), null, "ready").getFirst();
        UUID discardedPassage = fixture.passages(lib.gm().owner(), discarded, "Texto D", 1).getFirst();
        fixture.state(lib.gm().owner(), discarded, "discarded");
        Claim claim = scopedClaim(lib.gm(), lib.gm().campaign());

        for (UUID outside : List.of(lib.cPassages().getFirst(), discardedPassage, theirs, Ids.newId())) {
            HttpResponse<String> answer = passages(claim, List.of(lib.aPassages().getFirst(), outside,
                    lib.bPassages().getFirst()));
            assertProblem(answer, 403, "scope.passage_refused");
            assertThat(answer.body()).doesNotContain(outside.toString()).doesNotContain("Texto")
                    .doesNotContain("passages\"");
        }
        assertNoTextLogged();
        // The same claim still reads what is in the scope.
        assertThat(passages(claim, List.of(lib.aPassages().getFirst())).statusCode()).isEqualTo(200);
    }

    @Test
    void anotherOwnersCampaignIsNotFound() throws Exception {
        Matrix lib = matrix();
        ScopeFixture.Starter them = fixture.starter();
        Claim claim = scopedClaim(them, lib.gm().campaign());

        HttpResponse<String> answer = passages(claim, List.of(lib.aPassages().getFirst()));

        assertProblem(answer, 404, "request.not_found");
        assertThat(answer.body()).doesNotContain(lib.gm().campaign().toString());
    }

    @Test
    void anIngestionJobsGrantGetsNoPassage() throws Exception {
        Matrix lib = matrix();
        UUID owner = lib.gm().owner();
        UUID jobId = OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions).execute(status ->
                jobs.create(IndexChanges.KIND, job -> index.insertChange(new IndexChangeRow(job.id(), owner, lib.a(),
                        0, 2, null))).id()));
        Claim claim = claim(jobId);

        assertProblem(passages(claim, List.of(lib.aPassages().getFirst())), 403, "job.passages_refused");
    }

    @Test
    void aBadGrantIsRefusedAndOneToAHundredIdsAreAccepted() throws Exception {
        Matrix lib = matrix();
        Claim claim = scopedClaim(lib.gm(), lib.gm().campaign());
        Claim other = scopedClaim(lib.gm(), lib.gm().campaign());
        String good = "{\"ids\":[\"" + lib.aPassages().getFirst() + "\"]}";

        for (String[] headers : new String[][] {{}, {"X-Job-Grant", "A".repeat(43)},
                {"X-Job-Grant", other.grant()}, {"X-Job-Grant", "not a grant"}}) {
            assertProblem(sendAsWorker("POST", claim.passages(), good, headers), 403, "job.grant_refused");
        }
        assertProblem(sendAsWorker("POST", "/internal/v1/jobs/" + claim.jobId() + "/claims/" + UUID.randomUUID()
                + "/passages", good, "X-Job-Grant", claim.grant()), 403, "job.grant_refused");
        assertProblem(sendAsWorker("POST", "/internal/v1/jobs/" + claim.jobId() + "/claims/" + other.claimId()
                + "/passages", good, "X-Job-Grant", other.grant()), 403, "job.grant_refused");

        List<UUID> hundred = IntStream.range(0, 100).mapToObj(i -> lib.aPassages().get(i % 3)).toList();
        List<UUID> hundredOne = IntStream.range(0, 101).mapToObj(i -> lib.aPassages().get(i % 3)).toList();
        assertThat(json(passages(claim, hundred)).path("passages").size()).isEqualTo(3);
        assertProblem(passages(claim, hundredOne), 400, "job.passages_invalid");
        assertProblem(passages(claim, List.of()), 400, "job.passages_invalid");
        for (String body : List.of("{}", "{\"ids\":null}", "{\"ids\":[null]}")) {
            assertProblem(sendAsWorker("POST", claim.passages(), body, "X-Job-Grant", claim.grant()), 400,
                    "job.passages_invalid");
        }
        assertProblem(sendAsWorker("POST", claim.passages(), "{\"ids\":[\"not-an-id\"]}", "X-Job-Grant",
                claim.grant()), 400, "request.invalid");
        // A bad grant comes before the ids are checked (a malformed body or a non-UUID id is request.invalid first).
        assertProblem(sendAsWorker("POST", claim.passages(), "{\"ids\":[]}", "X-Job-Grant", other.grant()), 403,
                "job.grant_refused");
        // An ended claim reads nothing more.
        assertThat(sendAsWorker("POST", "/internal/v1/jobs/" + claim.jobId() + "/claims/" + claim.claimId()
                + "/outcome", "{\"outcome\":\"done\"}", "X-Job-Grant", claim.grant()).statusCode()).isEqualTo(204);
        assertProblem(passages(claim, List.of(lib.aPassages().getFirst())), 403, "job.grant_refused");
    }

    /** A job of the scoped kind for {@code owner}, given {@code campaign}, claimed. */
    private Claim scopedClaim(ScopeFixture.Starter owner, UUID campaign) throws Exception {
        UUID jobId = OwnerContext.callAs(owner.owner(), () -> new TransactionTemplate(transactions).execute(status ->
                jobs.create(ScopedHandler.KIND, job -> handler.campaigns.put(job.id(), campaign)).id()));
        return claim(jobId);
    }

    private Claim claim(UUID jobId) throws Exception {
        HttpResponse<String> claimed = sendAsWorker("POST", "/internal/v1/jobs/" + jobId + "/claims", null);
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(201);
        JsonNode claim = json(claimed);
        return new Claim(jobId, claim.path("claimId").asString(), claim.path("grant").asString());
    }

    private HttpResponse<String> passages(Claim claim, List<UUID> ids) throws Exception {
        String body = ids.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(",", "{\"ids\":[", "]}"));
        return sendAsWorker("POST", claim.passages(), body, "X-Job-Grant", claim.grant());
    }

    private static void assertProblem(HttpResponse<String> answer, int status, String code) {
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(status);
        assertThat(json(answer).path("code").asString()).as(answer.body()).isEqualTo(code);
    }

    /** No line carries a passage's text or a section title. */
    private void assertNoTextLogged() {
        assertThat(logged.list).isNotEmpty().allSatisfy(event -> {
            String line = event.getFormattedMessage() + " " + (event.getKeyValuePairs() == null ? ""
                    : event.getKeyValuePairs().stream().map(pair -> pair.key + "=" + pair.value)
                            .collect(Collectors.joining(" ")));
            assertThat(line).doesNotContain("Texto").doesNotContain("Seção").doesNotContain("Capítulo");
        });
    }
}
