package io.github.gmshield.library.web;

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
import io.github.gmshield.jobs.data.MutableClock;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.data.LibraryTestStores;
import io.github.gmshield.library.data.RollRow;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.materials.data.MaterialTestStores;
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
 * Story 6.4 (part A) without a database: every backend row of its I/O matrix. A new roll (201, its label, the formula
 * normalised and the total computed, stored only as one ciphertext); a repeat of its key in its Shield (200, the stored
 * roll, no new row) and in another Shield ({@code request.roll_key_taken}, 409); results, formulas, labels and times the
 * browser could not have made ({@code request.roll_invalid}, 400) and malformed bodies ({@code request.invalid}); the
 * log newest first a page at a time, as a second device reads it; the clear (204) and the preview's count; and the same
 * {@code request.not_found} for another owner's Shield, a Shield deleted elsewhere and a campaign being deleted, while
 * another owner's key is a new roll of the poster's own. The same rows against PostgreSQL are in
 * {@code LibraryIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GMSHIELD_MODE=test")
@ActiveProfiles({"test-mode", "unit"})
class RollFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CREDENTIAL = "gmshield-test-mode-frontend-credential-not-a-secret";
    private static final Instant START = Instant.parse("2026-11-27T12:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
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
    private InMemoryLibraryStore library;
    @Autowired
    private SystemDeletion deletion;
    @Autowired
    private Vault vault;

    private final HttpClient client = HttpClient.newHttpClient();
    private final Map<UUID, String> subs = new java.util.concurrent.ConcurrentHashMap<>();

    record Gm(UUID id, String session, UUID campaign, UUID system) {
    }

    @Test
    void aNewRollIsStoredWithItsLabelAsOneCiphertextAndARepeatAnswersTheStoredRoll() throws Exception {
        Gm gm = signUp();
        UUID shield = firstShield(gm);
        UUID key = UUID.randomUUID();
        String roll = roll(key, " 2D6 + 3 ", "[4,5]", "\" Iniciativa \"", "2026-11-27T11:59:30.123456789Z");

        HttpResponse<String> created = send(gm, "POST", rolls(gm, shield), roll);

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode stored = json(created);
        assertThat(stored.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("id", "key", "formula", "results", "total", "label", "rolledAt");
        assertThat(stored.path("key").asString()).isEqualTo(key.toString());
        assertThat(stored.path("formula").asString()).isEqualTo("2d6+3");
        assertThat(stored.path("results")).extracting(JsonNode::asInt).containsExactly(4, 5);
        assertThat(stored.path("total").asLong()).isEqualTo(12);
        assertThat(stored.path("label").asString()).isEqualTo("Iniciativa");
        assertThat(stored.path("rolledAt").asString()).isEqualTo("2026-11-27T11:59:30.123456Z");
        UUID id = UUID.fromString(stored.path("id").asString());
        RollRow row = library.rolls.get(id);
        assertThat(row.ownerId()).isEqualTo(gm.id());
        assertThat(row.shieldId()).isEqualTo(shield);
        assertThat(row.rollKey()).isEqualTo(key);
        assertThat(new String(row.content(), StandardCharsets.ISO_8859_1)).doesNotContain("2d6").doesNotContain("Inicia");
        assertThat(vault.decryptText(new Binding("roll", "content", id, gm.id()), row.content())).contains("Iniciativa");

        // The same key again in its Shield: the stored roll, nothing new.
        HttpResponse<String> again = send(gm, "POST", rolls(gm, shield), roll);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        assertThat(json(again)).isEqualTo(stored);
        assertThat(rollsOf(gm)).hasSize(1);
        // In another of the owner's Shields: refused.
        UUID other = UUID.fromString(json(send(gm, "POST", shields(gm.campaign()), "{\"name\":\"Combate\"}"))
                .path("id").asString());
        HttpResponse<String> taken = send(gm, "POST", rolls(gm, other), roll);
        assertThat(taken.statusCode()).as(taken.body()).isEqualTo(409);
        assertThat(json(taken).path("code").asString()).isEqualTo("request.roll_key_taken");
        assertThat(rollsOf(gm)).hasSize(1);

        // No label: null; the list holds the roll as it was answered.
        JsonNode plain = json(send(gm, "POST", rolls(gm, shield), roll(UUID.randomUUID(), "d20", "[20]", null,
                "2026-11-27T11:59:40Z")));
        assertThat(plain.path("label").isNull()).isTrue();
        assertThat(plain.path("formula").asString()).isEqualTo("1d20");
        JsonNode list = ok(get(gm, rolls(gm, shield)));
        assertThat(list.path("items")).containsExactly(plain, stored);
        assertThat(list.path("total").asLong()).isEqualTo(2);
        assertThat(list.has("nextCursor")).isFalse();
    }

    @Test
    void aRollTheBrowserCouldNotHaveMadeOrAMalformedBodyIsRefusedAndNothingIsStored() throws Exception {
        Gm gm = signUp();
        String path = rolls(gm, firstShield(gm));
        String now = "2026-11-27T12:00:00Z";

        List<String> invalid = List.of(
                roll(UUID.randomUUID(), "1d6", "[7]", null, now),
                roll(UUID.randomUUID(), "1d6", "[3,4]", null, now),
                roll(UUID.randomUUID(), "2d6-1d4", "[6,6]", null, now),
                roll(UUID.randomUUID(), "1d10 0", "[3]", null, now),
                roll(UUID.randomUUID(), "1d7", "[3]", null, now),
                roll(UUID.randomUUID(), "1d6", "[0]", null, now),
                roll(UUID.randomUUID(), "1d6", "[3.5]", null, now),
                roll(UUID.randomUUID(), "1d6", "[\"3\"]", null, now),
                roll(UUID.randomUUID(), "1d6", "3", null, now),
                roll(UUID.randomUUID(), "1d6", "[3]", "\"" + "x".repeat(81) + "\"", now),
                roll(UUID.randomUUID(), "1d6", "[3]", "5", now),
                roll(UUID.randomUUID(), "1d6", "[3]", null, "2026-11-27T12:05:00.001Z"),
                roll(UUID.randomUUID(), "1d6", "[3]", null, "ontem"),
                "{\"key\":\"" + UUID.randomUUID() + "\",\"formula\":\"1d6\",\"results\":[3]}",
                "{\"key\":\"" + UUID.randomUUID() + "\",\"formula\":6,\"results\":[3],\"rolledAt\":\"" + now
                        + "\"}",
                "{\"key\":\"" + UUID.randomUUID() + "\",\"results\":[3],\"rolledAt\":\"" + now + "\"}");
        for (String body : invalid) {
            HttpResponse<String> refused = send(gm, "POST", path, body);
            assertThat(refused.statusCode()).as(body).isEqualTo(400);
            assertThat(refused.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
            assertThat(json(refused).path("code").asString()).as(body).isEqualTo("request.roll_invalid");
            assertThat(json(refused).path("params").path("labelMax").asInt()).isEqualTo(80);
        }
        for (String body : List.of("not json", "null", "[]", "{}", "{\"formula\":\"1d6\",\"results\":[3]}",
                "{\"key\":\"nope\",\"formula\":\"1d6\",\"results\":[3],\"rolledAt\":\"" + now + "\"}",
                "{\"key\":5,\"formula\":\"1d6\",\"results\":[3],\"rolledAt\":\"" + now + "\"}")) {
            HttpResponse<String> refused = send(gm, "POST", path, body);
            assertThat(refused.statusCode()).as(body).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).as(body).isEqualTo("request.invalid");
        }
        assertThat(rollsOf(gm)).isEmpty();
        // At the limits: "2d6-1d4" with three results, 80 code points of label, five minutes ahead.
        assertThat(send(gm, "POST", path, roll(UUID.randomUUID(), "2d6-1d4", "[6,6,4]", "\"" + "🎲".repeat(80)
                + "\"", "2026-11-27T12:05:00Z")).statusCode()).isEqualTo(201);
        assertThat(rollsOf(gm)).hasSize(1);
    }

    @Test
    void theLogIsNewestFirstAPageAtATimeOnEveryDeviceAndAClearEmptiesIt() throws Exception {
        Gm gm = signUp();
        UUID shield = firstShield(gm);
        List<String> ids = new ArrayList<>();
        for (String at : List.of("11:00:00", "11:30:00", "10:00:00")) {
            ids.add(json(send(gm, "POST", rolls(gm, shield), roll(UUID.randomUUID(), "1d6", "[1]", null,
                    "2026-11-27T" + at + "Z"))).path("id").asString());
        }
        // Another device of the account: a second session, which reads the same log.
        Gm other = new Gm(gm.id(), secondSession(gm), gm.campaign(), gm.system());
        assertThat(other.session()).isNotEqualTo(gm.session());

        JsonNode first = ok(get(other, rolls(gm, shield) + "?limit=2"));
        assertThat(first.path("items")).extracting(item -> item.path("id").asString())
                .containsExactly(ids.get(1), ids.get(0));
        assertThat(first.path("nextCursor").asString()).isEqualTo(ids.get(0));
        assertThat(first.path("total").asLong()).isEqualTo(3);
        JsonNode second = ok(get(other, rolls(gm, shield) + "?limit=2&cursor=" + ids.get(0)));
        assertThat(second.path("items")).extracting(item -> item.path("id").asString()).containsExactly(ids.get(2));
        assertThat(second.has("nextCursor")).isFalse();
        for (String query : List.of("?limit=0", "?limit=51", "?cursor=nope")) {
            HttpResponse<String> refused = get(gm, rolls(gm, shield) + query);
            assertThat(refused.statusCode()).as(query).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).isEqualTo("request.invalid");
        }

        // The Shield's deletion preview counts them; the clear empties the log.
        assertThat(ok(get(gm, shields(gm.campaign()) + "/" + shield + "/deletion")).path("rolls").asLong())
                .isEqualTo(3);
        HttpResponse<String> cleared = send(gm, "DELETE", rolls(gm, shield), "");
        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(204);
        assertThat(cleared.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(cleared.body()).isEmpty();
        JsonNode empty = ok(get(other, rolls(gm, shield)));
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("total").asLong()).isZero();
        assertThat(rollsOf(gm)).isEmpty();
        assertThat(ok(get(gm, shields(gm.campaign()) + "/" + shield + "/deletion")).path("rolls").asLong()).isZero();
        // A cursor whose roll was cleared gives an empty page; a second clear is fine.
        assertThat(ok(get(gm, rolls(gm, shield) + "?cursor=" + ids.get(0))).path("items")).isEmpty();
        assertThat(send(gm, "DELETE", rolls(gm, shield), "").statusCode()).isEqualTo(204);
    }

    @Test
    void anotherOwnersShieldAShieldDeletedElsewhereAndACampaignBeingDeletedAreNotFound() throws Exception {
        Gm a = signUp();
        Gm b = signUp();
        UUID aShield = firstShield(a);
        UUID bShield = firstShield(b);
        UUID key = UUID.randomUUID();
        String aRoll = roll(key, "1d20", "[12]", "\"Ataque\"", "2026-11-27T11:59:00Z");
        assertThat(send(a, "POST", rolls(a, aShield), aRoll).statusCode()).isEqualTo(201);
        Map<UUID, RollRow> before = Map.copyOf(library.rolls);
        JsonNode expected = notFound(get(b, "/api/v1/campaigns/" + b.campaign() + "/shields/" + UUID.randomUUID()
                + "/rolls"));

        List<HttpResponse<String>> refused = new ArrayList<>();
        for (String campaign : List.of(a.campaign().toString(), b.campaign().toString(), UUID.randomUUID().toString(),
                "not-a-uuid")) {
            for (String shield : List.of(aShield.toString(), "123")) {
                String path = "/api/v1/campaigns/" + campaign + "/shields/" + shield + "/rolls";
                refused.add(get(b, path));
                refused.add(send(b, "POST", path, roll(UUID.randomUUID(), "1d6", "[3]", null, "2026-11-27T11:59:00Z")));
                refused.add(send(b, "POST", path, aRoll));
                refused.add(send(b, "DELETE", path, ""));
            }
        }
        for (HttpResponse<String> response : refused) {
            assertThat(withoutTraceId(notFound(response))).isEqualTo(withoutTraceId(expected));
            assertThat(response.body()).doesNotContain(aShield.toString()).doesNotContain(key.toString());
        }
        assertThat(library.rolls).isEqualTo(before);
        assertThat(ok(get(a, rolls(a, aShield))).path("total").asLong()).isEqualTo(1);
        // A's key in B's own Shield: a new roll of B's own.
        HttpResponse<String> mine = send(b, "POST", rolls(b, bShield), aRoll);
        assertThat(mine.statusCode()).as(mine.body()).isEqualTo(201);
        assertThat(rollsOf(a)).hasSize(1);
        assertThat(rollsOf(b)).singleElement()
                .satisfies(row -> assertThat(row.rollKey()).isEqualTo(key));
        // Every route needs a session.
        assertThat(client.send(HttpRequest.newBuilder(uri(rolls(a, aShield))).header("Authorization", "Bearer "
                + CREDENTIAL).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);

        // A Shield deleted elsewhere: not found at once, and its rolls go with its row.
        UUID combat = UUID.fromString(json(send(a, "POST", shields(a.campaign()), "{\"name\":\"Combate\"}"))
                .path("id").asString());
        assertThat(send(a, "POST", rolls(a, combat), roll(UUID.randomUUID(), "1d4", "[2]", null,
                "2026-11-27T11:59:00Z")).statusCode()).isEqualTo(201);
        assertThat(send(a, "DELETE", shields(a.campaign()) + "/" + combat, "").statusCode()).isEqualTo(202);
        notFound(get(a, rolls(a, combat)));
        notFound(send(a, "POST", rolls(a, combat), roll(UUID.randomUUID(), "1d4", "[2]", null, "2026-11-27T11:59:00Z")));
        notFound(send(a, "DELETE", rolls(a, combat), ""));
        deletion.sweep();
        assertThat(library.shields).doesNotContainKey(combat);
        assertThat(library.rolls.values()).noneMatch(row -> row.shieldId().equals(combat));

        // A campaign being deleted: not found on every route, and its removal takes the rolls.
        HttpResponse<String> other = send(a, "POST", "/api/v1/campaigns", "{\"name\":\"Outra\",\"systemId\":\""
                + a.system() + "\"}");
        UUID otherCampaign = UUID.fromString(json(other).path("id").asString());
        UUID otherShield = UUID.fromString(ok(get(a, shields(otherCampaign))).path("items").get(0).path("id").asString());
        String otherPath = "/api/v1/campaigns/" + otherCampaign + "/shields/" + otherShield + "/rolls";
        assertThat(send(a, "POST", otherPath, roll(UUID.randomUUID(), "1d8", "[8]", null, "2026-11-27T11:59:00Z"))
                .statusCode()).isEqualTo(201);
        assertThat(send(a, "DELETE", "/api/v1/campaigns/" + otherCampaign, "").statusCode()).isEqualTo(202);
        notFound(get(a, otherPath));
        notFound(send(a, "POST", otherPath, roll(UUID.randomUUID(), "1d8", "[8]", null, "2026-11-27T11:59:00Z")));
        notFound(send(a, "DELETE", otherPath, ""));
        deletion.sweep();
        assertThat(library.rolls.values()).noneMatch(row -> row.shieldId().equals(otherShield));
        assertThat(rollsOf(a)).singleElement()
                .satisfies(row -> assertThat(row.shieldId()).isEqualTo(aShield));
    }

    // --- rolls

    /** The account's stored rolls (the context's store holds every test's). */
    private List<RollRow> rollsOf(Gm gm) {
        return library.rolls.values().stream().filter(row -> row.ownerId().equals(gm.id())).toList();
    }

    private static String rolls(Gm gm, UUID shield) {
        return shields(gm.campaign()) + "/" + shield + "/rolls";
    }

    /** A roll's body; {@code label} is JSON (a quoted text, a number) or null to leave it out. */
    private static String roll(UUID key, String formula, String results, String label, String rolledAt) {
        return "{\"key\":\"" + key + "\",\"formula\":\"" + formula + "\",\"results\":" + results
                + (label == null ? "" : ",\"label\":" + label) + ",\"rolledAt\":\"" + rolledAt + "\"}";
    }

    // --- helpers

    private static String shields(UUID campaign) {
        return "/api/v1/campaigns/" + campaign + "/shields";
    }

    private UUID firstShield(Gm gm) throws Exception {
        return UUID.fromString(ok(get(gm, shields(gm.campaign()))).path("items").get(0).path("id").asString());
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

    private HttpResponse<String> get(Gm gm, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Session-Token", gm.session()).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(Gm gm, String method, String path, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Session-Token", gm.session()).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    /** A second sign-in of the account, as from another device: a session of its own. */
    private String secondSession(Gm gm) throws Exception {
        JsonNode started = json(post("/api/v1/sign-in/google", null));
        JsonNode again = json(post("/api/v1/sign-in/google/callback", callback(started, subs.get(gm.id()))));
        assertThat(again.path("outcome").asString()).isEqualTo("signed_in");
        return again.path("session").path("sessionToken").asString();
    }

    private static Map<String, Object> callback(JsonNode started, String sub) {
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
        return callback;
    }

    private Gm signUp() throws Exception {
        String sub = "roll-" + UUID.randomUUID();
        JsonNode started = json(post("/api/v1/sign-in/google", null));
        assertThat(json(post("/api/v1/sign-in/google/callback", callback(started, sub))).path("outcome").asString())
                .isEqualTo("username_required");
        Map<String, Object> complete = new LinkedHashMap<>();
        complete.put("attemptToken", started.path("attemptToken").asString());
        complete.put("username", "u" + sub.substring(sub.length() - 12).replace("-", "_"));
        complete.put("termsAccepted", true);
        HttpResponse<String> response = post("/api/v1/sign-in/complete", complete);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        AccountRow row = accounts.findByGoogleIdFingerprint(vault.fingerprint("account", "google_id", sub)).orElseThrow();
        String session = json(response).path("sessionToken").asString();
        subs.put(row.id(), sub);
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
