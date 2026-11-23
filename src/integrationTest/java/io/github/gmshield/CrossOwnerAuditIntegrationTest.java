package io.github.gmshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.vault.service.Vault;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * The cross-owner audit of Story 3.13 part C on a real PostgreSQL and Redis (test mode). The unit audit
 * ({@link CrossOwnerAuditFlowTest}) runs on the in-memory twins, which filter by owner themselves; here every row is
 * PostgreSQL's, owned through Hibernate's {@code @TenantId} and the JDBC stores' {@code owner_id = :owner}. The same
 * routes ({@link CrossOwnerAuditFlowTest#ROUTES}), the same body-field probes and the same worlds, built over HTTP
 * through the public API and the worker's internal API, give the same assertions: B's request with A's ids answers
 * {@code request.not_found} (404) as a random id does, each field answers what a random id gets. "Nothing changed" is
 * read with SQL and Redis ({@link PostgresCrossOwnerAuditStores}): every row of A and B in every table with an
 * {@code owner_id} column, dumped before and after B's sweep, and the work streams' messages.
 */
// The sweeps are off, as in the other integration classes: a second context's sweep must not touch their rows.
// Its own context holds few connections and is closed after the class: the other classes' cached contexts already
// hold most of a default PostgreSQL's 100 connections, and one more full pool refuses the last ones ("too many
// clients already"). It cannot share a cached context instead: each RANDOM_PORT integration class
// (LibraryIntegrationTest, JobIntegrationTest, UploadIntegrationTest) adds a nested @TestConfiguration of its own, and
// LibraryIntegrationTest sets no gmshield.files.dir, which the audit's uploads need.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
            "gmshield.materials.upload-sweep.enabled=false", "gmshield.materials.version-removal-sweep.enabled=false",
            "gmshield.library.deletion-sweep.enabled=false", "gmshield.materials.index-retry-sweep.enabled=false",
            "gmshield.files.dir=${java.io.tmpdir}/gmshield-cross-owner-it",
            "gmshield.conversations.keep-alive=400ms", "gmshield.conversations.poll=100ms",
            "spring.datasource.hikari.maximum-pool-size=4"})
@ActiveProfiles("test-mode")
@DirtiesContext
class CrossOwnerAuditIntegrationTest {

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private Vault vault;

    private PostgresCrossOwnerAuditStores stores;
    private CrossOwnerAuditFlowTest audit;

    @BeforeEach
    void start() {
        stores = new PostgresCrossOwnerAuditStores(jdbc, redis, vault);
        audit = CrossOwnerAuditFlowTest.against(port, stores);
        audit.start();
    }

    /** The owner-row dump and its comparison see a row changed behind the application's back, naming it. */
    @Test
    void theOwnerRowDumpNamesOneOfAsRowsChangedThroughJdbc() throws Exception {
        CrossOwnerAuditFlowTest.Gm a = audit.signUp();
        UUID system = jdbc.sql("select system_id from campaign where id = ?").param(a.campaign()).query(UUID.class)
                .single();
        Map<String, Map<Object, Object>> before = stores.tables(List.of(a.id()));
        assertThat(CrossOwnerAuditFlowTest.differences(before, stores.tables(List.of(a.id())))).isEmpty();

        jdbc.sql("update system set version = version + 1 where id = ?").param(system).update();

        assertThat(CrossOwnerAuditFlowTest.differences(before, stores.tables(List.of(a.id()))))
                .containsExactly("system " + system + " changed");
    }

    @Test
    void everyRouteThatNamesAnotherAccountsItemAnswersNotFoundAsARandomIdDoesAndChangesNoRowOnPostgres()
            throws Exception {
        audit.everyRouteThatNamesAnotherAccountsItemAnswersNotFoundAsARandomIdDoesAndChangesNothing();
    }

    @Test
    void everyFieldThatNamesAnotherAccountsItemAnswersExactlyAsARandomIdDoesOnPostgres() throws Exception {
        audit.everyFieldThatNamesAnotherAccountsItemAnswersExactlyAsARandomIdDoes();
    }
}
