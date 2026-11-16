package io.github.gmshield.library.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.accounts.data.AccountRow;
import io.github.gmshield.accounts.data.AccountStore;
import io.github.gmshield.accounts.data.InMemoryAccountStore;
import io.github.gmshield.accounts.data.InMemorySessionStore;
import io.github.gmshield.accounts.data.InMemorySignInAttemptStore;
import io.github.gmshield.accounts.data.SessionStore;
import io.github.gmshield.accounts.data.SignInAttemptStore;
import io.github.gmshield.audit.data.AuditStore;
import io.github.gmshield.audit.data.InMemoryAuditStore;
import io.github.gmshield.library.data.CampaignRow;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.data.LibraryTestStores;
import io.github.gmshield.library.data.SystemRow;
import io.github.gmshield.library.service.Language;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.shared.data.InMemoryTransactionManager;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.data.DataKeyStore;
import io.github.gmshield.vault.data.InMemoryDataKeyStore;
import io.github.gmshield.vault.data.WrappedDataKey;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The signed-in shell's first campaign (Story 1.11), without a database: the starter library of a first sign-in
 * in both languages, its rollback with the account, the library routes and their owner, and the owner-context
 * guard. Every backend row of the story's I/O matrix; the same rows against PostgreSQL are in the integration
 * tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GMSHIELD_MODE=test")
@ActiveProfiles({"test-mode", "unit"})
class LibraryFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CREDENTIAL = "gmshield-test-mode-frontend-credential-not-a-secret";

    @TestConfiguration
    @Import(LibraryTestStores.class)
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
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private InMemoryAccountStore accounts;

    @Autowired
    private InMemorySignInAttemptStore attempts;

    @Autowired
    private InMemoryLibraryStore library;

    @Autowired
    private InMemoryDataKeyStore dataKeys;

    @Autowired
    private InMemoryTransactionManager transactions;

    @Autowired
    private Library libraryService;

    @Autowired
    private Vault vault;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void freshAttempts() {
        attempts.values.clear();
        library.failNextCampaign.set(false);
    }

    /** A signed-up account: its id, its session token. */
    record Gm(UUID id, String session) {
    }

    @Test
    void aFirstSignInInEnglishCreatesTheAccountWithAnEnglishSystemAndCampaignOfTheSameOwner() throws Exception {
        Gm gm = signUp("en");

        SystemRow system = onlySystemOf(gm.id());
        CampaignRow campaign = onlyCampaignOf(gm.id());
        assertThat(system.ownerId()).isEqualTo(gm.id());
        assertThat(campaign.ownerId()).isEqualTo(gm.id());
        assertThat(campaign.systemId()).isEqualTo(system.id());
        assertThat(vault.decryptText(new Binding("system", "name", system.id(), gm.id()), system.name()))
                .isEqualTo("Untitled system");
        assertThat(vault.decryptText(new Binding("campaign", "name", campaign.id(), gm.id()), campaign.name()))
                .isEqualTo("First campaign");
        // Stored only encrypted, and fingerprinted in the unique form.
        assertThat(new String(system.name(), StandardCharsets.ISO_8859_1)).doesNotContain("Untitled");
        assertThat(new String(campaign.name(), StandardCharsets.ISO_8859_1)).doesNotContain("First");
        assertThat(system.nameFingerprint()).isEqualTo(vault.fingerprint("system", "name", "untitled system"));

        JsonNode current = current(gm.session(), 200);
        assertThat(current.path("id").asString()).isEqualTo(campaign.id().toString());
        assertThat(current.path("name").asString()).isEqualTo("First campaign");
        assertThat(current.path("system").path("id").asString()).isEqualTo(system.id().toString());
        assertThat(current.path("system").path("name").asString()).isEqualTo("Untitled system");
    }

    @Test
    void withoutALanguageTheStarterNamesArePortuguese() throws Exception {
        Gm gm = signUp(null);

        JsonNode current = current(gm.session(), 200);
        assertThat(current.path("name").asString()).isEqualTo("Primeira campanha");
        assertThat(current.path("system").path("name").asString()).isEqualTo("Sistema sem nome");
        JsonNode explicit = current(signUp("pt-BR").session(), 200);
        assertThat(explicit.path("system").path("name").asString()).isEqualTo("Sistema sem nome");
    }

    @Test
    void anUnknownLanguageIsAnInvalidRequestAndTheAttemptStays() throws Exception {
        String sub = "lang-" + UUID.randomUUID();
        String attempt = identified(sub);

        HttpResponse<String> refused = complete(attempt, "lang_" + sub.substring(5, 11), "fr");

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json(refused).path("code").asString()).isEqualTo("request.invalid");
        assertThat(attempts.values).hasSize(1);
        assertThat(complete(attempt, "lang_" + sub.substring(5, 11), "en").statusCode()).isEqualTo(200);
    }

    @Test
    void whenTheStarterFailsNeitherTheAccountNorTheSystemExistAndARetryUsesTheSameAccountAndKey() throws Exception {
        String sub = "fail-" + UUID.randomUUID();
        String username = "fail_" + sub.substring(5, 11);
        String attempt = identified(sub);
        int rollbacks = transactions.rollbacks.get();
        library.failNextCampaign.set(true);

        HttpResponse<String> failed = complete(attempt, username, "en");

        assertThat(failed.statusCode()).isEqualTo(500);
        assertThat(json(failed).path("code").asString()).isEqualTo("server.unexpected");
        assertThat(failed.body()).doesNotContain("campaign insert failed");
        assertThat(transactions.rollbacks.get()).isEqualTo(rollbacks + 1);
        assertThat(accounts.findByGoogleIdFingerprint(vault.fingerprint("account", "google_id", sub))).isEmpty();
        // The attempt and the data key of its account id are kept.
        assertThat(attempts.values).hasSize(1);
        UUID accountId = attempts.find(vault.tokenHash("sign-in", attempt)).orElseThrow().accountId();
        assertThat(library.systems.values()).noneMatch(row -> row.ownerId().equals(accountId));
        assertThat(library.campaigns.values()).noneMatch(row -> row.ownerId().equals(accountId));
        WrappedDataKey key = dataKeys.latest(accountId).orElseThrow();

        HttpResponse<String> retried = complete(attempt, username, "en");

        assertThat(retried.statusCode()).as(retried.body()).isEqualTo(200);
        AccountRow account = accounts.findByGoogleIdFingerprint(vault.fingerprint("account", "google_id", sub)).orElseThrow();
        assertThat(account.id()).isEqualTo(accountId);
        assertThat(dataKeys.rows.values().stream().filter(row -> row.ownerId().equals(accountId))).containsExactly(key);
        assertThat(onlySystemOf(accountId).ownerId()).isEqualTo(accountId);
        assertThat(onlyCampaignOf(accountId).ownerId()).isEqualTo(accountId);
    }

    @Test
    void aReturningSignInCreatesNoNewSystemOrCampaign() throws Exception {
        String sub = "back-" + UUID.randomUUID();
        Gm gm = signUp(sub, "en");
        int systems = library.systems.size();
        int campaigns = library.campaigns.size();

        JsonNode again = callback(start(), sub);

        assertThat(again.path("outcome").asString()).isEqualTo("signed_in");
        assertThat(library.systems).hasSize(systems);
        assertThat(library.campaigns).hasSize(campaigns);
        assertThat(onlySystemOf(gm.id())).isNotNull();
    }

    @Test
    void theLibraryRoutesNeedASession() throws Exception {
        Gm gm = signUp("en");
        UUID campaignId = onlyCampaignOf(gm.id()).id();
        for (String path : List.of("/api/v1/campaigns/current", "/api/v1/campaigns/" + campaignId,
                "/api/v1/systems/" + onlySystemOf(gm.id()).id())) {
            for (String token : new String[] {null, "", "short", "C".repeat(43)}) {
                HttpResponse<String> response = get(path, token);
                assertThat(response.statusCode()).as(path + " " + token).isEqualTo(401);
                assertThat(json(response).path("code").asString()).isEqualTo("session.required");
                assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
                assertThat(response.body()).doesNotContain(campaignId.toString());
            }
        }
    }

    @Test
    void anotherOwnersIdAnUnknownIdAndAMalformedIdAreTheSameNotFound() throws Exception {
        Gm a = signUp("en");
        Gm b = signUp("pt-BR");
        UUID aSystem = onlySystemOf(a.id()).id();
        UUID aCampaign = onlyCampaignOf(a.id()).id();

        // A reads its own.
        JsonNode own = ok(get("/api/v1/campaigns/" + aCampaign, a.session()));
        assertThat(own.path("name").asString()).isEqualTo("First campaign");
        assertThat(own.path("system").path("id").asString()).isEqualTo(aSystem.toString());
        assertThat(ok(get("/api/v1/systems/" + aSystem, a.session())).path("name").asString()).isEqualTo("Untitled system");

        // B learns nothing about A's items: the same problem as for an id that exists nowhere.
        JsonNode expected = notFound(get("/api/v1/systems/" + UUID.randomUUID(), b.session()));
        for (String path : List.of("/api/v1/systems/" + aSystem, "/api/v1/campaigns/" + aCampaign,
                "/api/v1/campaigns/" + UUID.randomUUID(), "/api/v1/systems/not-a-uuid", "/api/v1/campaigns/123",
                "/api/v1/systems/" + aSystem.toString().toUpperCase() + "0")) {
            HttpResponse<String> response = get(path, b.session());
            JsonNode problem = notFound(response);
            assertThat(withoutTraceId(problem)).as(path).isEqualTo(withoutTraceId(expected));
            assertThat(response.body()).doesNotContain(aSystem.toString()).doesNotContain(aCampaign.toString())
                    .doesNotContain("First campaign").doesNotContain("Untitled system");
        }
        // B's current campaign is B's.
        assertThat(current(b.session(), 200).path("name").asString()).isEqualTo("Primeira campanha");
    }

    @Test
    void anAccountWithoutACampaignHasNoCurrentOne() throws Exception {
        Gm gm = signUp("en");
        library.campaigns.values().removeIf(row -> row.ownerId().equals(gm.id()));

        assertThat(current(gm.session(), 404).path("code").asString()).isEqualTo("request.not_found");
    }

    @Test
    void withoutAnOwnerNoLibraryReadRunsAndNoOwnerOutlivesItsWork() throws Exception {
        assertThat(OwnerContext.current()).isEmpty();
        assertThatThrownBy(() -> libraryService.currentCampaign()).isInstanceOf(OwnerContext.NoOwnerException.class);
        assertThatThrownBy(() -> libraryService.system(UUID.randomUUID())).isInstanceOf(OwnerContext.NoOwnerException.class);
        assertThatThrownBy(() -> libraryService.campaign(UUID.randomUUID())).isInstanceOf(OwnerContext.NoOwnerException.class);
        // A starter outside a transaction, or without an owner, is refused.
        UUID owner = UUID.randomUUID();
        assertThatThrownBy(() -> OwnerContext.runAs(owner, () -> libraryService.createStarter(Language.EN)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(library.systems.values()).noneMatch(row -> row.ownerId().equals(owner));

        // The request edge unbinds the owner once the request is over.
        Gm gm = signUp("en");
        current(gm.session(), 200);
        assertThat(OwnerContext.current()).isEmpty();
    }

    // --- helpers ---

    private Gm signUp(String language) throws Exception {
        return signUp("lib-" + UUID.randomUUID(), language);
    }

    private Gm signUp(String sub, String language) throws Exception {
        String attempt = identified(sub);
        HttpResponse<String> response = complete(attempt, "u" + sub.substring(sub.length() - 12).replace("-", "_"), language);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        AccountRow row = accounts.findByGoogleIdFingerprint(vault.fingerprint("account", "google_id", sub)).orElseThrow();
        return new Gm(row.id(), json(response).path("sessionToken").asString());
    }

    /** An attempt whose callback identified a new Google identity: the username step comes next. */
    private String identified(String sub) throws Exception {
        String[] attempt = start();
        assertThat(callback(attempt, sub).path("outcome").asString()).isEqualTo("username_required");
        return attempt[0];
    }

    private String[] start() throws Exception {
        JsonNode body = json(post("/api/v1/sign-in/google", null));
        String state = null;
        for (String pair : URI.create(body.path("authorizationUrl").asString()).getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals("state")) {
                state = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return new String[] {body.path("attemptToken").asString(), state};
    }

    private JsonNode callback(String[] attempt, String sub) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attemptToken", attempt[0]);
        body.put("state", attempt[1]);
        body.put("code", "stand-in." + sub + "." + "gm@example.test");
        return json(post("/api/v1/sign-in/google/callback", body));
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

    private SystemRow onlySystemOf(UUID owner) {
        List<SystemRow> rows = library.systems.values().stream().filter(row -> row.ownerId().equals(owner)).toList();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private CampaignRow onlyCampaignOf(UUID owner) {
        List<CampaignRow> rows = library.campaigns.values().stream().filter(row -> row.ownerId().equals(owner)).toList();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private JsonNode current(String session, int expectedStatus) throws Exception {
        HttpResponse<String> response = get("/api/v1/campaigns/current", session);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        return json(response);
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
        if (session != null && !session.isEmpty()) {
            request.header("X-Session-Token", session);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }
}
