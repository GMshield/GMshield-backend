package io.github.gmshield.knowledge.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.accounts.data.AccountRow;
import io.github.gmshield.accounts.data.AccountStore;
import io.github.gmshield.accounts.data.InMemoryAccountStore;
import io.github.gmshield.accounts.data.InMemorySessionStore;
import io.github.gmshield.accounts.data.InMemorySignInAttemptStore;
import io.github.gmshield.accounts.data.SessionStore;
import io.github.gmshield.accounts.data.SignInAttemptStore;
import io.github.gmshield.audit.data.AuditStore;
import io.github.gmshield.audit.data.InMemoryAuditStore;
import io.github.gmshield.conversations.data.ConversationTestStores;
import io.github.gmshield.jobs.data.JobMemoryStores;
import io.github.gmshield.knowledge.data.InMemoryKnowledgeStore;
import io.github.gmshield.knowledge.data.RecordTypeRow;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.data.LibraryTestStores;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.materials.data.MaterialTestStores;
import io.github.gmshield.shared.data.InMemoryTransactionManager;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.data.DataKeyStore;
import io.github.gmshield.vault.data.InMemoryDataKeyStore;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
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
 * Story 4.1, without a database: the core vocabulary in every system. Every row of the story's I/O matrix: a new system
 * (the GM's, and the starter system of a first sign-in) has the eight core record types from the transaction that
 * creates it, and neither exists without the other; the list answers them in the fixed core order with a record count
 * each and the total, by page; a core type cannot be renamed or removed (409, nothing changes), while a type that is not
 * core is renamed on its version and removed; another owner's system or record type is "not found" exactly like an
 * unknown one, and changes nothing; the system's deletion removes its record types. The fill of systems from before V34
 * is {@code CoreTypeBackfillTest}; the same rows against PostgreSQL are {@code OntologyIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GMSHIELD_MODE=test")
@ActiveProfiles({"test-mode", "unit"})
class OntologyFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CREDENTIAL = "gmshield-test-mode-frontend-credential-not-a-secret";
    private static final List<String> CORE = List.of("rule", "spell", "item", "creature", "character_sheet", "encounter",
            "dungeon", "note");
    private static final List<String> CORE_NAMES = List.of("rule", "spell", "item", "creature", "character sheet",
            "encounter", "dungeon", "note");

    @TestConfiguration
    @Import({LibraryTestStores.class, MaterialTestStores.class, JobMemoryStores.class, ConversationTestStores.class})
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
    private InMemoryLibraryStore library;
    @Autowired
    private InMemoryKnowledgeStore knowledge;
    @Autowired
    private InMemoryTransactionManager transactions;
    @Autowired
    private SystemDeletion deletion;
    @Autowired
    private Vault vault;

    private final HttpClient client = HttpClient.newHttpClient();

    /** A signed-up account: its id, its session token, its starter system. */
    record Gm(UUID id, String session, UUID system) {
    }

    @BeforeEach
    void noFailures() {
        library.failNextCampaign.set(false);
        knowledge.failNextInsert.set(false);
    }

    // --- new system

    @Test
    void theStarterSystemOfAFirstSignInHasTheEightCoreTypesInTheFixedOrder() throws Exception {
        Gm gm = signUp();

        JsonNode page = ok(get("/api/v1/systems/" + gm.system() + "/record-types", gm.session()));

        assertThat(page.path("total").asLong()).isEqualTo(8);
        assertThat(cores(page)).containsExactlyElementsOf(CORE);
        List<String> names = new ArrayList<>();
        page.path("items").forEach(item -> {
            names.add(item.path("name").asString());
            assertThat(item.path("records").asLong()).isZero();
            assertThat(item.path("version").asLong()).isZero();
            assertThat(item.path("id").asString()).isNotBlank();
        });
        assertThat(names).containsExactlyElementsOf(CORE_NAMES);
        assertThat(page.has("nextCursor")).isFalse();
    }

    @Test
    void aNewSystemHasTheEightCoreTypesStoredEncryptedFromItsCreation() throws Exception {
        Gm gm = signUp();
        UUID system = createSystem(gm, "Tormenta20");

        List<RecordTypeRow> rows = knowledge.recordTypes.values().stream().filter(row -> row.systemId().equals(system))
                .toList();
        assertThat(rows).hasSize(8).allSatisfy(row -> {
            assertThat(row.ownerId()).isEqualTo(gm.id());
            String name = CORE_NAMES.get(CORE.indexOf(row.core()));
            // The name column holds no plaintext: a ciphertext bound to the row, and the fingerprint of the unique form.
            assertThat(new String(row.name(), StandardCharsets.ISO_8859_1)).doesNotContain(name);
            assertThat(vault.decryptText(new Binding("record_type", "name", row.id(), gm.id()), row.name()))
                    .isEqualTo(name);
            assertThat(row.nameFingerprint()).isEqualTo(vault.fingerprint("record_type", "name", Library.uniqueForm(name)));
        });
        assertThat(rows).extracting(RecordTypeRow::core).containsExactlyInAnyOrderElementsOf(CORE);
        assertThat(cores(ok(get("/api/v1/systems/" + system + "/record-types", gm.session()))))
                .containsExactlyElementsOf(CORE);
    }

    @Test
    void whenTheCoreTypesCannotBeStoredTheSystemIsNotCreatedEither() throws Exception {
        Gm gm = signUp();
        int systems = library.systems.size();
        int rollbacks = transactions.rollbacks.get();
        knowledge.failNextInsert.set(true);

        HttpResponse<String> failed = send("POST", "/api/v1/systems", gm.session(), Map.of("name", "Perdido"));

        assertThat(failed.statusCode()).isEqualTo(500);
        assertThat(json(failed).path("code").asString()).isEqualTo("server.unexpected");
        assertThat(transactions.rollbacks.get()).isEqualTo(rollbacks + 1);
        assertThat(library.systems).hasSize(systems);
        // The name is free: the next try creates it, with its core types.
        UUID system = createSystem(gm, "Perdido");
        assertThat(knowledge.recordTypes.values()).filteredOn(row -> row.systemId().equals(system)).hasSize(8);
    }

    @Test
    void whenTheStarterLibraryRollsBackItsCoreTypesGoWithIt() throws Exception {
        String sub = "kn-" + UUID.randomUUID();
        String attempt = identified(sub);
        int recordTypes = knowledge.recordTypes.size();
        int systems = library.systems.size();
        int rollbacks = transactions.rollbacks.get();
        library.failNextCampaign.set(true);

        HttpResponse<String> failed = complete(attempt, "k" + sub.substring(sub.length() - 12).replace("-", "_"));

        assertThat(failed.statusCode()).isEqualTo(500);
        assertThat(transactions.rollbacks.get()).isEqualTo(rollbacks + 1);
        assertThat(accounts.findByGoogleIdFingerprint(vault.fingerprint("account", "google_id", sub))).isEmpty();
        assertThat(library.systems).hasSize(systems);
        // The eight seeded in the failed transaction went with it.
        assertThat(knowledge.recordTypes).hasSize(recordTypes);
    }

    // --- list

    @Test
    void theListIsPagedByOffsetAndRefusesAMalformedPage() throws Exception {
        Gm gm = signUp();
        String path = "/api/v1/systems/" + gm.system() + "/record-types";

        JsonNode last = ok(get(path + "?limit=3&offset=6", gm.session()));
        assertThat(cores(last)).containsExactly("dungeon", "note");
        assertThat(last.path("total").asLong()).isEqualTo(8);
        assertThat(cores(ok(get(path + "?limit=3", gm.session())))).containsExactly("rule", "spell", "item");
        assertThat(ok(get(path + "?offset=8", gm.session())).path("items")).isEmpty();
        for (String bad : List.of("?limit=0", "?limit=51", "?limit=x", "?offset=-1", "?offset=100001", "?offset=01")) {
            HttpResponse<String> refused = get(path + bad, gm.session());
            assertThat(refused.statusCode()).as(bad).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).isEqualTo("request.invalid");
        }
    }

    @Test
    void aRecordTypeOpensByItsId() throws Exception {
        Gm gm = signUp();
        JsonNode spell = item(gm, "spell");

        JsonNode read = ok(get("/api/v1/record-types/" + spell.path("id").asString(), gm.session()));

        assertThat(read).isEqualTo(spell);
    }

    // --- core types are fixed

    @Test
    void aCoreTypeIsNeitherRenamedNorRemovedAndNothingChanges() throws Exception {
        Gm gm = signUp();
        JsonNode note = item(gm, "note");
        String path = "/api/v1/record-types/" + note.path("id").asString();
        Map<UUID, RecordTypeRow> before = Map.copyOf(knowledge.recordTypes);

        HttpResponse<String> renamed = send("PATCH", path, gm.session(), Map.of("version", 0, "name", "Anotação"));
        HttpResponse<String> removed = send("DELETE", path, gm.session(), null);

        for (HttpResponse<String> refused : List.of(renamed, removed)) {
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
            assertThat(json(refused).path("code").asString()).isEqualTo("ontology.core_record_type");
            assertThat(json(refused).path("category").asString()).isEqualTo("ontology");
            assertThat(refused.body()).doesNotContain("note");
        }
        assertThat(knowledge.recordTypes).isEqualTo(before);
        assertThat(ok(get(path, gm.session()))).isEqualTo(note);
        // Whatever the body: a stale version or a bad name on a core type is still the core refusal.
        HttpResponse<String> stale = send("PATCH", path, gm.session(), Map.of("version", 7, "name", ""));
        assertThat(json(stale).path("code").asString()).isEqualTo("ontology.core_record_type");
    }

    // --- a type that is not core (Story 4.4 approves them; stored directly here)

    @Test
    void aTypeThatIsNotCoreIsRenamedOnItsVersionToAFreeNameAndRemoved() throws Exception {
        Gm gm = signUp();
        UUID vehicle = notCore(gm, gm.system(), "Veículo");
        String path = "/api/v1/record-types/" + vehicle;

        JsonNode read = ok(get(path, gm.session()));
        assertThat(read.path("name").asString()).isEqualTo("Veículo");
        assertThat(read.path("core").isNull()).isTrue();
        assertThat(cores(ok(get("/api/v1/systems/" + gm.system() + "/record-types", gm.session()))))
                .endsWith("note", "null");

        JsonNode renamed = ok(send("PATCH", path, gm.session(), Map.of("version", 0, "name", "  Montaria ")));
        assertThat(renamed.path("name").asString()).isEqualTo("Montaria");
        assertThat(renamed.path("version").asLong()).isEqualTo(1);
        assertThat(renamed.path("records").asLong()).isZero();
        RecordTypeRow stored = knowledge.recordTypes.get(vehicle);
        assertThat(new String(stored.name(), StandardCharsets.UTF_8)).doesNotContain("Montaria");
        assertThat(stored.nameFingerprint()).isEqualTo(vault.fingerprint("record_type", "name", Library.uniqueForm("Montaria")));

        assertProblem(send("PATCH", path, gm.session(), Map.of("version", 0, "name", "Corcel")), 409,
                "request.stale_version");
        assertProblem(send("PATCH", path, gm.session(), Map.of("version", 1, "name", " SPELL ")), 409,
                "ontology.record_type_name_taken");
        assertProblem(send("PATCH", path, gm.session(), Map.of("version", 1, "name", "Character Sheet")), 409,
                "ontology.record_type_name_taken");
        JsonNode invalid = assertProblem(send("PATCH", path, gm.session(), Map.of("version", 1, "name", "   ")), 400,
                "ontology.record_type_name_invalid");
        assertThat(invalid.path("params").path("max").asInt()).isEqualTo(120);
        assertProblem(send("PATCH", path, gm.session(), Map.of("version", 1, "name", "x".repeat(121))), 400,
                "ontology.record_type_name_invalid");
        assertProblem(send("PATCH", path, gm.session(), Map.of("name", "Corcel")), 400, "request.invalid");
        assertProblem(send("PATCH", path, gm.session(), Map.of("version", 1)), 400, "request.invalid");
        assertThat(knowledge.recordTypes.get(vehicle)).isEqualTo(stored);

        HttpResponse<String> removed = send("DELETE", path, gm.session(), null);
        assertThat(removed.statusCode()).isEqualTo(204);
        assertThat(knowledge.recordTypes).doesNotContainKey(vehicle);
        assertProblem(get(path, gm.session()), 404, "request.not_found");
        assertProblem(send("DELETE", path, gm.session(), null), 404, "request.not_found");
        assertThat(ok(get("/api/v1/systems/" + gm.system() + "/record-types", gm.session())).path("total").asLong())
                .isEqualTo(8);
    }

    // --- another owner

    @Test
    void anotherOwnersSystemOrRecordTypeIsNotFoundLikeAnUnknownOneAndNothingChanges() throws Exception {
        Gm a = signUp();
        Gm b = signUp();
        String spell = item(a, "spell").path("id").asString();
        UUID vehicle = notCore(a, a.system(), "Veículo");
        Map<UUID, RecordTypeRow> before = Map.copyOf(knowledge.recordTypes);
        String random = UUID.randomUUID().toString();

        for (String[] request : List.of(
                new String[] {"GET", "/api/v1/systems/%s/record-types", a.system().toString()},
                new String[] {"GET", "/api/v1/record-types/%s", spell},
                new String[] {"PATCH", "/api/v1/record-types/%s", spell},
                new String[] {"DELETE", "/api/v1/record-types/%s", spell},
                new String[] {"GET", "/api/v1/record-types/%s", vehicle.toString()},
                new String[] {"PATCH", "/api/v1/record-types/%s", vehicle.toString()},
                new String[] {"DELETE", "/api/v1/record-types/%s", vehicle.toString()})) {
            Map<String, Object> body = request[0].equals("PATCH") ? Map.of("version", 0, "name", "Tomado") : null;
            HttpResponse<String> theirs = send(request[0], request[1].formatted(request[2]), b.session(), body);
            HttpResponse<String> unknown = send(request[0], request[1].formatted(random), b.session(), body);
            HttpResponse<String> malformed = send(request[0], request[1].formatted("not-an-id"), b.session(), body);
            String what = request[0] + " " + request[1];
            assertThat(withoutTraceId(assertProblem(theirs, 404, "request.not_found"))).as(what)
                    .isEqualTo(withoutTraceId(assertProblem(unknown, 404, "request.not_found")));
            assertProblem(malformed, 404, "request.not_found");
            assertThat(theirs.body()).doesNotContain(request[2]).doesNotContain(a.system().toString());
        }
        assertThat(knowledge.recordTypes).isEqualTo(before);
        assertThat(ok(get("/api/v1/record-types/" + vehicle, a.session())).path("name").asString()).isEqualTo("Veículo");
    }

    @Test
    void withoutASessionTheRoutesAreRefusedBeforeAnyController() throws Exception {
        Gm gm = signUp();
        String spell = item(gm, "spell").path("id").asString();

        assertThat(get("/api/v1/systems/" + gm.system() + "/record-types", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/record-types/" + spell, null).statusCode()).isEqualTo(401);
        assertThat(send("PATCH", "/api/v1/record-types/" + spell, null, Map.of("version", 0, "name", "X")).statusCode())
                .isEqualTo(401);
        assertThat(send("DELETE", "/api/v1/record-types/" + spell, null, null).statusCode()).isEqualTo(401);
    }

    // --- deletion

    @Test
    void aDeletedSystemsRecordTypesAreNotFoundAtOnceAndRemovedWithIt() throws Exception {
        Gm gm = signUp();
        UUID system = createSystem(gm, "Passageiro");
        String spell = ok(get("/api/v1/systems/" + system + "/record-types", gm.session())).path("items").get(1)
                .path("id").asString();
        UUID vehicle = notCore(gm, system, "Veículo");

        assertThat(send("DELETE", "/api/v1/systems/" + system, gm.session(), null).statusCode()).isEqualTo(202);

        assertProblem(get("/api/v1/systems/" + system + "/record-types", gm.session()), 404, "request.not_found");
        assertProblem(get("/api/v1/record-types/" + spell, gm.session()), 404, "request.not_found");
        assertProblem(send("PATCH", "/api/v1/record-types/" + vehicle, gm.session(), Map.of("version", 0, "name", "X")),
                404, "request.not_found");
        assertProblem(send("DELETE", "/api/v1/record-types/" + vehicle, gm.session(), null), 404, "request.not_found");
        assertThat(knowledge.recordTypes.values()).filteredOn(row -> row.systemId().equals(system)).hasSize(9);

        assertThat(deletion.sweep()).isPositive();

        assertThat(knowledge.recordTypes.values()).noneMatch(row -> row.systemId().equals(system));
        assertThat(knowledge.recordTypes.values()).filteredOn(row -> row.systemId().equals(gm.system())).hasSize(8);
    }

    // --- helpers

    private static List<String> cores(JsonNode page) {
        List<String> cores = new ArrayList<>();
        page.path("items").forEach(item -> cores.add(item.path("core").isNull() ? "null" : item.path("core").asString()));
        return cores;
    }

    private JsonNode item(Gm gm, String core) throws Exception {
        JsonNode page = ok(get("/api/v1/systems/" + gm.system() + "/record-types", gm.session()));
        for (JsonNode item : page.path("items")) {
            if (core.equals(item.path("core").asString())) {
                return item;
            }
        }
        throw new AssertionError("no " + core);
    }

    /** A record type that is not core, stored as Story 4.4's approval will: its id. */
    private UUID notCore(Gm gm, UUID system, String name) {
        UUID id = Ids.newId();
        OwnerContext.runAs(gm.id(), () -> knowledge.insertRecordTypes(List.of(new RecordTypeRow(id, gm.id(), system, null,
                "t" + id.toString().substring(0, 8), Ids.newId(),
                vault.encryptText(new Binding("record_type", "name", id, gm.id()), name),
                vault.fingerprint("record_type", "name", Library.uniqueForm(name)), 0, Instant.now()))));
        return id;
    }

    private UUID createSystem(Gm gm, String name) throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/systems", gm.session(), Map.of("name", name));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(json(created).path("id").asString());
    }

    private Gm signUp() throws Exception {
        String sub = "kn-" + UUID.randomUUID();
        HttpResponse<String> response = complete(identified(sub), "k" + sub.substring(sub.length() - 12).replace("-", "_"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        AccountRow row = accounts.findByGoogleIdFingerprint(vault.fingerprint("account", "google_id", sub)).orElseThrow();
        String session = json(response).path("sessionToken").asString();
        UUID system = UUID.fromString(ok(get("/api/v1/campaigns/current", session)).path("system").path("id").asString());
        return new Gm(row.id(), session, system);
    }

    /** An attempt whose callback identified a new Google identity: the username step comes next. */
    private String identified(String sub) throws Exception {
        JsonNode started = json(send("POST", "/api/v1/sign-in/google", null, null));
        String state = null;
        for (String pair : URI.create(started.path("authorizationUrl").asString()).getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals("state")) {
                state = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attemptToken", started.path("attemptToken").asString());
        body.put("state", state);
        body.put("code", "stand-in." + sub + "." + "gm@example.test");
        assertThat(json(send("POST", "/api/v1/sign-in/google/callback", null, body)).path("outcome").asString())
                .isEqualTo("username_required");
        return started.path("attemptToken").asString();
    }

    private HttpResponse<String> complete(String attempt, String username) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attemptToken", attempt);
        body.put("username", username);
        body.put("termsAccepted", true);
        body.put("language", "pt-BR");
        return send("POST", "/api/v1/sign-in/complete", null, body);
    }

    private static JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        return json(response);
    }

    private static JsonNode assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode problem = json(response);
        assertThat(problem.path("code").asString()).as(response.body()).isEqualTo(code);
        return problem;
    }

    private static Map<String, Object> withoutTraceId(JsonNode problem) {
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = JSON.convertValue(problem, Map.class);
        fields.remove("traceId");
        return fields;
    }

    private HttpResponse<String> get(String path, String session) throws Exception {
        return send("GET", path, session, null);
    }

    private HttpResponse<String> send(String method, String path, String session, Map<String, Object> body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + CREDENTIAL);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        }
        if (session != null) {
            request.header("X-Session-Token", session);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }
}
