package io.github.gmshield.artifacts.web;

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
import io.github.gmshield.artifacts.data.ArtifactIndexRemovalRow;
import io.github.gmshield.artifacts.data.ArtifactIndexRow;
import io.github.gmshield.artifacts.data.InMemoryArtifactIndexStore;
import io.github.gmshield.artifacts.data.InMemoryArtifactVersionStore;
import io.github.gmshield.artifacts.service.ArtifactIndexReconcile;
import io.github.gmshield.artifacts.service.ArtifactIndexRetrySweep;
import io.github.gmshield.audit.data.AuditStore;
import io.github.gmshield.audit.data.InMemoryAuditStore;
import io.github.gmshield.conversations.data.ConversationTestStores;
import io.github.gmshield.jobs.data.InMemoryJobStorage;
import io.github.gmshield.jobs.data.JobMemoryStores;
import io.github.gmshield.jobs.data.JobRow;
import io.github.gmshield.jobs.data.MutableClock;
import io.github.gmshield.jobs.service.IndexRecipeStated;
import io.github.gmshield.knowledge.data.InMemoryRecordStore;
import io.github.gmshield.library.data.LibraryTestStores;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.materials.data.MaterialTestStores;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 5.1 part B, without a database: an artifact's index jobs. Saving an artifact commits its {@code index.artifact}
 * job with it, and a refused save leaves none (a rollback is {@code ArtifactsIntegrationTest}'s); the worker's claim reads a records run's input and its one records line
 * (the content as a last field), and a completion stores the recipe it reports (a bad one is refused); the backfill
 * gives each artifact not indexed under a stated recipe one job, and none while a job is unended; a failed job is
 * retried with a new one; a campaign's deletion cancels its artifacts' index jobs, removes their rows and creates one
 * {@code index.artifact_removal} job per artifact, by owner and source. Story 5.2 part A: an edit or a restore commits
 * the new version's job and cancels the older versions' unended jobs, so only the newest is indexed, and the retry
 * sweep never retries an older version's failed row. Story 5.2 part B: an artifact's own deletion cancels its index
 * jobs, waits for a claimed one's claim to lapse, then removes its index rows, versions and identity and creates one
 * {@code index.artifact_removal} job; neither the backfill nor a retry gives a deleting artifact a job.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GMSHIELD_MODE=test")
@ActiveProfiles({"test-mode", "unit"})
class ArtifactIndexingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CREDENTIAL = "gmshield-test-mode-frontend-credential-not-a-secret";
    private static final String WORKER_CREDENTIAL = "gmshield-test-mode-worker-credential-not-a-secret";
    private static final Set<String> OPEN = Set.of("offered", "running", "waiting");
    private static final String KAEL = "{\"recordType\":\"item\",\"name\":\"Espada de Kael\",\"fields\":["
            + "{\"name\":\"Dano\",\"value\":\"1d8 cortante\"},{\"name\":\"Peso\",\"value\":\"1,5 kg\"}],"
            + "\"content\":\"Forjada nas montanhas do norte.\"}";

    private static final Instant START = Instant.parse("2026-11-26T12:00:00Z");

    @TestConfiguration
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
    private InMemoryArtifactIndexStore index;
    @Autowired
    private InMemoryArtifactVersionStore versions;
    @Autowired
    private InMemoryRecordStore records;
    @Autowired
    private ArtifactIndexReconcile reconcile;
    @Autowired
    private ArtifactIndexRetrySweep retry;
    @Autowired
    private SystemDeletion deletion;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private ApplicationEventPublisher events;
    @Autowired
    private Vault vault;
    @Autowired
    private MutableClock clock;

    private final HttpClient client = HttpClient.newHttpClient();
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    record Gm(UUID id, String session, UUID campaign, UUID system) {
    }

    @BeforeEach
    void freeTheIngestionLane() {
        endIngestion();
        logged.start();
        root.addAppender(logged);
    }

    @AfterEach
    void detach() {
        root.detachAppender(logged);
    }

    // --- save

    @Test
    void aSaveCommitsItsIndexJobWithItAndARefusedSaveLeavesNone() throws Exception {
        Gm gm = signUp();

        UUID id = created(gm, gm.campaign(), KAEL);

        // Matrix row "Save": one index.artifact job on the ingestion lane, its row naming version 1 with no recipe.
        ArtifactIndexRow row = onlyRow(id);
        assertThat(row.number()).isEqualTo(1);
        assertThat(row.recipe()).isNull();
        assertThat(row.retries()).isZero();
        JobRow job = jobs.rows.get(row.jobId());
        assertThat(job.kind()).isEqualTo("index.artifact");
        assertThat(job.lane()).isEqualTo("ingestion");
        assertThat(job.ownerId()).isEqualTo(gm.id());
        assertThat(OPEN).contains(job.state());

        // A refused save stores nothing, so no job either (the rollback of a save's transaction is
        // ArtifactsIntegrationTest's, on PostgreSQL).
        Set<UUID> jobsBefore = Set.copyOf(jobs.rows.keySet());
        HttpResponse<String> refused = send(gm, "POST", "/api/v1/campaigns/" + gm.campaign() + "/artifacts",
                "{\"recordType\":\"no_such_type\",\"name\":\"Rascunho\",\"fields\":[]}");
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
        assertThat(jobs.rows.values()).filteredOn(one -> !jobsBefore.contains(one.id()))
                .noneMatch(one -> one.ownerId().equals(gm.id()));
        assertThat(rowsOf(id)).hasSize(1);
    }

    // --- a new version (Story 5.2)

    @Test
    void aNewVersionCommitsItsJobAndCancelsTheOlderVersionsUnendedJobs() throws Exception {
        Gm gm = signUp();
        UUID id = created(gm, gm.campaign(), KAEL);
        UUID first = onlyRow(id).jobId();

        edited(gm, id, 1, "Espada de Kael, a Rubra");

        // Matrix row "Edit": the job of version 2 commits with it; version 1's unended job is cancelled.
        assertThat(jobs.rows.get(first).state()).isEqualTo("cancelled");
        ArtifactIndexRow second = rowOf(id, 2);
        assertThat(OPEN).contains(jobs.rows.get(second.jobId()).state());
        assertThat(jobs.rows.get(second.jobId()).kind()).isEqualTo("index.artifact");

        // Matrix row "Index order": version 2's job is claimed (unended) when version 3 is saved.
        offer(second.jobId());
        JsonNode claim = claim(second.jobId());
        HttpResponse<String> restored = send(gm, "POST", "/api/v1/campaigns/" + gm.campaign() + "/artifacts/" + id
                + "/versions/1/restore", "{\"version\":2}");
        assertThat(restored.statusCode()).as(restored.body()).isEqualTo(200);
        assertThat(jobs.rows.get(second.jobId()).state()).isEqualTo("cancelled");
        ArtifactIndexRow third = rowOf(id, 3);
        assertThat(OPEN).contains(jobs.rows.get(third.jobId()).state());
        // Version 2's worker can no longer report: only version 3 is indexed.
        HttpResponse<String> late = outcome(second.jobId(), claim, "{\"outcome\":\"done\","
                + "\"result\":{\"indexRecipe\":\"2\"}}");
        assertThat(late.statusCode()).isNotEqualTo(204);
        assertThat(rowOf(id, 2).recipe()).isNull();
        offer(third.jobId());
        JsonNode thirdClaim = claim(third.jobId());
        JsonNode line = JSON.readTree(input(third.jobId(), thirdClaim, "records").body().strip());
        assertThat(line.path("type").asString()).isEqualTo("item");
        assertThat(line.path("name").asString()).isEqualTo("Espada de Kael");
        assertThat(outcome(third.jobId(), thirdClaim, "{\"outcome\":\"done\",\"result\":{\"indexRecipe\":\"2\"}}")
                .statusCode()).isEqualTo(204);
        assertThat(rowOf(id, 3).recipe()).isEqualTo("2");
        assertThat(logged.list).anyMatch(event -> event.getFormattedMessage().equals("artifacts.index_superseded"));
        for (ILoggingEvent event : logged.list) {
            String text = event.getFormattedMessage() + " " + event.getKeyValuePairs();
            assertThat(text).doesNotContain("Kael").doesNotContain("Rubra").doesNotContain("Forjada");
        }
    }

    @Test
    void aNewRecipeIndexesOnlyTheNewestVersionAgainAndLeavesTheOlderVersionsRowAndJobAlone() throws Exception {
        Gm gm = signUp();
        UUID id = created(gm, gm.campaign(), KAEL);
        UUID first = onlyRow(id).jobId();
        offer(first);
        JsonNode firstClaim = claim(first);
        assertThat(outcome(first, firstClaim, "{\"outcome\":\"done\",\"result\":{\"indexRecipe\":\"2\"}}")
                .statusCode()).isEqualTo(204);
        edited(gm, id, 1, "Espada de Kael, a Rubra");
        UUID second = rowOf(id, 2).jobId();
        offer(second);
        JsonNode secondClaim = claim(second);
        assertThat(outcome(second, secondClaim, "{\"outcome\":\"done\",\"result\":{\"indexRecipe\":\"2\"}}")
                .statusCode()).isEqualTo(204);

        // Under "2" nothing is stale; under "3" only version 2 gets a new job.
        reconcile.reconcile("2");
        assertThat(rowOf(id, 2).jobId()).isEqualTo(second);
        reconcile.reconcile("3");

        ArtifactIndexRow again = rowOf(id, 2);
        assertThat(again.jobId()).isNotEqualTo(second);
        assertThat(again.recipe()).isNull();
        assertThat(OPEN).contains(jobs.rows.get(again.jobId()).state());
        ArtifactIndexRow kept = rowOf(id, 1);
        assertThat(kept.jobId()).isEqualTo(first);
        assertThat(kept.recipe()).isEqualTo("2");
        assertThat(jobs.rows.get(first).state()).isEqualTo("done");
    }

    @Test
    void aFailedRowOfAnOlderVersionIsNotRetried() throws Exception {
        Gm gm = signUp();
        UUID id = created(gm, gm.campaign(), KAEL);
        UUID first = onlyRow(id).jobId();
        failed(first);

        edited(gm, id, 1, "Espada de Kael, a Rubra");
        retry.sweep();

        // Matrix row "Index order": the failed version 1 row stays as it was; version 2 has its own job.
        ArtifactIndexRow kept = rowOf(id, 1);
        assertThat(kept.jobId()).isEqualTo(first);
        assertThat(kept.retries()).isZero();
        assertThat(jobs.rows.get(first).state()).isEqualTo("failed");
        assertThat(OPEN).contains(jobs.rows.get(rowOf(id, 2).jobId()).state());
    }

    // --- index

    @Test
    void theClaimReadsOneRecordsLineWithTheContentLastAndTheCompletionStoresTheRecipe() throws Exception {
        Gm gm = signUp();
        UUID id = created(gm, gm.campaign(), KAEL);
        UUID jobId = onlyRow(id).jobId();

        JsonNode claim = claim(jobId);

        // Matrix row "Index": the input of a records run, its source the artifact.
        JsonNode input = claim.path("input");
        assertThat(fieldNames(input)).containsExactlyInAnyOrder("source", "owner", "kind", "language", "tokenKey",
                "records");
        assertThat(input.path("source").asString()).isEqualTo(id.toString());
        assertThat(input.path("owner").asString()).isEqualTo(gm.id().toString());
        assertThat(input.path("kind").asString()).isEqualTo("artifact");
        assertThat(input.path("language").isNull()).isTrue();
        assertThat(input.path("records").asString()).isEqualTo("records");
        assertThat(input.path("tokenKey").asString()).isNotBlank();
        HttpResponse<String> line = input(jobId, claim, "records");
        assertThat(line.statusCode()).as(line.body()).isEqualTo(200);
        assertThat(line.body()).endsWith("\n").doesNotContain("\n{");
        JsonNode record = JSON.readTree(line.body().strip());
        assertThat(fieldNames(record)).containsExactly("id", "page", "type", "name", "fields");
        assertThat(record.path("id").asString()).isEqualTo(id.toString());
        assertThat(record.path("page").asInt()).isEqualTo(1);
        assertThat(record.path("type").asString()).isEqualTo("item");
        assertThat(record.path("name").asString()).isEqualTo("Espada de Kael");
        assertThat(record.path("fields").toString()).isEqualTo("[{\"name\":\"Dano\",\"value\":\"1d8 cortante\"},"
                + "{\"name\":\"Peso\",\"value\":\"1,5 kg\"},{\"name\":\"content\",\"value\":"
                + "\"Forjada nas montanhas do norte.\"}]");
        assertThat(input(jobId, claim, "passages").statusCode()).isEqualTo(404);

        // A recipe that is not one is refused, and the row keeps none.
        HttpResponse<String> bad = outcome(jobId, claim, "{\"outcome\":\"done\",\"result\":{\"indexRecipe\":\"a b\"}}");
        assertThat(json(bad).path("code").asString()).as(bad.body()).isEqualTo("job.result_invalid");
        assertThat(logged.list).anyMatch(event -> event.getFormattedMessage().equals("job.result_invalid")
                && event.getKeyValuePairs().stream().anyMatch(pair -> pair.key.equals("reason")
                && "index.recipe_invalid".equals(pair.value)));
        assertThat(onlyRow(id).recipe()).isNull();
        HttpResponse<String> done = outcome(jobId, claim, "{\"outcome\":\"done\",\"result\":{\"indexRecipe\":\"2\","
                + "\"points\":1}}");
        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        assertThat(onlyRow(id).recipe()).isEqualTo("2");
        assertThat(jobs.rows.get(jobId).state()).isEqualTo("done");
        // No line names the artifact's text.
        for (ILoggingEvent event : logged.list) {
            String text = event.getFormattedMessage() + " " + event.getKeyValuePairs();
            assertThat(text).doesNotContain("Kael").doesNotContain("Forjada").doesNotContain("cortante");
        }
    }

    @Test
    void anArtifactWithoutContentHasNoContentField() throws Exception {
        Gm gm = signUp();
        UUID id = created(gm, gm.campaign(), "{\"recordType\":\"note\",\"name\":\"Lembrete\",\"fields\":[]}");
        UUID jobId = onlyRow(id).jobId();

        JsonNode claim = claim(jobId);

        JsonNode record = JSON.readTree(input(jobId, claim, "records").body().strip());
        assertThat(record.path("fields").size()).isZero();
    }

    // --- backfill

    @Test
    void theBackfillGivesEachArtifactNotIndexedUnderTheRecipeOneJobAndNoneWhileAJobIsUnended() throws Exception {
        Gm gm = signUp();
        UUID first = created(gm, gm.campaign(), KAEL);
        UUID second = created(gm, gm.campaign(), "{\"recordType\":\"note\",\"name\":\"Lembrete\",\"fields\":[]}");
        // As saved under part A: no row and no job.
        OwnerContext.runAs(gm.id(), () -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                index.deleteOf(List.of(first, second))));
        endIngestion();

        // Matrix row "Backfill": the worker states its recipe; one job per artifact.
        events.publishEvent(new IndexRecipeStated("2"));
        reconcile.settled().get(10, TimeUnit.SECONDS);

        UUID jobId = onlyRow(first).jobId();
        UUID secondJob = onlyRow(second).jobId();
        assertThat(jobs.rows.get(jobId).kind()).isEqualTo("index.artifact");
        assertThat(OPEN).contains(jobs.rows.get(jobId).state());
        // A second statement creates none while a job is unended.
        reconcile.reconcile("2");
        assertThat(onlyRow(first).jobId()).isEqualTo(jobId);
        assertThat(onlyRow(second).jobId()).isEqualTo(secondJob);

        // Once the first is indexed under 2, only a new recipe indexes it again; the second's job is still unended.
        offer(jobId);
        JsonNode claim = claim(jobId);
        assertThat(outcome(jobId, claim, "{\"outcome\":\"done\",\"result\":{\"indexRecipe\":\"2\"}}").statusCode())
                .isEqualTo(204);
        reconcile.reconcile("2");
        assertThat(onlyRow(first).jobId()).isEqualTo(jobId);
        assertThat(onlyRow(first).recipe()).isEqualTo("2");
        assertThat(reconcile.reconcile("3")).isGreaterThanOrEqualTo(1);
        assertThat(onlyRow(first)).satisfies(row -> {
            assertThat(row.recipe()).isNull();
            assertThat(row.jobId()).isNotEqualTo(jobId);
        });
        assertThat(onlyRow(second).jobId()).isEqualTo(secondJob);
    }

    // --- retry

    @Test
    void aFailedJobIsRetriedWithANewJobAndTheRowMovesToIt() throws Exception {
        Gm gm = signUp();
        UUID id = created(gm, gm.campaign(), KAEL);
        UUID jobId = onlyRow(id).jobId();
        JsonNode claim = claim(jobId);
        assertThat(outcome(jobId, claim, "{\"outcome\":\"failed\",\"code\":\"index.store_unavailable\","
                + "\"retry\":false}").statusCode()).isEqualTo(204);
        assertThat(jobs.rows.get(jobId).state()).isEqualTo("failed");

        assertThat(retry.sweep()).isGreaterThanOrEqualTo(1);

        ArtifactIndexRow moved = onlyRow(id);
        assertThat(moved.jobId()).isNotEqualTo(jobId);
        assertThat(moved.retries()).isEqualTo(1);
        assertThat(jobs.rows.get(moved.jobId()).kind()).isEqualTo("index.artifact");
        assertThat(jobs.rows.get(jobId).state()).isEqualTo("failed");
    }

    @Test
    void aFailedRowAtTheMostRetriesIsLeftWhileAnotherFailedRowOfTheOwnerIsRetried() throws Exception {
        Gm gm = signUp();
        UUID capped = created(gm, gm.campaign(), KAEL);
        UUID other = created(gm, gm.campaign(), "{\"recordType\":\"note\",\"name\":\"Lembrete\",\"fields\":[]}");
        UUID cappedJob = onlyRow(capped).jobId();
        UUID otherJob = onlyRow(other).jobId();
        failed(cappedJob);
        failed(otherJob);
        ArtifactIndexRow row = onlyRow(capped);
        index.rows.put(cappedJob, new ArtifactIndexRow(cappedJob, gm.id(), capped, 1, null,
                ArtifactIndexRow.MAX_RETRIES, row.createdAt()));

        retry.sweep();

        assertThat(onlyRow(capped)).satisfies(kept -> {
            assertThat(kept.jobId()).isEqualTo(cappedJob);
            assertThat(kept.retries()).isEqualTo(ArtifactIndexRow.MAX_RETRIES);
        });
        assertThat(onlyRow(other)).satisfies(moved -> {
            assertThat(moved.jobId()).isNotEqualTo(otherJob);
            assertThat(moved.retries()).isEqualTo(1);
        });
    }

    @Test
    void aFailedRemovalGetsANewJobWithTheSameArtifactAndOwnerAndItsRowMovesOnce() throws Exception {
        Gm gm = signUp();
        UUID other = campaign(gm);
        UUID going = created(gm, other, KAEL);
        assertThat(send(gm, "DELETE", "/api/v1/campaigns/" + other, null).statusCode()).isEqualTo(202);
        deletion.sweep();
        ArtifactIndexRemovalRow removal = removalOf(going);
        failed(removal.jobId());

        retry.sweep();

        ArtifactIndexRemovalRow moved = removalOf(going);
        assertThat(moved.jobId()).isNotEqualTo(removal.jobId());
        assertThat(moved.ownerId()).isEqualTo(gm.id());
        assertThat(moved.artifactId()).isEqualTo(going);
        assertThat(jobs.rows.get(moved.jobId()).kind()).isEqualTo("index.artifact_removal");
        assertThat(OPEN).contains(jobs.rows.get(moved.jobId()).state());
        assertThat(jobs.rows.get(removal.jobId()).state()).isEqualTo("failed");
        // The failed job kept as history has no row: a second sweep retries nothing of it.
        Set<UUID> before = Set.copyOf(jobs.rows.keySet());
        retry.sweep();
        assertThat(removalOf(going).jobId()).isEqualTo(moved.jobId());
        assertThat(jobs.rows.values()).filteredOn(one -> !before.contains(one.id()))
                .noneMatch(one -> one.ownerId().equals(gm.id()));
    }

    // --- deletion

    @Test
    void aCampaignsDeletionWaitsForAClaimedIndexJobsClaimBeforeItRemovesAnything() throws Exception {
        Gm gm = signUp();
        UUID other = campaign(gm);
        UUID going = created(gm, other, KAEL);
        UUID job = onlyRow(going).jobId();
        offer(job);
        claim(job);
        Instant lapses = jobs.rows.get(job).claimLapsesAt();
        assertThat(lapses).isAfter(clock.instant());

        assertThat(send(gm, "DELETE", "/api/v1/campaigns/" + other, null).statusCode()).isEqualTo(202);
        assertThat(jobs.rows.get(job).state()).isEqualTo("cancelled");
        deletion.sweep();

        // The worker may still write its point until its claim lapses: nothing goes, and no removal starts.
        assertThat(rowsOf(going)).hasSize(1);
        assertThat(versions.rows).containsKey(going + "/1");
        assertThat(records.artifacts).containsKey(going);
        assertThat(index.removalRows.values()).noneMatch(row -> row.artifactId().equals(going));

        clock.set(lapses.plus(Duration.ofSeconds(1)));
        deletion.sweep();

        assertThat(rowsOf(going)).isEmpty();
        assertThat(versions.rows).doesNotContainKey(going + "/1");
        assertThat(records.artifacts).doesNotContainKey(going);
        assertThat(removalOf(going).ownerId()).isEqualTo(gm.id());
    }

    @Test
    void neitherTheBackfillNorARetryGivesAJobToAnArtifactOfACampaignBeingDeleted() throws Exception {
        Gm gm = signUp();
        UUID other = campaign(gm);
        UUID going = created(gm, other, KAEL);
        UUID job = onlyRow(going).jobId();
        failed(job);
        assertThat(send(gm, "DELETE", "/api/v1/campaigns/" + other, null).statusCode()).isEqualTo(202);
        Set<UUID> before = Set.copyOf(jobs.rows.keySet());

        reconcile.reconcile("guard-" + UUID.randomUUID().toString().substring(0, 8));
        retry.sweep();

        assertThat(onlyRow(going).jobId()).isEqualTo(job);
        assertThat(jobs.rows.values()).filteredOn(one -> !before.contains(one.id()))
                .noneMatch(one -> one.ownerId().equals(gm.id()) && one.kind().equals("index.artifact"));
    }

    @Test
    void aCampaignsDeletionCancelsItsArtifactsJobsRemovesTheirRowsAndRemovesTheirPointsByOwnerAndSource()
            throws Exception {
        Gm gm = signUp();
        UUID other = UUID.fromString(json(send(gm, "POST", "/api/v1/campaigns", "{\"name\":\"Outra mesa\","
                + "\"systemId\":\"" + gm.system() + "\"}")).path("id").asString());
        UUID going = created(gm, other, KAEL);
        UUID alsoGoing = created(gm, other, "{\"recordType\":\"note\",\"name\":\"Lembrete\",\"fields\":[]}");
        UUID staying = created(gm, gm.campaign(), KAEL);
        UUID goingJob = onlyRow(going).jobId();
        UUID alsoGoingJob = onlyRow(alsoGoing).jobId();

        HttpResponse<String> deleted = send(gm, "DELETE", "/api/v1/campaigns/" + other, null);
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(202);
        // Matrix row "Deletion": the jobs are cancelled at once.
        assertThat(jobs.rows.get(goingJob).state()).isEqualTo("cancelled");
        assertThat(jobs.rows.get(alsoGoingJob).state()).isEqualTo("cancelled");
        assertThat(OPEN).contains(jobs.rows.get(onlyRow(staying).jobId()).state());
        deletion.sweep();
        deletion.sweep();

        assertThat(rowsOf(going)).isEmpty();
        assertThat(rowsOf(alsoGoing)).isEmpty();
        assertThat(rowsOf(staying)).hasSize(1);
        assertThat(versions.rows).doesNotContainKey(going + "/1").containsKey(staying + "/1");
        assertThat(records.artifacts).doesNotContainKey(going).containsKey(staying);
        // One removal per artifact, by owner and source, whatever the passes.
        List<ArtifactIndexRemovalRow> removals = index.removalRows.values().stream()
                .filter(row -> row.ownerId().equals(gm.id())).toList();
        assertThat(removals).extracting(ArtifactIndexRemovalRow::artifactId)
                .containsExactlyInAnyOrder(going, alsoGoing);
        ArtifactIndexRemovalRow removal = removals.stream().filter(row -> row.artifactId().equals(going)).findFirst()
                .orElseThrow();
        assertThat(jobs.rows.get(removal.jobId()).kind()).isEqualTo("index.artifact_removal");
        offer(removal.jobId());
        JsonNode claim = claim(removal.jobId());
        assertThat(fieldNames(claim.path("input"))).containsExactlyInAnyOrder("source", "owner");
        assertThat(claim.path("input").path("source").asString()).isEqualTo(going.toString());
        assertThat(claim.path("input").path("owner").asString()).isEqualTo(gm.id().toString());
        assertThat(outcome(removal.jobId(), claim, "{\"outcome\":\"done\"}").statusCode()).isEqualTo(204);
    }

    // --- an artifact's own deletion (Story 5.2 part B)

    @Test
    void anArtifactsDeletionWaitsForItsClaimedIndexJobThenRemovesItsRowsWithOneRemovalJob() throws Exception {
        Gm gm = signUp();
        UUID going = created(gm, gm.campaign(), KAEL);
        UUID staying = created(gm, gm.campaign(), "{\"recordType\":\"note\",\"name\":\"Lembrete\",\"fields\":[]}");
        edited(gm, going, 1, "Espada de Kael II");
        UUID job = rowOf(going, 2).jobId();
        offer(job);
        claim(job);
        Instant lapses = jobs.rows.get(job).claimLapsesAt();
        assertThat(lapses).isAfter(clock.instant());

        HttpResponse<String> deleted = send(gm, "DELETE", "/api/v1/campaigns/" + gm.campaign() + "/artifacts/" + going,
                null);
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(202);
        assertThat(jobs.rows.get(job).state()).isEqualTo("cancelled");
        deletion.sweep();

        // Matrix row "Claimed index job": the worker may still write its point until its claim lapses.
        assertThat(rowsOf(going)).hasSize(2);
        assertThat(versions.rows).containsKeys(going + "/1", going + "/2");
        assertThat(records.artifacts).containsKey(going);
        assertThat(index.removalRows.values()).noneMatch(row -> row.artifactId().equals(going));

        clock.set(lapses.plus(Duration.ofSeconds(1)));
        deletion.sweep();
        deletion.sweep();

        assertThat(rowsOf(going)).isEmpty();
        assertThat(versions.rows).doesNotContainKeys(going + "/1", going + "/2").containsKey(staying + "/1");
        assertThat(records.artifacts).doesNotContainKey(going).containsKey(staying);
        assertThat(records.deletingArtifacts).doesNotContain(going);
        assertThat(rowsOf(staying)).hasSize(1);
        // One removal of its points, by owner and source, whatever the passes.
        ArtifactIndexRemovalRow removal = removalOf(going);
        assertThat(removal.ownerId()).isEqualTo(gm.id());
        assertThat(jobs.rows.get(removal.jobId()).kind()).isEqualTo("index.artifact_removal");
        assertThat(OPEN).contains(jobs.rows.get(removal.jobId()).state());
        assertThat(index.removalRows.values()).noneMatch(row -> row.artifactId().equals(staying));
    }

    @Test
    void anArtifactsDeletionCancelsItsUnclaimedJobAndRemovesItAtTheFirstPass() throws Exception {
        Gm gm = signUp();
        UUID going = created(gm, gm.campaign(), KAEL);
        UUID job = onlyRow(going).jobId();

        assertThat(send(gm, "DELETE", "/api/v1/campaigns/" + gm.campaign() + "/artifacts/" + going, null)
                .statusCode()).isEqualTo(202);
        assertThat(jobs.rows.get(job).state()).isEqualTo("cancelled");
        // The backend restarted before its pass: the sweep finishes the deletion.
        deletion.sweep();

        assertThat(rowsOf(going)).isEmpty();
        assertThat(versions.rows).doesNotContainKey(going + "/1");
        assertThat(records.artifacts).doesNotContainKey(going);
        assertThat(removalOf(going).ownerId()).isEqualTo(gm.id());
    }

    @Test
    void neitherTheBackfillNorARetryGivesAJobToADeletingArtifact() throws Exception {
        Gm gm = signUp();
        UUID going = created(gm, gm.campaign(), KAEL);
        UUID job = onlyRow(going).jobId();
        failed(job);
        UUID never = created(gm, gm.campaign(), "{\"recordType\":\"note\",\"name\":\"Nunca\",\"fields\":[]}");
        index.rows.remove(onlyRow(never).jobId());
        assertThat(send(gm, "DELETE", "/api/v1/campaigns/" + gm.campaign() + "/artifacts/" + going, null)
                .statusCode()).isEqualTo(202);
        assertThat(send(gm, "DELETE", "/api/v1/campaigns/" + gm.campaign() + "/artifacts/" + never, null)
                .statusCode()).isEqualTo(202);
        Set<UUID> before = Set.copyOf(jobs.rows.keySet());

        reconcile.reconcile("guard-" + UUID.randomUUID().toString().substring(0, 8));
        retry.sweep();

        assertThat(onlyRow(going).jobId()).isEqualTo(job);
        assertThat(rowsOf(never)).isEmpty();
        assertThat(jobs.rows.values()).filteredOn(one -> !before.contains(one.id()))
                .noneMatch(one -> one.ownerId().equals(gm.id()) && one.kind().equals("index.artifact"));
    }

    // --- helpers

    /** A second campaign of the GM's system; its id. */
    private UUID campaign(Gm gm) throws Exception {
        HttpResponse<String> created = send(gm, "POST", "/api/v1/campaigns", "{\"name\":\"Outra mesa\","
                + "\"systemId\":\"" + gm.system() + "\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(json(created).path("id").asString());
    }

    /** The job offered, claimed and failed for good by the worker. */
    private void failed(UUID jobId) throws Exception {
        offer(jobId);
        JsonNode claim = claim(jobId);
        assertThat(outcome(jobId, claim, "{\"outcome\":\"failed\",\"code\":\"index.store_unavailable\","
                + "\"retry\":false}").statusCode()).isEqualTo(204);
        assertThat(jobs.rows.get(jobId).state()).isEqualTo("failed");
    }

    /** The one removal row of the artifact. */
    private ArtifactIndexRemovalRow removalOf(UUID artifact) {
        List<ArtifactIndexRemovalRow> rows = index.removalRows.values().stream()
                .filter(row -> row.artifactId().equals(artifact)).toList();
        assertThat(rows).as("the removal rows of %s", artifact).hasSize(1);
        return rows.getFirst();
    }

    private UUID created(Gm gm, UUID campaign, String body) throws Exception {
        HttpResponse<String> created = send(gm, "POST", "/api/v1/campaigns/" + campaign + "/artifacts", body);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(json(created).path("id").asString());
    }

    /** The GM's edit of the artifact from version {@code version}, as an item with no field, under a new name. */
    private void edited(Gm gm, UUID id, int version, String name) throws Exception {
        HttpResponse<String> edited = send(gm, "PATCH", "/api/v1/campaigns/" + gm.campaign() + "/artifacts/" + id,
                "{\"version\":" + version + ",\"recordType\":\"item\",\"name\":\"" + name + "\",\"fields\":[]}");
        assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
    }

    /** The one index row of the artifact's version {@code number}. */
    private ArtifactIndexRow rowOf(UUID artifact, int number) {
        List<ArtifactIndexRow> rows = rowsOf(artifact).stream().filter(row -> row.number() == number).toList();
        assertThat(rows).as("the index rows of %s version %s", artifact, number).hasSize(1);
        return rows.getFirst();
    }

    private List<ArtifactIndexRow> rowsOf(UUID artifact) {
        return index.rows.values().stream().filter(row -> row.recordId().equals(artifact)).toList();
    }

    private ArtifactIndexRow onlyRow(UUID artifact) {
        List<ArtifactIndexRow> rows = rowsOf(artifact);
        assertThat(rows).as("the index rows of %s", artifact).hasSize(1);
        return rows.getFirst();
    }

    /** Every open ingestion job of an earlier test or step cancelled, so the next one created is offered at once. */
    private void endIngestion() {
        jobs.rows.replaceAll((id, row) -> row.lane().equals("ingestion") && OPEN.contains(row.state())
                ? row.released("cancelled", null, row.updatedAt()) : row);
    }

    /** The job offered, as the ingestion lane would once the jobs ahead of it ended. */
    private void offer(UUID jobId) {
        jobs.rows.computeIfPresent(jobId, (id, row) -> row.state().equals("waiting") ? row.offered(row.updatedAt())
                : row);
    }

    private JsonNode claim(UUID jobId) throws Exception {
        HttpResponse<String> claimed = worker("POST", "/internal/v1/jobs/" + jobId + "/claims", null);
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(201);
        return json(claimed);
    }

    private HttpResponse<String> input(UUID jobId, JsonNode claim, String name) throws Exception {
        return worker("GET", "/internal/v1/jobs/" + jobId + "/claims/" + claim.path("claimId").asString() + "/inputs/"
                + name, null, "X-Job-Grant", claim.path("grant").asString());
    }

    private HttpResponse<String> outcome(UUID jobId, JsonNode claim, String body) throws Exception {
        return worker("POST", "/internal/v1/jobs/" + jobId + "/claims/" + claim.path("claimId").asString()
                + "/outcome", body.getBytes(StandardCharsets.UTF_8), "Content-Type", "application/json", "X-Job-Grant",
                claim.path("grant").asString());
    }

    private HttpResponse<String> worker(String method, String path, byte[] body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + WORKER_CREDENTIAL)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> send(Gm gm, String method, String path, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Session-Token", gm.session());
        if (json == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Gm signUp() throws Exception {
        String sub = "idx-" + UUID.randomUUID();
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
