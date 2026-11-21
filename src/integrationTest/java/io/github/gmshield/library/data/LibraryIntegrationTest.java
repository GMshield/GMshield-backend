package io.github.gmshield.library.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.accounts.data.SignInAttemptStore;
import io.github.gmshield.conversations.data.ConversationRow;
import io.github.gmshield.conversations.data.ConversationStore;
import io.github.gmshield.library.service.CampaignFingerprints;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.data.MaterialRow;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.MaterialSystemRow;
import io.github.gmshield.materials.data.VersionRow;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 1.11 against a real PostgreSQL and Redis (test mode: postgres:5432, redis:6379): the starter library of
 * a first sign-in and its rollback with the account, the vault's data key that survives it, the owner of every
 * library query, and the cross-owner tests of a system and a campaign through HTTP. Story 3.1: systems by name and
 * edition, stored only encrypted, edited by version (the store's conflicts on PostgreSQL's key and version), and
 * another owner's system on every new route and on {@code ?system=}. Story 3.2: campaigns created, renamed by version,
 * archived, restored and chosen, stored only encrypted with their name fingerprint (the key and the version as the
 * store's conflicts), the fill of a fingerprint from before V23, and another owner's campaign on every new route.
 *
 * <p>The raw rows are read here with SQL to prove what is stored; the application itself never reads a library
 * table with native SQL.
 */
// The job sweep is off: JobIntegrationTest drives it itself, and a second context's sweep must not touch its jobs;
// likewise the upload sweep (UploadIntegrationTest drives it).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
            "gmshield.materials.upload-sweep.enabled=false", "gmshield.materials.version-removal-sweep.enabled=false",
            "gmshield.library.deletion-sweep.enabled=false",
            "gmshield.materials.index-retry-sweep.enabled=false"})
@ActiveProfiles("test-mode")
class LibraryIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Test mode's fixed service credential (application-test-mode.yaml). */
    private static final String CREDENTIAL = "gmshield-test-mode-frontend-credential-not-a-secret";

    /** The real store, which can be told to fail its next campaign insert, as a database failure would. */
    @TestConfiguration
    static class FailingStore {
        @Bean
        @Primary
        FailableLibraryStore failableLibraryStore(JpaLibraryStore real) {
            return new FailableLibraryStore(real);
        }
    }

    static class FailableLibraryStore implements LibraryStore {
        final AtomicBoolean failNextCampaign = new AtomicBoolean();
        private final LibraryStore real;

        FailableLibraryStore(LibraryStore real) {
            this.real = real;
        }

        @Override
        public void insertSystem(SystemRow row) {
            real.insertSystem(row);
        }

        @Override
        public void insertCampaign(CampaignRow row) {
            if (failNextCampaign.getAndSet(false)) {
                throw new IllegalStateException("campaign insert failed");
            }
            real.insertCampaign(row);
        }

        @Override
        public Optional<SystemRow> updateSystem(SystemChange change) {
            return real.updateSystem(change);
        }

        @Override
        public boolean systemNameTaken(byte[] nameFingerprint, UUID except) {
            return real.systemNameTaken(nameFingerprint, except);
        }

        @Override
        public List<SystemRow> systems(UUID before, int limit) {
            return real.systems(before, limit);
        }

        @Override
        public List<CampaignRow> campaigns(UUID systemId, boolean archived, UUID before, int limit) {
            return real.campaigns(systemId, archived, before, limit);
        }

        @Override
        public List<UUID> campaignIdsOf(UUID systemId) {
            return real.campaignIdsOf(systemId);
        }

        @Override
        public Optional<CampaignRow> updateCampaign(CampaignChange change) {
            return real.updateCampaign(change);
        }

        @Override
        public boolean chooseCampaign(UUID id, Instant at) {
            return real.chooseCampaign(id, at);
        }

        @Override
        public boolean campaignNameTaken(UUID systemId, byte[] nameFingerprint, UUID except) {
            return real.campaignNameTaken(systemId, nameFingerprint, except);
        }

        @Override
        public List<CampaignRow> campaignsWithoutFingerprint(UUID systemId) {
            return real.campaignsWithoutFingerprint(systemId);
        }

        @Override
        public boolean fillCampaignFingerprint(UUID id, byte[] nameFingerprint) {
            return real.fillCampaignFingerprint(id, nameFingerprint);
        }

        @Override
        public Optional<SystemRow> findSystem(UUID id) {
            return real.findSystem(id);
        }

        @Override
        public Optional<CampaignRow> findCampaign(UUID id) {
            return real.findCampaign(id);
        }

        @Override
        public List<SystemRow> findSystems(java.util.Collection<UUID> ids) {
            return real.findSystems(ids);
        }

        @Override
        public List<CampaignRow> findCampaigns(java.util.Collection<UUID> ids) {
            return real.findCampaigns(ids);
        }

        @Override
        public Optional<CampaignRow> currentCampaign() {
            return real.currentCampaign();
        }

        @Override
        public boolean markDeleting(UUID id, Instant at) {
            return real.markDeleting(id, at);
        }

        @Override
        public boolean deleting(UUID id) {
            return real.deleting(id);
        }

        @Override
        public List<UUID> campaignIdsOfDeleting(UUID systemId) {
            return real.campaignIdsOfDeleting(systemId);
        }

        @Override
        public boolean deleteDeleting(UUID id) {
            return real.deleteDeleting(id);
        }

        @Override
        public boolean markCampaignDeleting(UUID id, Instant at) {
            return real.markCampaignDeleting(id, at);
        }

        @Override
        public boolean campaignDeleting(UUID id) {
            return real.campaignDeleting(id);
        }

        @Override
        public boolean deleteDeletingCampaign(UUID id) {
            return real.deleteDeletingCampaign(id);
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private FailableLibraryStore store;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SignInAttemptStore attempts;

    @Autowired
    private Vault vault;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private EntityManagerFactory entityManagers;

    @Autowired
    private MaterialStore materials;

    @Autowired
    private CampaignFingerprints fingerprints;

    @Autowired
    private ConversationStore conversations;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void armNothing() {
        store.failNextCampaign.set(false);
    }

    /** A signed-up account: its id and session, and its starter system and campaign. */
    record Gm(UUID id, String session, UUID systemId, UUID campaignId) {
    }

    @Test
    void anotherOwnersSystemIsNotFoundLikeAnUnknownOne() throws Exception {
        Gm a = signUp("en");
        Gm b = signUp("pt-BR");

        assertThat(ok(get("/api/v1/systems/" + a.systemId(), a.session())).path("name").asString()).isEqualTo("Untitled system");
        JsonNode unknown = notFound(get("/api/v1/systems/" + Ids.newId(), b.session()));
        HttpResponse<String> crossOwner = get("/api/v1/systems/" + a.systemId(), b.session());
        assertThat(withoutTraceId(notFound(crossOwner))).isEqualTo(withoutTraceId(unknown));
        assertThat(crossOwner.body()).doesNotContain(a.systemId().toString()).doesNotContain("Untitled system");
        assertThat(withoutTraceId(notFound(get("/api/v1/systems/not-a-uuid", b.session())))).isEqualTo(withoutTraceId(unknown));
        // B's own system is B's.
        assertThat(ok(get("/api/v1/systems/" + b.systemId(), b.session())).path("name").asString()).isEqualTo("Sistema sem nome");
        assertThat(notFound(get("/api/v1/systems/" + b.systemId(), a.session())).path("code").asString())
                .isEqualTo("request.not_found");
    }

    @Test
    void anotherOwnersCampaignIsNotFoundLikeAnUnknownOne() throws Exception {
        Gm a = signUp("en");
        Gm b = signUp(null);

        JsonNode own = ok(get("/api/v1/campaigns/" + a.campaignId(), a.session()));
        assertThat(own.path("name").asString()).isEqualTo("First campaign");
        assertThat(own.path("system").path("id").asString()).isEqualTo(a.systemId().toString());
        JsonNode unknown = notFound(get("/api/v1/campaigns/" + Ids.newId(), b.session()));
        HttpResponse<String> crossOwner = get("/api/v1/campaigns/" + a.campaignId(), b.session());
        assertThat(withoutTraceId(notFound(crossOwner))).isEqualTo(withoutTraceId(unknown));
        assertThat(crossOwner.body()).doesNotContain(a.campaignId().toString()).doesNotContain("First campaign");
        // Each current campaign is the owner's own, named in the language of the first sign-in.
        JsonNode current = ok(get("/api/v1/campaigns/current", b.session()));
        assertThat(current.path("id").asString()).isEqualTo(b.campaignId().toString());
        assertThat(current.path("name").asString()).isEqualTo("Primeira campanha");
        assertThat(current.path("system").path("name").asString()).isEqualTo("Sistema sem nome");
        assertThat(get("/api/v1/campaigns/current", null).statusCode()).isEqualTo(401);
    }

    @Test
    void theStarterAndTheAccountAreStoredTogetherWithTheSameOwnerAndOnlyEncrypted() throws Exception {
        Gm gm = signUp("en");

        assertThat(count("select count(*) from account where id = ?", gm.id())).isEqualTo(1);
        assertThat(count("select count(*) from system where owner_id = ?", gm.id())).isEqualTo(1);
        assertThat(count("select count(*) from campaign where owner_id = ? and system_id = ?", gm.id(), gm.systemId())).isEqualTo(1);
        byte[] systemName = jdbc.sql("select name from system where id = ?").param(gm.systemId()).query(byte[].class).single();
        byte[] campaignName = jdbc.sql("select name from campaign where id = ?").param(gm.campaignId()).query(byte[].class).single();
        for (byte[] stored : List.of(systemName, campaignName)) {
            String asText = new String(stored, StandardCharsets.ISO_8859_1);
            assertThat(asText).doesNotContain("Untitled").doesNotContain("First").doesNotContain("campaign");
        }
        assertThat(vault.decryptText(new Binding("system", "name", gm.systemId(), gm.id()), systemName)).isEqualTo("Untitled system");
        assertThat(vault.decryptText(new Binding("campaign", "name", gm.campaignId(), gm.id()), campaignName))
                .isEqualTo("First campaign");
    }

    @Test
    void whenTheStarterFailsNoAccountOrSystemIsLeftAndTheRetryKeepsTheAccountIdAndKey() throws Exception {
        String sub = "it-fail-" + UUID.randomUUID();
        String username = "itf_" + sub.substring(sub.length() - 12);
        String attempt = identified(sub);
        store.failNextCampaign.set(true);

        HttpResponse<String> failed = complete(attempt, username, "en");

        assertThat(failed.statusCode()).isEqualTo(500);
        assertThat(json(failed).path("code").asString()).isEqualTo("server.unexpected");
        // The attempt keeps its account id, whose data key the callback created; nothing else was stored.
        UUID owner = attempts.find(vault.tokenHash("sign-in", attempt)).orElseThrow().accountId();
        assertThat(count("select count(*) from account where id = ?", owner)).isZero();
        assertThat(count("select count(*) from system where owner_id = ?", owner)).isZero();
        assertThat(count("select count(*) from campaign where owner_id = ?", owner)).isZero();
        byte[] key = jdbc.sql("select wrapped from vault_data_key where owner_id = ?").param(owner).query(byte[].class).single();

        HttpResponse<String> retried = complete(attempt, username, "en");

        assertThat(retried.statusCode()).as(retried.body()).isEqualTo(200);
        assertThat(count("select count(*) from account where id = ?", owner)).isEqualTo(1);
        assertThat(count("select count(*) from system where owner_id = ?", owner)).isEqualTo(1);
        assertThat(count("select count(*) from campaign where owner_id = ?", owner)).isEqualTo(1);
        assertThat(jdbc.sql("select wrapped from vault_data_key where owner_id = ?").param(owner).query(byte[].class).list())
                .containsExactly(key);
    }

    @Test
    void aDataKeyStoredInsideATransactionThatRollsBackIsKept() {
        UUID owner = Ids.newId();
        UUID systemId = Ids.newId();

        OwnerContext.runAs(owner, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            byte[] name = vault.encryptText(new Binding("system", "name", systemId, owner), "Rolled back");
            store.insertSystem(new SystemRow(systemId, owner, name, vault.fingerprint("system", "name", "rolled back"),
                    Instant.now().truncatedTo(ChronoUnit.MICROS)));
            status.setRollbackOnly();
        }));

        assertThat(count("select count(*) from vault_data_key where owner_id = ?", owner)).isEqualTo(1);
        assertThat(count("select count(*) from system where id = ?", systemId)).isZero();
    }

    @Test
    void withoutAnOwnerNoLibraryQueryRuns() {
        UUID anyId = Ids.newId();

        assertThatThrownBy(() -> store.findSystem(anyId)).satisfies(failure -> assertThat(causes(failure))
                .anyMatch(OwnerContext.NoOwnerException.class::isInstance));
        assertThatThrownBy(() -> store.currentCampaign()).satisfies(failure -> assertThat(causes(failure))
                .anyMatch(OwnerContext.NoOwnerException.class::isInstance));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> store.findCampaign(anyId)))
                .satisfies(failure -> assertThat(causes(failure)).anyMatch(OwnerContext.NoOwnerException.class::isInstance));
    }

    @Test
    void hibernateFillsAndFiltersTheOwnerAndTheDatabaseKeepsACampaignWithinItsOwner() throws Exception {
        Gm a = signUp("en");
        UUID b = Ids.newId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // Reads by id: JPQL is filtered; find() is checked too (the store never uses it).
        OwnerContext.runAs(b, () -> {
            assertThat(store.findSystem(a.systemId())).isEmpty();
            assertThat(store.findCampaign(a.campaignId())).isEmpty();
            assertThat(store.currentCampaign()).isEmpty();
            EntityManager em = entityManagers.createEntityManager();
            try {
                assertThat(em.find(SystemEntity.class, a.systemId())).as("find() of another owner's row").isNull();
            } finally {
                em.close();
            }
        });
        // A row of another owner is refused by Hibernate before it is written: its owner is not the current one.
        UUID refusedSystem = Ids.newId();
        assertThatThrownBy(() -> OwnerContext.runAs(b, () -> new TransactionTemplate(transactions).executeWithoutResult(
                status -> store.insertSystem(new SystemRow(refusedSystem, a.id(), new byte[] {1}, new byte[] {2}, now)))))
                .satisfies(failure -> assertThat(messages(failure))
                        .anyMatch(message -> message.contains("assigned tenant id differs from current tenant id")));
        assertThat(count("select count(*) from system where id = ?", refusedSystem)).isZero();
        // A campaign of B can never name A's system: the (owner, system) reference refuses it.
        UUID refusedCampaign = Ids.newId();
        assertThatThrownBy(() -> OwnerContext.runAs(b, () -> new TransactionTemplate(transactions).executeWithoutResult(
                status -> store.insertCampaign(new CampaignRow(refusedCampaign, b, a.systemId(), new byte[] {1}, now)))))
                .satisfies(failure -> assertThat(messages(failure))
                        .anyMatch(message -> message.contains("campaign_system_fkey")));
        assertThat(count("select count(*) from campaign where id = ?", refusedCampaign)).isZero();
        // A second system with the same name, whatever its case and spacing, is refused within one owner: the database's
        // key, read as the store's conflict (Story 3.1), never its message.
        UUID duplicate = Ids.newId();
        assertThatThrownBy(() -> OwnerContext.runAs(a.id(), () -> new TransactionTemplate(transactions).executeWithoutResult(
                status -> store.insertSystem(new SystemRow(duplicate, a.id(), new byte[] {1},
                        vault.fingerprint("system", "name", Library.uniqueForm("  UNTITLED System ")), now)))))
                .isInstanceOfSatisfying(LibraryConflict.class,
                        conflict -> assertThat(conflict.reason()).isEqualTo(LibraryConflict.Reason.NAME_TAKEN))
                .hasMessage("library.name_taken");
        assertThat(count("select count(*) from system where id = ?", duplicate)).isZero();
    }

    // --- Story 3.1: systems ---

    @Test
    void systemsAreCreatedByNameAndEditionStoredOnlyEncryptedAndEditedByVersion() throws Exception {
        Gm gm = signUp("pt-BR");
        // The starter row of V4 now reads with no edition, no description and version 0.
        JsonNode starter = ok(get("/api/v1/systems/" + gm.systemId(), gm.session()));
        assertThat(starter.path("edition").isNull()).isTrue();
        assertThat(starter.path("version").asLong()).isZero();

        // Matrix rows "Create", "Other edition" and "Duplicate".
        JsonNode fifth = created(send("POST", "/api/v1/systems", gm.session(),
                Map.of("name", "D&D", "edition", "5.2.1", "description", "Livro do jogador e SRD.")));
        Thread.sleep(2);
        JsonNode newer = created(send("POST", "/api/v1/systems", gm.session(), Map.of("name", "D&D", "edition", "2024")));
        HttpResponse<String> duplicate = send("POST", "/api/v1/systems", gm.session(), Map.of("name", " d&d ", "edition", "5.2.1"));
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(json(duplicate).path("code").asString()).isEqualTo("request.system_name_taken");
        assertThat(count("select count(*) from system where owner_id = ?", gm.id())).isEqualTo(3);
        HttpResponse<String> invalid = send("POST", "/api/v1/systems", gm.session(), Map.of("name", "x".repeat(121)));
        assertThat(json(invalid).path("code").asString()).isEqualTo("request.system_name_invalid");
        assertThat(count("select count(*) from system where owner_id = ?", gm.id())).isEqualTo(3);
        assertThat(ok(get("/api/v1/systems", gm.session())).path("items")).extracting(item -> item.path("id").asString())
                .containsExactly(newer.path("id").asString(), fifth.path("id").asString(), gm.systemId().toString());

        // The row holds no plain text, and each field decrypts only under its own binding.
        UUID id = UUID.fromString(fifth.path("id").asString());
        Map<String, Object> row = jdbc.sql("select name, edition, description, version from system where id = ?").param(id)
                .query().singleRow();
        for (String column : List.of("name", "edition", "description")) {
            assertThat(new String((byte[]) row.get(column), StandardCharsets.ISO_8859_1)).as(column)
                    .doesNotContain("D&D").doesNotContain("5.2.1").doesNotContain("jogador");
        }
        assertThat(vault.decryptText(new Binding("system", "edition", id, gm.id()), (byte[]) row.get("edition")))
                .isEqualTo("5.2.1");
        assertThat(vault.decryptText(new Binding("system", "description", id, gm.id()), (byte[]) row.get("description")))
                .isEqualTo("Livro do jogador e SRD.");
        assertThat(row.get("version")).isEqualTo(0L);
        assertThat(jdbc.sql("select edition from system where id = ?").param(UUID.fromString(newer.path("id").asString()))
                .query(byte[].class).single()).isNotNull();

        // Matrix rows "Rename to own name" and "Stale edit".
        JsonNode described = ok(send("PATCH", "/api/v1/systems/" + id, gm.session(), Map.of("version", 0, "description", "SRD.")));
        assertThat(described.path("version").asLong()).isEqualTo(1);
        assertThat(described.path("name").asString()).isEqualTo("D&D");
        HttpResponse<String> stale = send("PATCH", "/api/v1/systems/" + id, gm.session(), Map.of("version", 0, "name", "Outro"));
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(json(stale).path("code").asString()).isEqualTo("request.stale_version");
        assertThat(jdbc.sql("select version from system where id = ?").param(id).query(Long.class).single()).isEqualTo(1L);
        assertThat(ok(get("/api/v1/systems/" + id, gm.session())).path("description").asString()).isEqualTo("SRD.");
        // An edit to the other edition's name and edition is taken; dropping the edition is free.
        HttpResponse<String> taken = send("PATCH", "/api/v1/systems/" + id, gm.session(), Map.of("version", 1, "edition", "2024"));
        assertThat(json(taken).path("code").asString()).isEqualTo("request.system_name_taken");
        JsonNode bare = ok(send("PATCH", "/api/v1/systems/" + id, gm.session(), Map.of("version", 1, "edition", " ")));
        assertThat(bare.path("edition").isNull()).isTrue();
        assertThat(bare.path("version").asLong()).isEqualTo(2);
        assertThat(jdbc.sql("select edition is null from system where id = ?").param(id).query(Boolean.class).single()).isTrue();
        // The context bar's next load shows a new name of the current campaign's system.
        ok(send("PATCH", "/api/v1/systems/" + gm.systemId(), gm.session(), Map.of("version", 0, "name", "Tormenta20")));
        assertThat(ok(get("/api/v1/campaigns/current", gm.session())).path("system").path("name").asString())
                .isEqualTo("Tormenta20");
        // A system's campaigns: the starter's one, none for a new system.
        JsonNode campaigns = ok(get("/api/v1/campaigns?system=" + gm.systemId(), gm.session()));
        assertThat(campaigns.path("items")).extracting(item -> item.path("id").asString())
                .containsExactly(gm.campaignId().toString());
        assertThat(ok(get("/api/v1/campaigns?system=" + id, gm.session())).path("items")).isEmpty();
    }

    @Test
    void theStoreRefusesAStaleVersionAndATakenNameAsConflictsAndWritesNothing() throws Exception {
        Gm gm = signUp("en");
        JsonNode other = created(send("POST", "/api/v1/systems", gm.session(), Map.of("name", "GURPS", "edition", "4e")));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        byte[] name = vault.encryptText(new Binding("system", "name", gm.systemId(), gm.id()), "GURPS");
        byte[] takenFingerprint = vault.fingerprint("system", "name", "gurps\u00004e");
        byte[] edition = vault.encryptText(new Binding("system", "edition", gm.systemId(), gm.id()), "4e");

        assertThatThrownBy(() -> OwnerContext.runAs(gm.id(), () -> new TransactionTemplate(transactions).execute(status ->
                store.updateSystem(new SystemChange(gm.systemId(), 3, name, takenFingerprint, edition, null, now)))))
                .isInstanceOfSatisfying(LibraryConflict.class,
                        conflict -> assertThat(conflict.reason()).isEqualTo(LibraryConflict.Reason.STALE_VERSION));
        // The key on (owner, name_fingerprint) refuses what the service would have checked first.
        assertThatThrownBy(() -> OwnerContext.runAs(gm.id(), () -> new TransactionTemplate(transactions).execute(status ->
                store.updateSystem(new SystemChange(gm.systemId(), 0, name, takenFingerprint, edition, null, now)))))
                .isInstanceOfSatisfying(LibraryConflict.class,
                        conflict -> assertThat(conflict.reason()).isEqualTo(LibraryConflict.Reason.NAME_TAKEN));
        assertThat(jdbc.sql("select version from system where id = ?").param(gm.systemId()).query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("select edition is null from system where id = ?").param(gm.systemId()).query(Boolean.class).single())
                .isTrue();
        // Another owner's system is not there for an edit.
        UUID b = Ids.newId();
        Optional<SystemRow> foreign = OwnerContext.callAs(b, () -> new TransactionTemplate(transactions).execute(status ->
                store.updateSystem(new SystemChange(gm.systemId(), 0, name, new byte[] {1}, null, null, now))));
        assertThat(foreign).isEmpty();
        // An edit at the right version is written, and its version rises.
        Optional<SystemRow> written = OwnerContext.callAs(gm.id(), () -> new TransactionTemplate(transactions).execute(
                status -> store.updateSystem(new SystemChange(gm.systemId(), 0, name,
                        vault.fingerprint("system", "name", "gurps"), null, null, now))));
        assertThat(written).hasValueSatisfying(row -> assertThat(row.version()).isEqualTo(1));
        assertThat(other.path("version").asLong()).isZero();
    }

    @Test
    void anotherOwnersSystemIsNotFoundOnEveryNewRouteAndFilter() throws Exception {
        Gm a = signUp("en");
        Gm b = signUp("pt-BR");
        JsonNode unknown = notFound(get("/api/v1/systems/" + Ids.newId(), b.session()));

        List<HttpResponse<String>> refused = List.of(
                send("PATCH", "/api/v1/systems/" + a.systemId(), b.session(), Map.of("version", 0, "name", "Stolen")),
                get("/api/v1/campaigns?system=" + a.systemId(), b.session()),
                get("/api/v1/materials?state=ready&state=uploading&system=" + a.systemId(), b.session()),
                get("/api/v1/campaigns?system=not-a-uuid", b.session()),
                get("/api/v1/materials?state=ready&system=" + Ids.newId(), b.session()));
        for (HttpResponse<String> response : refused) {
            assertThat(withoutTraceId(notFound(response))).isEqualTo(withoutTraceId(unknown));
            assertThat(response.body()).doesNotContain(a.systemId().toString()).doesNotContain("Untitled system")
                    .doesNotContain("First campaign");
        }
        assertThat(jdbc.sql("select version from system where id = ?").param(a.systemId()).query(Long.class).single()).isZero();
        assertThat(ok(get("/api/v1/systems", b.session())).path("items")).extracting(item -> item.path("id").asString())
                .containsExactly(b.systemId().toString());
        // A's own filters answer.
        assertThat(ok(get("/api/v1/materials?state=ready&system=" + a.systemId(), a.session())).path("items")).isEmpty();
        assertThat(ok(get("/api/v1/campaigns?system=" + a.systemId(), a.session())).path("items")).hasSize(1);
    }

    @Test
    void theListsPageByCursorOnPostgresqlNewestFirst() throws Exception {
        Gm gm = signUp("en");
        Thread.sleep(2);
        String older = created(send("POST", "/api/v1/systems", gm.session(), Map.of("name", "Older"))).path("id").asString();
        Thread.sleep(2);
        String newer = created(send("POST", "/api/v1/systems", gm.session(), Map.of("name", "Newer"))).path("id").asString();

        JsonNode first = ok(get("/api/v1/systems?limit=1", gm.session()));
        assertThat(first.path("items")).extracting(item -> item.path("id").asString()).containsExactly(newer);
        assertThat(first.path("nextCursor").asString()).isEqualTo(newer);
        JsonNode second = ok(get("/api/v1/systems?limit=1&cursor=" + newer, gm.session()));
        assertThat(second.path("items")).extracting(item -> item.path("id").asString()).containsExactly(older);
        JsonNode last = ok(get("/api/v1/systems?limit=1&cursor=" + older, gm.session()));
        assertThat(last.path("items")).extracting(item -> item.path("id").asString())
                .containsExactly(gm.systemId().toString());
        assertThat(last.has("nextCursor")).isFalse();

        // A second campaign of the starter system, newer than the starter's.
        Thread.sleep(2);
        UUID campaign = Ids.newId();
        OwnerContext.runAs(gm.id(), () -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                store.insertCampaign(new CampaignRow(campaign, gm.id(), gm.systemId(),
                        vault.encryptText(new Binding("campaign", "name", campaign, gm.id()), "Second campaign"),
                        Instant.now().truncatedTo(ChronoUnit.MICROS)))));
        JsonNode campaigns = ok(get("/api/v1/campaigns?limit=1&system=" + gm.systemId(), gm.session()));
        assertThat(campaigns.path("items")).extracting(item -> item.path("name").asString()).containsExactly("Second campaign");
        assertThat(campaigns.path("nextCursor").asString()).isEqualTo(campaign.toString());
        JsonNode lastCampaign = ok(get("/api/v1/campaigns?limit=1&system=" + gm.systemId() + "&cursor=" + campaign,
                gm.session()));
        assertThat(lastCampaign.path("items")).extracting(item -> item.path("id").asString())
                .containsExactly(gm.campaignId().toString());
        assertThat(lastCampaign.has("nextCursor")).isFalse();
    }

    @Test
    void aSystemsMaterialsAreThoseAttachedToItOrToItsCampaignsOnPostgresql() throws Exception {
        Gm gm = signUp("en");
        UUID otherSystem = UUID.fromString(created(send("POST", "/api/v1/systems", gm.session(),
                Map.of("name", "Other"))).path("id").asString());
        UUID ofSystem = material(gm, "srd.pdf", null, gm.systemId());
        UUID ofCampaign = material(gm, "notas.md", gm.campaignId(), null);
        material(gm, "loose.pdf", null, null);
        material(gm, "other.pdf", null, otherSystem);

        String list = "/api/v1/materials?state=ready&system=" + gm.systemId();
        assertThat(ok(get(list, gm.session())).path("items")).extracting(item -> item.path("id").asString())
                .containsExactly(ofCampaign.toString(), ofSystem.toString());
        JsonNode first = ok(get(list + "&limit=1", gm.session()));
        assertThat(first.path("items")).extracting(item -> item.path("name").asString()).containsExactly("notas.md");
        assertThat(first.path("nextCursor").asString()).isEqualTo(ofCampaign.toString());
        JsonNode last = ok(get(list + "&limit=1&cursor=" + ofCampaign, gm.session()));
        assertThat(last.path("items")).extracting(item -> item.path("id").asString()).containsExactly(ofSystem.toString());
        assertThat(last.has("nextCursor")).isFalse();
        // A system with neither, and one with a material of its own but no campaign.
        assertThat(ok(get("/api/v1/materials?state=ready&system=" + otherSystem, gm.session())).path("items"))
                .extracting(item -> item.path("name").asString()).containsExactly("other.pdf");
    }

    // --- Story 3.2: campaigns ---

    @Test
    void campaignsAreCreatedStoredOnlyEncryptedRenamedByVersionArchivedAndChosenOnPostgresql() throws Exception {
        Gm gm = signUp("pt-BR");
        UUID otherSystem = UUID.fromString(created(send("POST", "/api/v1/systems", gm.session(),
                Map.of("name", "D&D"))).path("id").asString());

        // Matrix rows "Create", "Duplicate name", "Same name, other system" and "Invalid name".
        Thread.sleep(2);
        JsonNode thursday = created(send("POST", "/api/v1/campaigns", gm.session(),
                Map.of("name", "Mesa de quinta", "systemId", gm.systemId().toString())));
        UUID id = UUID.fromString(thursday.path("id").asString());
        assertThat(ok(get("/api/v1/campaigns/current", gm.session())).path("id").asString()).isEqualTo(id.toString());
        HttpResponse<String> duplicate = send("POST", "/api/v1/campaigns", gm.session(),
                Map.of("name", " mesa de QUINTA ", "systemId", gm.systemId().toString()));
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(json(duplicate).path("code").asString()).isEqualTo("request.campaign_name_taken");
        // The starter's own name is taken as well: a first sign-in stores its fingerprint.
        assertThat(json(send("POST", "/api/v1/campaigns", gm.session(), Map.of("name", "PRIMEIRA campanha",
                "systemId", gm.systemId().toString()))).path("code").asString()).isEqualTo("request.campaign_name_taken");
        HttpResponse<String> invalid = send("POST", "/api/v1/campaigns", gm.session(),
                Map.of("name", "x".repeat(121), "systemId", gm.systemId().toString()));
        assertThat(json(invalid).path("code").asString()).isEqualTo("request.campaign_name_invalid");
        assertThat(count("select count(*) from campaign where owner_id = ?", gm.id())).isEqualTo(2);
        Thread.sleep(2);
        UUID elsewhere = UUID.fromString(created(send("POST", "/api/v1/campaigns", gm.session(),
                Map.of("name", "Mesa de quinta", "systemId", otherSystem.toString()))).path("id").asString());

        // Stored only encrypted, with its fingerprint; the choice is a time, at version 0.
        Map<String, Object> row = jdbc.sql("select name, name_fingerprint, version, archived_at, chosen_at from campaign"
                + " where id = ?").param(id).query().singleRow();
        assertThat(new String((byte[]) row.get("name"), StandardCharsets.ISO_8859_1)).doesNotContain("Mesa");
        assertThat(vault.decryptText(new Binding("campaign", "name", id, gm.id()), (byte[]) row.get("name")))
                .isEqualTo("Mesa de quinta");
        assertThat((byte[]) row.get("name_fingerprint")).isEqualTo(vault.fingerprint("campaign", "name", "mesa de quinta"));
        assertThat(row.get("version")).isEqualTo(0L);
        assertThat(row.get("archived_at")).isNull();
        assertThat(row.get("chosen_at")).isNotNull();

        // Matrix row "Switch": the choice leaves the version; then "Stale rename".
        ok(send("PUT", "/api/v1/campaigns/current", gm.session(), Map.of("campaignId", id.toString())));
        assertThat(ok(get("/api/v1/campaigns/current", gm.session())).path("id").asString()).isEqualTo(id.toString());
        assertThat(version(id)).isZero();
        JsonNode renamed = ok(send("PATCH", "/api/v1/campaigns/" + id, gm.session(), Map.of("version", 0, "name", "Sexta")));
        assertThat(renamed.path("version").asLong()).isEqualTo(1);
        HttpResponse<String> stale = send("PATCH", "/api/v1/campaigns/" + id, gm.session(), Map.of("version", 0, "name", "X"));
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(json(stale).path("code").asString()).isEqualTo("request.stale_version");
        assertThat(version(id)).isEqualTo(1);
        assertThat(ok(get("/api/v1/campaigns/" + id, gm.session())).path("name").asString()).isEqualTo("Sexta");

        // Matrix rows "Archive current", "Switch to archived" and "Restore".
        JsonNode archived = ok(send("PATCH", "/api/v1/campaigns/" + id, gm.session(), Map.of("version", 1, "archived", true)));
        assertThat(archived.path("archived").asBoolean()).isTrue();
        assertThat(jdbc.sql("select chosen_at is null and archived_at is not null from campaign where id = ?").param(id)
                .query(Boolean.class).single()).isTrue();
        assertThat(ok(get("/api/v1/campaigns/current", gm.session())).path("id").asString()).isEqualTo(elsewhere.toString());
        assertThat(ok(get("/api/v1/campaigns", gm.session())).path("items")).extracting(item -> item.path("id").asString())
                .containsExactly(elsewhere.toString(), gm.campaignId().toString());
        assertThat(ok(get("/api/v1/campaigns?archived=true", gm.session())).path("items"))
                .extracting(item -> item.path("id").asString()).containsExactly(id.toString());
        assertThat(json(send("PUT", "/api/v1/campaigns/current", gm.session(), Map.of("campaignId", id.toString())))
                .path("code").asString()).isEqualTo("request.not_found");
        ok(send("PATCH", "/api/v1/campaigns/" + id, gm.session(), Map.of("version", 2, "archived", false)));
        assertThat(ok(get("/api/v1/campaigns/current", gm.session())).path("id").asString()).isEqualTo(elsewhere.toString());
        // All the account's campaigns, by cursor on PostgreSQL, each with its system.
        JsonNode first = ok(get("/api/v1/campaigns?limit=2", gm.session()));
        assertThat(first.path("items")).extracting(item -> item.path("id").asString())
                .containsExactly(elsewhere.toString(), id.toString());
        assertThat(first.path("items").get(0).path("system").path("name").asString()).isEqualTo("D&D");
        JsonNode last = ok(get("/api/v1/campaigns?limit=2&cursor=" + first.path("nextCursor").asString(), gm.session()));
        assertThat(last.path("items")).extracting(item -> item.path("id").asString())
                .containsExactly(gm.campaignId().toString());
        assertThat(last.has("nextCursor")).isFalse();
        assertThat(ok(get("/api/v1/campaigns?system=" + otherSystem, gm.session())).path("items"))
                .extracting(item -> item.path("id").asString()).containsExactly(elsewhere.toString());
    }

    @Test
    void theStoreRefusesAStaleCampaignAndATakenCampaignNameAsConflicts() throws Exception {
        Gm gm = signUp("en");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        byte[] taken = vault.fingerprint("campaign", "name", "first campaign");
        UUID twin = Ids.newId();

        // The key on (owner, system, name_fingerprint) refuses what the service would have checked first.
        assertThatThrownBy(() -> OwnerContext.runAs(gm.id(), () -> new TransactionTemplate(transactions).executeWithoutResult(
                status -> store.insertCampaign(new CampaignRow(twin, gm.id(), gm.systemId(), new byte[] {1}, taken, null,
                        null, 0, now)))))
                .isInstanceOfSatisfying(LibraryConflict.class,
                        conflict -> assertThat(conflict.reason()).isEqualTo(LibraryConflict.Reason.CAMPAIGN_NAME_TAKEN));
        assertThat(count("select count(*) from campaign where id = ?", twin)).isZero();
        assertThatThrownBy(() -> OwnerContext.runAs(gm.id(), () -> new TransactionTemplate(transactions).execute(status ->
                store.updateCampaign(new CampaignChange(gm.campaignId(), 4, new byte[] {1}, taken, false, now)))))
                .isInstanceOfSatisfying(LibraryConflict.class,
                        conflict -> assertThat(conflict.reason()).isEqualTo(LibraryConflict.Reason.STALE_VERSION));
        assertThat(version(gm.campaignId())).isZero();
        // Another owner's campaign is not there to change or to choose.
        UUID b = Ids.newId();
        Optional<CampaignRow> foreign = OwnerContext.callAs(b, () -> new TransactionTemplate(transactions).execute(status ->
                store.updateCampaign(new CampaignChange(gm.campaignId(), 0, new byte[] {1}, taken, true, now))));
        assertThat(foreign).isEmpty();
        Boolean chosen = OwnerContext.callAs(b, () -> new TransactionTemplate(transactions).execute(status ->
                store.chooseCampaign(gm.campaignId(), now)));
        assertThat(chosen).isFalse();
        assertThat(jdbc.sql("select chosen_at is null and archived_at is null from campaign where id = ?")
                .param(gm.campaignId()).query(Boolean.class).single()).isTrue();
    }

    @Test
    void aCampaignFromBeforeV23HoldsItsNameAndTheFillAtStartGivesItItsFingerprint() throws Exception {
        Gm gm = signUp("en");
        UUID old = Ids.newId();
        OwnerContext.runAs(gm.id(), () -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                store.insertCampaign(new CampaignRow(old, gm.id(), gm.systemId(),
                        vault.encryptText(new Binding("campaign", "name", old, gm.id()), "Velha mesa"),
                        Instant.now().truncatedTo(ChronoUnit.MICROS)))));
        jdbc.sql("insert into campaign_fingerprint_backfill (owner_id) values (?)").param(gm.id()).update();
        assertThat(jdbc.sql("select name_fingerprint is null from campaign where id = ?").param(old).query(Boolean.class)
                .single()).isTrue();

        // Before the fill, compared by its decrypted name.
        HttpResponse<String> before = send("POST", "/api/v1/campaigns", gm.session(),
                Map.of("name", " VELHA mesa", "systemId", gm.systemId().toString()));
        assertThat(json(before).path("code").asString()).isEqualTo("request.campaign_name_taken");

        assertThat(fingerprints.run()).isGreaterThanOrEqualTo(1);

        assertThat(jdbc.sql("select name_fingerprint from campaign where id = ?").param(old).query(byte[].class).single())
                .isEqualTo(vault.fingerprint("campaign", "name", "velha mesa"));
        assertThat(version(old)).isZero();
        assertThat(count("select count(*) from campaign_fingerprint_backfill where owner_id = ?", gm.id())).isZero();
        // After it, by the key; a rename to it as well.
        HttpResponse<String> after = send("POST", "/api/v1/campaigns", gm.session(),
                Map.of("name", "velha MESA", "systemId", gm.systemId().toString()));
        assertThat(json(after).path("code").asString()).isEqualTo("request.campaign_name_taken");
        HttpResponse<String> rename = send("PATCH", "/api/v1/campaigns/" + gm.campaignId(), gm.session(),
                Map.of("version", 0, "name", "Velha Mesa"));
        assertThat(json(rename).path("code").asString()).isEqualTo("request.campaign_name_taken");
        assertThat(count("select count(*) from campaign where owner_id = ?", gm.id())).isEqualTo(2);
    }

    @Test
    void anotherOwnersCampaignOrSystemIsNotFoundOnEveryCampaignRoute() throws Exception {
        Gm a = signUp("en");
        Gm b = signUp("pt-BR");
        // A's campaign has a conversation (no question, so no answer job is left behind): B still learns nothing of it.
        UUID conversationId = Ids.newId();
        OwnerContext.runAs(a.id(), () -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                conversations.insertConversation(new ConversationRow(conversationId, a.id(), a.campaignId(),
                        Instant.now().truncatedTo(ChronoUnit.MICROS)))));
        JsonNode conversation = ok(get("/api/v1/campaigns/" + a.campaignId() + "/conversation", a.session()));
        assertThat(conversation.path("id").asString()).isEqualTo(conversationId.toString());
        assertThat(conversation.path("questions").asLong()).isZero();
        JsonNode unknown = notFound(get("/api/v1/campaigns/" + Ids.newId(), b.session()));

        List<HttpResponse<String>> refused = List.of(
                send("PATCH", "/api/v1/campaigns/" + a.campaignId(), b.session(), Map.of("version", 0, "name", "Stolen")),
                send("PATCH", "/api/v1/campaigns/" + a.campaignId(), b.session(), Map.of("version", 0, "archived", true)),
                send("PUT", "/api/v1/campaigns/current", b.session(), Map.of("campaignId", a.campaignId().toString())),
                send("POST", "/api/v1/campaigns", b.session(), Map.of("name", "Stolen", "systemId", a.systemId().toString())),
                get("/api/v1/campaigns?archived=true&system=" + a.systemId(), b.session()),
                get("/api/v1/campaigns/" + a.campaignId() + "/conversation", b.session()),
                get("/api/v1/campaigns/" + Ids.newId() + "/conversation", b.session()),
                get("/api/v1/campaigns/" + b.campaignId() + "/conversation", b.session()));
        for (HttpResponse<String> response : refused) {
            assertThat(withoutTraceId(notFound(response))).isEqualTo(withoutTraceId(unknown));
            assertThat(response.body()).doesNotContain(a.campaignId().toString()).doesNotContain(a.systemId().toString())
                    .doesNotContain(conversation.path("id").asString()).doesNotContain("First campaign");
        }
        assertThat(jdbc.sql("select version from campaign where id = ?").param(a.campaignId()).query(Long.class).single())
                .isZero();
        assertThat(count("select count(*) from campaign where owner_id = ?", b.id())).isEqualTo(1);
        assertThat(ok(get("/api/v1/campaigns", b.session())).path("items")).extracting(item -> item.path("id").asString())
                .containsExactly(b.campaignId().toString());
    }

    private long version(UUID campaign) {
        return jdbc.sql("select version from campaign where id = ?").param(campaign).query(Long.class).single();
    }

    /** A ready material of the owner, in its campaign or attached to a system (or neither): its id, a millisecond later. */
    private UUID material(Gm gm, String name, UUID campaign, UUID system) throws Exception {
        Thread.sleep(2);
        UUID material = Ids.newId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OwnerContext.runAs(gm.id(), () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            materials.insertMaterial(new MaterialRow(material, gm.id(), vault.encryptText(new Binding("material", "name",
                    material, gm.id()), name), now, null, null, campaign));
            materials.insertVersion(new VersionRow(Ids.newId(), gm.id(), material, "ready", "pdf", 10L, now, now));
            if (system != null) {
                materials.insertMaterialSystem(new MaterialSystemRow(Ids.newId(), gm.id(), material, system, now));
            }
        }));
        return material;
    }

    // --- helpers ---

    private Gm signUp(String language) throws Exception {
        String sub = "it-" + UUID.randomUUID();
        String attempt = identified(sub);
        HttpResponse<String> response = complete(attempt, "it_" + sub.substring(sub.length() - 12), language);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String session = json(response).path("sessionToken").asString();
        JsonNode current = ok(get("/api/v1/campaigns/current", session));
        UUID campaignId = UUID.fromString(current.path("id").asString());
        UUID owner = jdbc.sql("select owner_id from campaign where id = ?").param(campaignId).query(UUID.class).single();
        return new Gm(owner, session, UUID.fromString(current.path("system").path("id").asString()), campaignId);
    }

    private String identified(String sub) throws Exception {
        JsonNode started = json(post("/api/v1/sign-in/google", null));
        String token = started.path("attemptToken").asString();
        String state = null;
        for (String pair : URI.create(started.path("authorizationUrl").asString()).getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals("state")) {
                state = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attemptToken", token);
        body.put("state", state);
        body.put("code", "stand-in." + sub + ".gm@example.test");
        assertThat(json(post("/api/v1/sign-in/google/callback", body)).path("outcome").asString()).isEqualTo("username_required");
        return token;
    }

    private HttpResponse<String> complete(String attempt, String username, String language) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attemptToken", attempt);
        body.put("username", username);
        body.put("termsAccepted", true);
        if (language != null) {
            body.put("language", language);
        }
        return post("/api/v1/sign-in/complete", body);
    }

    private long count(String sql, Object... params) {
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        for (Object param : params) {
            statement = statement.param(param);
        }
        return statement.query(Long.class).single();
    }

    /** The messages of a failure and its causes (a test may read them; the application never logs them). */
    private static List<String> messages(Throwable failure) {
        return causes(failure).stream().map(cause -> String.valueOf(cause.getMessage())).toList();
    }

    private static List<Throwable> causes(Throwable failure) {
        List<Throwable> chain = new java.util.ArrayList<>();
        for (Throwable cause = failure; cause != null && !chain.contains(cause); cause = cause.getCause()) {
            chain.add(cause);
        }
        return chain;
    }

    private static JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        return json(response);
    }

    private static JsonNode created(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        return json(response);
    }

    private HttpResponse<String> send(String method, String path, String session, Map<String, Object> body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + CREDENTIAL).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (session != null) {
            request.header("X-Session-Token", session);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode notFound(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode problem = json(response);
        assertThat(problem.path("code").asString()).isEqualTo("request.not_found");
        return problem;
    }

    private static Map<String, Object> withoutTraceId(JsonNode problem) {
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = JSON.convertValue(problem, Map.class);
        fields.remove("traceId");
        return fields;
    }

    private HttpResponse<String> post(String path, Map<String, Object> body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + CREDENTIAL);
        if (body == null) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String session) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + CREDENTIAL).GET();
        if (session != null) {
            request.header("X-Session-Token", session);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }
}
