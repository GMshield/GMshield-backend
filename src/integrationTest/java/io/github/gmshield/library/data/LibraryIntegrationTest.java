package io.github.gmshield.library.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.accounts.data.SignInAttemptStore;
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
 * library query, and the cross-owner tests of a system and a campaign through HTTP.
 *
 * <p>The raw rows are read here with SQL to prove what is stored; the application itself never reads a library
 * table with native SQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GMSHIELD_MODE=test")
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
        public Optional<SystemRow> findSystem(UUID id) {
            return real.findSystem(id);
        }

        @Override
        public Optional<CampaignRow> findCampaign(UUID id) {
            return real.findCampaign(id);
        }

        @Override
        public Optional<CampaignRow> firstCampaign() {
            return real.firstCampaign();
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
        assertThatThrownBy(() -> store.firstCampaign()).satisfies(failure -> assertThat(causes(failure))
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
            assertThat(store.firstCampaign()).isEmpty();
            EntityManager em = entityManagers.createEntityManager();
            try {
                assertThat(em.find(SystemEntity.class, a.systemId())).as("find() of another owner's row").isNull();
            } finally {
                em.close();
            }
        });
        // A row of another owner is refused before it is written.
        UUID refusedSystem = Ids.newId();
        assertThatThrownBy(() -> OwnerContext.runAs(b, () -> new TransactionTemplate(transactions).executeWithoutResult(
                status -> store.insertSystem(new SystemRow(refusedSystem, a.id(), new byte[] {1}, new byte[] {2}, now)))))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("select count(*) from system where id = ?", refusedSystem)).isZero();
        // A campaign of B can never name A's system.
        UUID refusedCampaign = Ids.newId();
        assertThatThrownBy(() -> OwnerContext.runAs(b, () -> new TransactionTemplate(transactions).executeWithoutResult(
                status -> store.insertCampaign(new CampaignRow(refusedCampaign, b, a.systemId(), new byte[] {1}, now)))))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("select count(*) from campaign where id = ?", refusedCampaign)).isZero();
        // A second system with the same name, whatever its case, is refused within one owner.
        assertThatThrownBy(() -> OwnerContext.runAs(a.id(), () -> new TransactionTemplate(transactions).executeWithoutResult(
                status -> store.insertSystem(new SystemRow(Ids.newId(), a.id(), new byte[] {1},
                        vault.fingerprint("system", "name", "untitled system"), now)))))
                .isInstanceOf(RuntimeException.class);
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
