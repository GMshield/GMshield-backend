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
import io.github.gmshield.library.data.ShieldRow;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.materials.data.MaterialTestStores;
import io.github.gmshield.vault.data.DataKeyStore;
import io.github.gmshield.vault.data.InMemoryDataKeyStore;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.IntegrityException;
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
 * Story 6.1 (part A) without a database: every backend row of its I/O matrix. A first sign-in and a new campaign each
 * have one unnamed Shield; a campaign without one (the deploy window) gets it when its Shields are listed; a new Shield,
 * its name's limits and its uniqueness within the campaign; a read; a rename by version and a stale one; the list's
 * pages, oldest first; and the same {@code request.not_found} for another owner's campaign or Shield, an unknown or
 * malformed id, a Shield of another campaign and a campaign being deleted. The same rows against PostgreSQL are in
 * {@code LibraryIntegrationTest}; the deletions of Shields with their campaign are in {@link CampaignDeletionFlowTest}
 * and {@link SystemDeletionFlowTest}.
 *
 * <p>Part B: the deletion preview ({@code notes} and {@code rolls} 0, {@code last} for the campaign's only Shield), the
 * deletion (from Story 6.2 part B the recorded process: 202, the list one shorter at once, its name free at once, its
 * row gone after the pass), the refusal of the last Shield ({@code request.last_shield}, 409, the row kept), an already
 * deleted Shield (404), and the same not found on both routes. The deletion of a Shield holding notes is
 * {@code ShieldNoteFlowTest}'s. Story 6.4: the preview counts the Shield's stored rolls, and the deletion removes them
 * (the rolls' own rows are {@link RollFlowTest}'s).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GMSHIELD_MODE=test")
@ActiveProfiles({"test-mode", "unit"})
class ShieldFlowTest {

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

    record Gm(UUID id, String session, UUID campaign, UUID system) {
    }

    @Test
    void aFirstSignInAndANewCampaignEachHaveOneUnnamedShield() throws Exception {
        Gm gm = signUp();

        // Stored with the starter campaign, in its transaction.
        assertThat(library.shields.values()).filteredOn(row -> row.ownerId().equals(gm.id())).singleElement()
                .satisfies(row -> {
                    assertThat(row.campaignId()).isEqualTo(gm.campaign());
                    assertThat(row.unnamed()).isTrue();
                    assertThat(row.nameFingerprint()).isNull();
                });
        JsonNode list = ok(get(gm, shields(gm.campaign())));
        assertThat(list.path("total").asLong()).isEqualTo(1);
        assertThat(list.has("nextCursor")).isFalse();
        JsonNode first = list.path("items").get(0);
        assertThat(first.path("name").isNull()).isTrue();
        assertThat(first.path("version").asLong()).isZero();
        assertThat(ok(get(gm, shields(gm.campaign()) + "/" + first.path("id").asString())))
                .isEqualTo(first);

        HttpResponse<String> created = send(gm, "POST", "/api/v1/campaigns", "{\"name\":\"Mesa de sexta\",\"systemId\":\""
                + gm.system() + "\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID campaign = UUID.fromString(json(created).path("id").asString());
        assertThat(library.shields.values()).filteredOn(row -> row.campaignId().equals(campaign)).singleElement()
                .satisfies(row -> assertThat(row.unnamed()).isTrue());
        JsonNode itsList = ok(get(gm, shields(campaign)));
        assertThat(itsList.path("items")).singleElement().satisfies(item -> assertThat(item.path("name").isNull()).isTrue());
    }

    @Test
    void aCampaignWithoutAShieldGetsItsUnnamedOneWhenItsShieldsAreListed() throws Exception {
        Gm gm = signUp();
        library.shields.values().removeIf(row -> row.campaignId().equals(gm.campaign()));

        JsonNode list = ok(get(gm, shields(gm.campaign())));
        JsonNode again = ok(get(gm, shields(gm.campaign())));

        assertThat(list.path("items")).singleElement().satisfies(item -> assertThat(item.path("name").isNull()).isTrue());
        assertThat(again).isEqualTo(list);
        assertThat(library.shields.values()).filteredOn(row -> row.campaignId().equals(gm.campaign())).hasSize(1);
    }

    @Test
    void aNewShieldIsStoredEncryptedReadBackAndListedOldestFirst() throws Exception {
        Gm gm = signUp();
        UUID first = firstShield(gm);

        HttpResponse<String> created = send(gm, "POST", shields(gm.campaign()), "{\"name\":\"  Combate  \"}");

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode shield = json(created);
        assertThat(shield.path("name").asString()).isEqualTo("Combate");
        assertThat(shield.path("version").asLong()).isZero();
        UUID id = UUID.fromString(shield.path("id").asString());
        ShieldRow row = library.shields.get(id);
        assertThat(row.ownerId()).isEqualTo(gm.id());
        assertThat(row.campaignId()).isEqualTo(gm.campaign());
        assertThat(new String(row.name(), StandardCharsets.ISO_8859_1)).doesNotContain("Combate");
        assertThat(vault.decryptText(new Binding("shield", "name", id, gm.id()), row.name())).isEqualTo("Combate");
        assertThat(row.nameFingerprint()).isEqualTo(vault.fingerprint("shield", "name", "combate"));
        // Bound to its own row, field, entity and owner only.
        for (Binding other : List.of(new Binding("shield", "name", first, gm.id()),
                new Binding("campaign", "name", id, gm.id()), new Binding("shield", "name", id, UUID.randomUUID()))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> vault.decryptText(other, row.name()))
                    .isInstanceOf(IntegrityException.class);
        }
        assertThat(ok(get(gm, shields(gm.campaign()) + "/" + id))).isEqualTo(shield);
        JsonNode list = ok(get(gm, shields(gm.campaign())));
        assertThat(list.path("items")).extracting(item -> item.path("id").asString())
                .containsExactly(first.toString(), id.toString());
        assertThat(list.path("total").asLong()).isEqualTo(2);
    }

    @Test
    void aNameOutsideItsLimitsOrTakenInTheCampaignIsRefusedAndNothingIsStored() throws Exception {
        Gm gm = signUp();
        assertThat(send(gm, "POST", shields(gm.campaign()), "{\"name\":\"Combate\"}").statusCode()).isEqualTo(201);
        int stored = library.shields.size();

        for (String body : List.of("{\"name\":\"   \"}", "{\"name\":\"\"}", "{}", "{\"name\":\"" + "é".repeat(121) + "\"}")) {
            HttpResponse<String> refused = send(gm, "POST", shields(gm.campaign()), body);
            assertThat(refused.statusCode()).as(body).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).isEqualTo("request.shield_name_invalid");
            assertThat(json(refused).path("params").path("min").asInt()).isEqualTo(1);
            assertThat(json(refused).path("params").path("max").asInt()).isEqualTo(120);
        }
        // 120 code points of a character outside the BMP fit.
        assertThat(send(gm, "POST", shields(gm.campaign()), "{\"name\":\"" + "🎲".repeat(120) + "\"}")
                .statusCode()).isEqualTo(201);
        HttpResponse<String> taken = send(gm, "POST", shields(gm.campaign()), "{\"name\":\" COMBATE \"}");
        assertThat(taken.statusCode()).isEqualTo(409);
        assertThat(json(taken).path("code").asString()).isEqualTo("request.shield_name_taken");
        assertThat(taken.body()).doesNotContain("ombate");
        assertThat(send(gm, "POST", shields(gm.campaign()), "not json").statusCode()).isEqualTo(400);
        assertThat(library.shields).hasSize(stored + 1);
        // The same name is free in another campaign.
        HttpResponse<String> other = send(gm, "POST", "/api/v1/campaigns", "{\"name\":\"Outra\",\"systemId\":\""
                + gm.system() + "\"}");
        UUID campaign = UUID.fromString(json(other).path("id").asString());
        assertThat(send(gm, "POST", shields(campaign), "{\"name\":\"combate\"}").statusCode()).isEqualTo(201);
    }

    @Test
    void aRenameNamesItsVersionAndAStaleOrTakenOneChangesNothing() throws Exception {
        Gm gm = signUp();
        UUID first = firstShield(gm);
        UUID combat = UUID.fromString(json(send(gm, "POST", shields(gm.campaign()), "{\"name\":\"Combate\"}"))
                .path("id").asString());
        String path = shields(gm.campaign()) + "/" + first;

        // The unnamed first Shield gets a name of its own.
        JsonNode renamed = ok(send(gm, "PATCH", path, "{\"version\":0,\"name\":\" Exploração \"}"));
        assertThat(renamed.path("name").asString()).isEqualTo("Exploração");
        assertThat(renamed.path("version").asLong()).isEqualTo(1);
        assertThat(library.shields.get(first).nameFingerprint()).isEqualTo(vault.fingerprint("shield", "name", "exploração"));
        assertThat(vault.decryptText(new Binding("shield", "name", first, gm.id()), library.shields.get(first).name()))
                .isEqualTo("Exploração");
        // Its own name in another case is fine.
        assertThat(ok(send(gm, "PATCH", path, "{\"version\":1,\"name\":\"EXPLORAÇÃO\"}")).path("version").asLong())
                .isEqualTo(2);

        HttpResponse<String> stale = send(gm, "PATCH", path, "{\"version\":1,\"name\":\"Outro\"}");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(json(stale).path("code").asString()).isEqualTo("request.stale_version");
        HttpResponse<String> taken = send(gm, "PATCH", path, "{\"version\":2,\"name\":\"combate\"}");
        assertThat(taken.statusCode()).isEqualTo(409);
        assertThat(json(taken).path("code").asString()).isEqualTo("request.shield_name_taken");
        HttpResponse<String> invalid = send(gm, "PATCH", path, "{\"version\":2,\"name\":\" \"}");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(json(invalid).path("code").asString()).isEqualTo("request.shield_name_invalid");
        for (String body : List.of("{\"name\":\"X\"}", "{\"version\":-1,\"name\":\"X\"}", "null")) {
            HttpResponse<String> refused = send(gm, "PATCH", path, body);
            assertThat(refused.statusCode()).as(body).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).isEqualTo("request.invalid");
        }
        assertThat(library.shields.get(first).version()).isEqualTo(2);
        assertThat(ok(get(gm, path)).path("name").asString()).isEqualTo("EXPLORAÇÃO");
        assertThat(library.shields.get(combat).version()).isZero();
        // With the first one renamed, the campaign has no unnamed Shield; the list shows the current names.
        assertThat(ok(get(gm, shields(gm.campaign()))).path("items")).extracting(item -> item.path("name").asString())
                .containsExactly("EXPLORAÇÃO", "Combate");
    }

    @Test
    void theListComesInPagesOldestFirstByCursorOrOffset() throws Exception {
        Gm gm = signUp();
        List<String> ids = new ArrayList<>(List.of(firstShield(gm).toString()));
        for (String name : List.of("A", "B", "C")) {
            Thread.sleep(2);
            ids.add(json(send(gm, "POST", shields(gm.campaign()), "{\"name\":\"" + name + "\"}")).path("id").asString());
        }

        JsonNode first = ok(get(gm, shields(gm.campaign()) + "?limit=3"));
        assertThat(first.path("items")).extracting(item -> item.path("id").asString()).containsExactlyElementsOf(ids.subList(0, 3));
        assertThat(first.path("nextCursor").asString()).isEqualTo(ids.get(2));
        assertThat(first.path("total").asLong()).isEqualTo(4);
        JsonNode second = ok(get(gm, shields(gm.campaign()) + "?limit=3&cursor=" + ids.get(2)));
        assertThat(second.path("items")).extracting(item -> item.path("id").asString()).containsExactly(ids.get(3));
        assertThat(second.has("nextCursor")).isFalse();
        JsonNode numbered = ok(get(gm, shields(gm.campaign()) + "?limit=2&offset=2"));
        assertThat(numbered.path("items")).extracting(item -> item.path("name").asString()).containsExactly("B", "C");
        assertThat(numbered.path("total").asLong()).isEqualTo(4);
        for (String query : List.of("?limit=0", "?limit=51", "?cursor=nope", "?offset=-1",
                "?offset=1&cursor=" + ids.get(0))) {
            HttpResponse<String> refused = get(gm, shields(gm.campaign()) + query);
            assertThat(refused.statusCode()).as(query).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).isEqualTo("request.invalid");
        }
    }

    @Test
    void anotherOwnersOrAnUnknownCampaignOrShieldAShieldOfAnotherCampaignAndADeletingCampaignAreNotFound()
            throws Exception {
        Gm a = signUp();
        Gm b = signUp();
        UUID aShield = firstShield(a);
        UUID bShield = firstShield(b);
        HttpResponse<String> other = send(b, "POST", "/api/v1/campaigns", "{\"name\":\"Outra\",\"systemId\":\""
                + b.system() + "\"}");
        UUID bOther = UUID.fromString(json(other).path("id").asString());
        UUID bOtherShield = UUID.fromString(ok(get(b, shields(bOther))).path("items").get(0).path("id").asString());
        Map<UUID, ShieldRow> before = Map.copyOf(library.shields);
        JsonNode expected = notFound(get(b, shields(UUID.randomUUID())));

        List<HttpResponse<String>> refused = new ArrayList<>();
        for (String campaign : List.of(a.campaign().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            refused.add(get(b, "/api/v1/campaigns/" + campaign + "/shields"));
            refused.add(send(b, "POST", "/api/v1/campaigns/" + campaign + "/shields", "{\"name\":\"Tomado\"}"));
            refused.add(get(b, "/api/v1/campaigns/" + campaign + "/shields/" + aShield));
            refused.add(send(b, "PATCH", "/api/v1/campaigns/" + campaign + "/shields/" + aShield,
                    "{\"version\":0,\"name\":\"Tomado\"}"));
            refused.add(get(b, "/api/v1/campaigns/" + campaign + "/shields/" + aShield + "/deletion"));
            refused.add(send(b, "DELETE", "/api/v1/campaigns/" + campaign + "/shields/" + aShield, ""));
        }
        // B's own campaign with A's Shield, B's Shield of its other campaign, an unknown and a malformed Shield id.
        for (String shield : List.of(aShield.toString(), bOtherShield.toString(), UUID.randomUUID().toString(), "123")) {
            refused.add(get(b, shields(b.campaign()) + "/" + shield));
            refused.add(send(b, "PATCH", shields(b.campaign()) + "/" + shield, "{\"version\":0,\"name\":\"Tomado\"}"));
            refused.add(get(b, shields(b.campaign()) + "/" + shield + "/deletion"));
            refused.add(send(b, "DELETE", shields(b.campaign()) + "/" + shield, ""));
        }
        for (HttpResponse<String> response : refused) {
            assertThat(withoutTraceId(notFound(response))).isEqualTo(withoutTraceId(expected));
            assertThat(response.body()).doesNotContain(a.campaign().toString()).doesNotContain(aShield.toString());
        }
        assertThat(library.shields).isEqualTo(before);
        assertThat(ok(get(a, shields(a.campaign()) + "/" + aShield)).path("version").asLong()).isZero();
        assertThat(ok(get(b, shields(b.campaign()))).path("items")).extracting(item -> item.path("id").asString())
                .containsExactly(bShield.toString());
        // Every route needs a session.
        assertThat(client.send(HttpRequest.newBuilder(uri(shields(a.campaign()))).header("Authorization", "Bearer "
                + CREDENTIAL).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);

        // A campaign being deleted: not found on every Shield route, from the commit of the GM's delete.
        assertThat(send(b, "DELETE", "/api/v1/campaigns/" + bOther, "").statusCode()).isEqualTo(202);
        notFound(get(b, shields(bOther)));
        notFound(send(b, "POST", shields(bOther), "{\"name\":\"Tarde\"}"));
        notFound(get(b, shields(bOther) + "/" + bOtherShield));
        notFound(send(b, "PATCH", shields(bOther) + "/" + bOtherShield, "{\"version\":0,\"name\":\"Tarde\"}"));
        notFound(get(b, shields(bOther) + "/" + bOtherShield + "/deletion"));
        notFound(send(b, "DELETE", shields(bOther) + "/" + bOtherShield, ""));
        // Its removal takes its Shields before its row.
        deletion.sweep();
        assertThat(library.campaigns).doesNotContainKey(bOther);
        assertThat(library.shields).doesNotContainKey(bOtherShield).containsKeys(aShield, bShield);
    }

    @Test
    void theDeletionPreviewNamesWhatGoesAndWhetherItIsTheLastShieldAndADeletionRefusesTheLastOne() throws Exception {
        Gm gm = signUp();
        UUID first = firstShield(gm);
        UUID combat = UUID.fromString(json(send(gm, "POST", shields(gm.campaign()), "{\"name\":\"Combate\"}"))
                .path("id").asString());

        JsonNode preview = ok(get(gm, shields(gm.campaign()) + "/" + combat + "/deletion"));
        assertThat(preview.path("notes").asLong()).isZero();
        assertThat(preview.path("rolls").asLong()).isZero();
        assertThat(preview.path("last").asBoolean()).isFalse();
        assertThat(preview.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("notes", "rolls", "last");
        assertThat(ok(get(gm, shields(gm.campaign()) + "/" + first + "/deletion")).path("last").asBoolean()).isFalse();

        HttpResponse<String> deleted = send(gm, "DELETE", shields(gm.campaign()) + "/" + combat, "");
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(202);
        assertThat(deleted.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(deleted.body()).isEmpty();
        JsonNode list = ok(get(gm, shields(gm.campaign())));
        assertThat(list.path("total").asLong()).isEqualTo(1);
        assertThat(list.path("items")).extracting(item -> item.path("id").asString()).containsExactly(first.toString());
        // Gone: not found on every route, a second deletion included.
        notFound(get(gm, shields(gm.campaign()) + "/" + combat));
        notFound(get(gm, shields(gm.campaign()) + "/" + combat + "/deletion"));
        notFound(send(gm, "DELETE", shields(gm.campaign()) + "/" + combat, ""));
        // Its name is free at once.
        UUID again = UUID.fromString(json(send(gm, "POST", shields(gm.campaign()), "{\"name\":\"Combate\"}"))
                .path("id").asString());
        // The pass (scheduled, or the sweep) removes its row and ends the deletion.
        deletion.sweep();
        assertThat(library.shields).doesNotContainKey(combat).containsKeys(first, again);
        assertThat(send(gm, "DELETE", shields(gm.campaign()) + "/" + again, "").statusCode()).isEqualTo(202);
        deletion.sweep();

        // The campaign's only Shield: the preview says so, and its deletion is refused with nothing changed.
        JsonNode last = ok(get(gm, shields(gm.campaign()) + "/" + first + "/deletion"));
        assertThat(last.path("last").asBoolean()).isTrue();
        assertThat(last.path("notes").asLong()).isZero();
        assertThat(last.path("rolls").asLong()).isZero();
        ShieldRow kept = library.shields.get(first);
        HttpResponse<String> refused = send(gm, "DELETE", shields(gm.campaign()) + "/" + first, "");
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(json(refused).path("code").asString()).isEqualTo("request.last_shield");
        assertThat(library.shields.get(first)).isEqualTo(kept);
        assertThat(ok(get(gm, shields(gm.campaign()))).path("total").asLong()).isEqualTo(1);

        // A new Shield makes the first one deletable again; the new one, named, keeps the campaign's one Shield.
        UUID next = UUID.fromString(json(send(gm, "POST", shields(gm.campaign()), "{\"name\":\"Exploração\"}"))
                .path("id").asString());
        assertThat(send(gm, "DELETE", shields(gm.campaign()) + "/" + first, "").statusCode()).isEqualTo(202);
        assertThat(ok(get(gm, shields(gm.campaign()))).path("items")).extracting(item -> item.path("id").asString())
                .containsExactly(next.toString());
        assertThat(ok(get(gm, shields(gm.campaign()) + "/" + next + "/deletion")).path("last").asBoolean()).isTrue();
    }

    @Test
    void thePreviewCountsTheShieldsStoredRollsAndItsDeletionRemovesThem() throws Exception {
        Gm gm = signUp();
        UUID first = firstShield(gm);
        UUID combat = UUID.fromString(json(send(gm, "POST", shields(gm.campaign()), "{\"name\":\"Combate\"}"))
                .path("id").asString());
        for (UUID shield : List.of(combat, combat, first)) {
            HttpResponse<String> kept = send(gm, "POST", shields(gm.campaign()) + "/" + shield + "/rolls",
                    "{\"key\":\"" + UUID.randomUUID() + "\",\"formula\":\"1d20\",\"results\":[7],"
                            + "\"rolledAt\":\"2026-11-27T11:00:00Z\"}");
            assertThat(kept.statusCode()).as(kept.body()).isEqualTo(201);
        }

        JsonNode preview = ok(get(gm, shields(gm.campaign()) + "/" + combat + "/deletion"));
        assertThat(preview.path("rolls").asLong()).isEqualTo(2);
        assertThat(preview.path("notes").asLong()).isZero();
        assertThat(ok(get(gm, shields(gm.campaign()) + "/" + first + "/deletion")).path("rolls").asLong()).isEqualTo(1);

        assertThat(send(gm, "DELETE", shields(gm.campaign()) + "/" + combat, "").statusCode()).isEqualTo(202);
        deletion.sweep();
        assertThat(library.shields).doesNotContainKey(combat);
        assertThat(library.rolls.values()).noneMatch(row -> row.shieldId().equals(combat));
        assertThat(library.rolls.values()).filteredOn(row -> row.ownerId().equals(gm.id())).singleElement()
                .satisfies(row -> assertThat(row.shieldId()).isEqualTo(first));
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

    private Gm signUp() throws Exception {
        String sub = "shield-" + UUID.randomUUID();
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
