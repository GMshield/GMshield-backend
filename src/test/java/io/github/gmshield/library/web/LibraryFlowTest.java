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
import io.github.gmshield.shared.service.Ids;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The signed-in shell's first campaign (Story 1.11), without a database: the starter library of a first sign-in
 * in both languages, its rollback with the account, the library routes and their owner, and the owner-context
 * guard. Every backend row of the story's I/O matrix; the same rows against PostgreSQL are in the integration
 * tests.
 *
 * <p>Story 3.1: the list of systems and its pages, a new system, a duplicate by name and edition, the field limits,
 * an edit by version and a stale one, a system's campaigns, and another owner's system on every new route and on
 * {@code ?system=}: each route and each problem.
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
        // A starter outside a transaction is refused by the library itself, before anything is encrypted.
        UUID owner = UUID.randomUUID();
        assertThatThrownBy(() -> OwnerContext.runAs(owner, () -> libraryService.createStarter(Language.EN)))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessage("library.transaction_required");
        assertThat(library.systems.values()).noneMatch(row -> row.ownerId().equals(owner));
        assertThat(dataKeys.rows.values()).noneMatch(key -> key.ownerId().equals(owner));
        // Inside a transaction but without an owner, it is refused too.
        int systems = library.systems.size();
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> libraryService.createStarter(Language.EN)))
                .isInstanceOf(OwnerContext.NoOwnerException.class).hasMessage("owner.missing");
        assertThat(library.systems).hasSize(systems);

        // The request edge unbinds the owner once the request is over.
        Gm gm = signUp("en");
        current(gm.session(), 200);
        assertThat(OwnerContext.current()).isEmpty();
    }

    // --- Story 3.1: systems ---

    @Test
    void theListHasTheStarterSystemWithEveryFieldAndANewSystemComesFirst() throws Exception {
        Gm gm = signUp("pt-BR");
        UUID starter = onlySystemOf(gm.id()).id();

        JsonNode list = ok(get("/api/v1/systems", gm.session()));
        assertThat(list.path("items")).hasSize(1);
        JsonNode first = list.path("items").get(0);
        assertThat(first.path("id").asString()).isEqualTo(starter.toString());
        assertThat(first.path("name").asString()).isEqualTo("Sistema sem nome");
        assertThat(first.path("edition").isNull()).isTrue();
        assertThat(first.path("description").isNull()).isTrue();
        assertThat(first.path("version").asLong()).isZero();
        assertThat(list.has("nextCursor")).isFalse();

        // Matrix row "Create": D&D 5.2.1, the newest row of the list.
        pause();
        HttpResponse<String> created = send("POST", "/api/v1/systems", gm.session(),
                Map.of("name", "  D&D ", "edition", " 5.2.1 ", "description", "As regras básicas."));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode system = json(created);
        assertThat(system.path("name").asString()).isEqualTo("D&D");
        assertThat(system.path("edition").asString()).isEqualTo("5.2.1");
        assertThat(system.path("description").asString()).isEqualTo("As regras básicas.");
        assertThat(system.path("version").asLong()).isZero();
        UUID id = UUID.fromString(system.path("id").asString());
        assertThat(ok(get("/api/v1/systems", gm.session())).path("items")).extracting(item -> item.path("id").asString())
                .containsExactly(id.toString(), starter.toString());
        assertThat(ok(get("/api/v1/systems/" + id, gm.session()))).isEqualTo(system);

        // Each field is stored encrypted under its own field, and the fingerprint covers the name and the edition.
        SystemRow row = library.systems.get(id);
        for (byte[] stored : List.of(row.name(), row.edition(), row.description())) {
            assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("D&D").doesNotContain("5.2.1")
                    .doesNotContain("regras");
        }
        assertThat(vault.decryptText(new Binding("system", "edition", id, gm.id()), row.edition())).isEqualTo("5.2.1");
        assertThat(vault.decryptText(new Binding("system", "description", id, gm.id()), row.description()))
                .isEqualTo("As regras básicas.");
        assertThatThrownBy(() -> vault.decryptText(new Binding("system", "name", id, gm.id()), row.edition()))
                .isInstanceOf(RuntimeException.class);
        assertThat(row.nameFingerprint()).isEqualTo(vault.fingerprint("system", "name", "d&d\u00005.2.1"));
        // Without an edition, the name alone, as a starter system stores it.
        JsonNode bare = json(send("POST", "/api/v1/systems", gm.session(), Map.of("name", "Old School", "edition", "  ")));
        assertThat(bare.path("edition").isNull()).isTrue();
        assertThat(bare.path("description").isNull()).isTrue();
        SystemRow bareRow = library.systems.get(UUID.fromString(bare.path("id").asString()));
        assertThat(bareRow.nameFingerprint()).isEqualTo(vault.fingerprint("system", "name", "old school"));
        assertThat(bareRow.edition()).isNull();
        assertThat(bareRow.description()).isNull();
    }

    @Test
    void twoEditionsAreTwoSystemsAndTheSameNameAndEditionIsTaken() throws Exception {
        Gm gm = signUp("en");
        assertThat(send("POST", "/api/v1/systems", gm.session(), Map.of("name", "D&D", "edition", "5.2.1")).statusCode())
                .isEqualTo(201);
        // Matrix row "Other edition".
        assertThat(send("POST", "/api/v1/systems", gm.session(), Map.of("name", "D&D", "edition", "2024")).statusCode())
                .isEqualTo(201);
        int systems = library.systems.size();

        // Matrix row "Duplicate": trimmed, whatever the case.
        HttpResponse<String> duplicate = send("POST", "/api/v1/systems", gm.session(),
                Map.of("name", " d&d ", "edition", "5.2.1"));
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(json(duplicate).path("code").asString()).isEqualTo("request.system_name_taken");
        assertThat(duplicate.body()).doesNotContain("d&d");
        assertThat(library.systems).hasSize(systems);
        // No edition twice is the same system too; the starter's own name as well.
        assertThat(send("POST", "/api/v1/systems", gm.session(), Map.of("name", "D&D")).statusCode()).isEqualTo(201);
        assertThat(json(send("POST", "/api/v1/systems", gm.session(), Map.of("name", "d&D"))).path("code").asString())
                .isEqualTo("request.system_name_taken");
        assertThat(json(send("POST", "/api/v1/systems", gm.session(), Map.of("name", "UNTITLED SYSTEM"))).path("code")
                .asString()).isEqualTo("request.system_name_taken");
        // Another owner may have the same name and edition.
        Gm other = signUp("en");
        assertThat(send("POST", "/api/v1/systems", other.session(), Map.of("name", "D&D", "edition", "5.2.1")).statusCode())
                .isEqualTo(201);
    }

    @Test
    void aFieldOutsideItsLimitsIsItsOwnProblemAndNothingIsStored() throws Exception {
        Gm gm = signUp("en");
        int systems = library.systems.size();
        // Matrix row "Invalid": a blank name, 121 characters, no name; then the edition and the description.
        List<Map.Entry<String, Map<String, Object>>> refused = List.of(
                Map.entry("request.system_name_invalid", Map.of("name", "   ")),
                Map.entry("request.system_name_invalid", Map.of("name", "x".repeat(121))),
                Map.entry("request.system_name_invalid", Map.of("edition", "5e")),
                Map.entry("request.system_edition_invalid", Map.of("name", "D&D", "edition", "e".repeat(61))),
                Map.entry("request.system_description_invalid", Map.of("name", "D&D", "description", "d".repeat(2001))));
        for (Map.Entry<String, Map<String, Object>> entry : refused) {
            HttpResponse<String> response = send("POST", "/api/v1/systems", gm.session(), entry.getValue());
            assertThat(response.statusCode()).as(entry.getKey()).isEqualTo(400);
            assertThat(json(response).path("code").asString()).isEqualTo(entry.getKey());
        }
        JsonNode name = json(send("POST", "/api/v1/systems", gm.session(), Map.of("name", "")));
        assertThat(name.path("params").path("min").asInt()).isEqualTo(1);
        assertThat(name.path("params").path("max").asInt()).isEqualTo(120);
        assertThat(json(send("POST", "/api/v1/systems", gm.session(), Map.of("name", "D", "edition", "e".repeat(61))))
                .path("params").path("max").asInt()).isEqualTo(60);
        assertThat(json(send("POST", "/api/v1/systems", gm.session(), Map.of("name", "D", "description", "d".repeat(2001))))
                .path("params").path("max").asInt()).isEqualTo(2000);
        HttpResponse<String> malformed = sendText("POST", "/api/v1/systems", gm.session(), "{\"name\":");
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json(malformed).path("code").asString()).isEqualTo("request.invalid");
        assertThat(library.systems).hasSize(systems);
        // The limits themselves are fine, counted in code points ("🎲" is two chars, one code point).
        assertThat(send("POST", "/api/v1/systems", gm.session(), Map.of("name", "\uD83C\uDFB2".repeat(120),
                "edition", "e".repeat(60), "description", "d".repeat(2000))).statusCode()).isEqualTo(201);
    }

    @Test
    void anEditNamesItsVersionAStaleOneChangesNothingAndTheCurrentCampaignShowsTheNewName() throws Exception {
        Gm gm = signUp("en");
        UUID starter = onlySystemOf(gm.id()).id();
        send("POST", "/api/v1/systems", gm.session(), Map.of("name", "D&D", "edition", "5.2.1"));

        // Matrix row "Rename to own name": only the description changes.
        JsonNode described = ok(send("PATCH", "/api/v1/systems/" + starter, gm.session(),
                Map.of("version", 0, "description", "Regras da casa.")));
        assertThat(described.path("name").asString()).isEqualTo("Untitled system");
        assertThat(described.path("description").asString()).isEqualTo("Regras da casa.");
        assertThat(described.path("version").asLong()).isEqualTo(1);

        // Matrix row "Stale edit": the earlier version is refused, and nothing changes.
        HttpResponse<String> stale = send("PATCH", "/api/v1/systems/" + starter, gm.session(),
                Map.of("version", 0, "name", "Elsewhere"));
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(json(stale).path("code").asString()).isEqualTo("request.stale_version");
        assertThat(ok(get("/api/v1/systems/" + starter, gm.session())).path("name").asString()).isEqualTo("Untitled system");

        // The name and edition of another system are taken.
        HttpResponse<String> taken = send("PATCH", "/api/v1/systems/" + starter, gm.session(),
                Map.of("version", 1, "name", "d&d", "edition", "5.2.1 "));
        assertThat(taken.statusCode()).isEqualTo(409);
        assertThat(json(taken).path("code").asString()).isEqualTo("request.system_name_taken");
        // The field problems of a creation, and a missing or negative version.
        assertThat(json(send("PATCH", "/api/v1/systems/" + starter, gm.session(), Map.of("version", 1, "name", " ")))
                .path("code").asString()).isEqualTo("request.system_name_invalid");
        assertThat(json(send("PATCH", "/api/v1/systems/" + starter, gm.session(), Map.of("version", 1, "edition",
                "e".repeat(61)))).path("code").asString()).isEqualTo("request.system_edition_invalid");
        for (Map<String, Object> body : List.<Map<String, Object>>of(Map.of("name", "X"), Map.of("version", -1, "name", "X"))) {
            HttpResponse<String> response = send("PATCH", "/api/v1/systems/" + starter, gm.session(), body);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json(response).path("code").asString()).isEqualTo("request.invalid");
        }
        assertThat(library.systems.get(starter).version()).isEqualTo(1);

        // A rename, with an edition, then the edition removed by a blank one.
        JsonNode renamed = ok(send("PATCH", "/api/v1/systems/" + starter, gm.session(),
                Map.of("version", 1, "name", " Tormenta ", "edition", "20")));
        assertThat(renamed.path("name").asString()).isEqualTo("Tormenta");
        assertThat(renamed.path("edition").asString()).isEqualTo("20");
        assertThat(renamed.path("description").asString()).isEqualTo("Regras da casa.");
        assertThat(renamed.path("version").asLong()).isEqualTo(2);
        assertThat(library.systems.get(starter).nameFingerprint()).isEqualTo(vault.fingerprint("system", "name", "tormenta\u000020"));
        JsonNode plain = ok(send("PATCH", "/api/v1/systems/" + starter, gm.session(), Map.of("version", 2, "edition", "")));
        assertThat(plain.path("edition").isNull()).isTrue();
        assertThat(library.systems.get(starter).nameFingerprint()).isEqualTo(vault.fingerprint("system", "name", "tormenta"));
        // The list and the context bar show it on their next load.
        assertThat(current(gm.session(), 200).path("system").path("name").asString()).isEqualTo("Tormenta");
        assertThat(ok(get("/api/v1/systems", gm.session())).path("items").get(1).path("name").asString()).isEqualTo("Tormenta");
    }

    @Test
    void theListAndTheCampaignsComeInPagesNewestFirst() throws Exception {
        Gm gm = signUp("en");
        UUID starter = onlySystemOf(gm.id()).id();
        List<String> created = new java.util.ArrayList<>();
        for (String name : List.of("A", "B", "C")) {
            pause();
            created.addFirst(json(send("POST", "/api/v1/systems", gm.session(), Map.of("name", name))).path("id").asString());
        }

        JsonNode first = ok(get("/api/v1/systems?limit=2", gm.session()));
        assertThat(first.path("items")).extracting(item -> item.path("name").asString()).containsExactly("C", "B");
        assertThat(first.path("nextCursor").asString()).isEqualTo(created.get(1));
        JsonNode second = ok(get("/api/v1/systems?limit=2&cursor=" + first.path("nextCursor").asString(), gm.session()));
        assertThat(second.path("items")).extracting(item -> item.path("name").asString())
                .containsExactly("A", "Untitled system");
        assertThat(second.has("nextCursor")).isFalse();
        for (String query : List.of("?limit=0", "?limit=51", "?limit=abc", "?cursor=nope", "?limit=-1")) {
            HttpResponse<String> response = get("/api/v1/systems" + query, gm.session());
            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(json(response).path("code").asString()).isEqualTo("request.invalid");
        }

        // The starter system's campaign, as {id, name}; a new system has none.
        JsonNode campaigns = ok(get("/api/v1/campaigns?system=" + starter, gm.session()));
        assertThat(campaigns.path("items")).hasSize(1);
        assertThat(campaigns.path("items").get(0).path("id").asString()).isEqualTo(onlyCampaignOf(gm.id()).id().toString());
        assertThat(campaigns.path("items").get(0).path("name").asString()).isEqualTo("First campaign");
        assertThat(campaigns.path("items").get(0).has("system")).isFalse();
        assertThat(campaigns.has("nextCursor")).isFalse();
        assertThat(ok(get("/api/v1/campaigns?system=" + created.getFirst(), gm.session())).path("items")).isEmpty();
        UUID newer = Ids.newId();
        OwnerContext.runAs(gm.id(), () -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                library.insertCampaign(new CampaignRow(newer, gm.id(), starter,
                        vault.encryptText(new Binding("campaign", "name", newer, gm.id()), "Second campaign"),
                        java.time.Instant.now()))));
        JsonNode paged = ok(get("/api/v1/campaigns?limit=1&system=" + starter, gm.session()));
        assertThat(paged.path("items").get(0).path("name").asString()).isEqualTo("Second campaign");
        assertThat(paged.path("nextCursor").asString()).isEqualTo(newer.toString());
        assertThat(ok(get("/api/v1/campaigns?system=" + starter + "&cursor=" + newer, gm.session())).path("items")
                .get(0).path("name").asString()).isEqualTo("First campaign");
        for (String query : List.of("", "?limit=0&system=" + starter, "?cursor=x&system=" + starter)) {
            HttpResponse<String> response = get("/api/v1/campaigns" + query, gm.session());
            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(json(response).path("code").asString()).isEqualTo("request.invalid");
        }
    }

    @Test
    void anotherOwnersSystemIsNotFoundOnEveryNewRouteAndFilter() throws Exception {
        Gm a = signUp("en");
        Gm b = signUp("pt-BR");
        UUID aSystem = onlySystemOf(a.id()).id();
        int systems = library.systems.size();

        JsonNode expected = notFound(get("/api/v1/systems/" + UUID.randomUUID(), b.session()));
        List<HttpResponse<String>> refused = new java.util.ArrayList<>();
        refused.add(send("PATCH", "/api/v1/systems/" + aSystem, b.session(), Map.of("version", 0, "name", "Stolen")));
        refused.add(send("PATCH", "/api/v1/systems/" + UUID.randomUUID(), b.session(), Map.of("version", 0, "name", "X")));
        refused.add(send("PATCH", "/api/v1/systems/not-a-uuid", b.session(), Map.of("version", 0, "name", "X")));
        for (String system : List.of(aSystem.toString(), UUID.randomUUID().toString(), "not-a-uuid", "")) {
            refused.add(get("/api/v1/campaigns?system=" + system, b.session()));
            refused.add(get("/api/v1/materials?state=ready&system=" + system, b.session()));
        }
        for (HttpResponse<String> response : refused) {
            assertThat(withoutTraceId(notFound(response))).isEqualTo(withoutTraceId(expected));
            assertThat(response.body()).doesNotContain(aSystem.toString()).doesNotContain("Untitled system")
                    .doesNotContain("First campaign");
        }
        assertThat(library.systems).hasSize(systems);
        assertThat(library.systems.get(aSystem).version()).isZero();
        // B's list holds B's own system only.
        assertThat(ok(get("/api/v1/systems", b.session())).path("items")).extracting(item -> item.path("name").asString())
                .containsExactly("Sistema sem nome");
        // Every new route needs a session.
        for (String path : List.of("/api/v1/systems", "/api/v1/campaigns?system=" + aSystem)) {
            assertThat(get(path, null).statusCode()).isEqualTo(401);
        }
        assertThat(send("POST", "/api/v1/systems", null, Map.of("name", "X")).statusCode()).isEqualTo(401);
        assertThat(send("PATCH", "/api/v1/systems/" + aSystem, null, Map.of("version", 0, "name", "X")).statusCode())
                .isEqualTo(401);
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

    /** Ids made in the same millisecond sort randomly ({@code Ids}): the next one comes a millisecond later. */
    private static void pause() throws InterruptedException {
        Thread.sleep(2);
    }

    private HttpResponse<String> send(String method, String path, String session, Map<String, Object> body) throws Exception {
        return sendText(method, path, session, JSON.writeValueAsString(body));
    }

    private HttpResponse<String> sendText(String method, String path, String session, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + CREDENTIAL).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (session != null) {
            request.header("X-Session-Token", session);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
