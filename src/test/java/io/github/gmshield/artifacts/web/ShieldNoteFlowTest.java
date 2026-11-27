package io.github.gmshield.artifacts.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.accounts.data.AccountRow;
import io.github.gmshield.accounts.data.AccountStore;
import io.github.gmshield.accounts.data.InMemoryAccountStore;
import io.github.gmshield.accounts.data.InMemorySessionStore;
import io.github.gmshield.accounts.data.InMemorySignInAttemptStore;
import io.github.gmshield.accounts.data.SessionStore;
import io.github.gmshield.accounts.data.SignInAttemptStore;
import io.github.gmshield.artifacts.data.ArtifactVersionRow;
import io.github.gmshield.artifacts.data.InMemoryArtifactIndexStore;
import io.github.gmshield.artifacts.data.InMemoryArtifactVersionStore;
import io.github.gmshield.audit.data.AuditStore;
import io.github.gmshield.audit.data.InMemoryAuditStore;
import io.github.gmshield.conversations.data.ConversationTestStores;
import io.github.gmshield.jobs.data.JobMemoryStores;
import io.github.gmshield.knowledge.data.ArtifactRow;
import io.github.gmshield.knowledge.data.InMemoryRecordStore;
import io.github.gmshield.library.data.DeletionRow;
import io.github.gmshield.library.data.InMemoryDeletionStore;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.data.LibraryTestStores;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.materials.data.MaterialTestStores;
import io.github.gmshield.scope.service.ScopeSettings;
import io.github.gmshield.scope.service.Scopes;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Story 6.2 part A without a database: every backend row of its I/O matrix, through the public API. A note written in a
 * Shield is created (201, type {@code note}, {@code shieldId}, version 1, no fields, an index job queued, stored only
 * encrypted) and opens as written; a blank name or a content over 20,000 code points is {@code request.invalid} naming
 * its field, and a malformed body {@code request.invalid}, with nothing stored; a Shield lists its own notes newest
 * first, a page at a time (by cursor and by offset, with its total), while the campaign's artifact list shows every
 * note with its Shield; a retype of a held note is {@code request.note_type_fixed} and stores nothing, an edit as a note
 * stores version 2 and keeps its Shield, and a stale one is {@code request.stale_version}; another owner's campaign or
 * Shield, an unknown or malformed id, a Shield of another campaign and a campaign being deleted are
 * {@code request.not_found} on both routes, with nothing stored. Besides the matrix: a note's id is among the campaign
 * tier's sources (citations need no code), a {@code note} written through the generic artifact route has no Shield and
 * is in no Shield's list, a held note shared into another campaign reads there with no Shield, and a deleted note
 * leaves its Shield's list at once. The cross-owner rows are also {@code CrossOwnerAuditFlowTest}'s, the key on
 * PostgreSQL {@code ArtifactsIntegrationTest}'s.
 *
 * <p>Story 6.2 part B, every backend row of its I/O matrix: a move to another Shield of the campaign keeps the note's
 * versions and creates no version and no index job, and a move to its own Shield answers it unchanged; a Shield of
 * another campaign, a deleting or unknown Shield, a note no Shield holds and a malformed body are refused with the note
 * unchanged; the deletion preview counts the live notes; a Shield deleted with its notes is gone at once with them
 * (lists, reads, the campaign tier), and its pass removes their rows and versions with one removal job each before the
 * Shield's row (the in-memory store refuses the row while a note names it, as the key does); the last live Shield is
 * refused while another is deleting; a note created in, or moved into, a deleting Shield is not found; a campaign
 * deleted meanwhile ends both deletions; and another owner's move or deletion is not found.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GMSHIELD_MODE=test")
@ActiveProfiles({"test-mode", "unit"})
class ShieldNoteFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CREDENTIAL = "gmshield-test-mode-frontend-credential-not-a-secret";
    private static final String PISTAS = "{\"name\":\"  Pistas \",\"content\":\"O sacerdote mente.\\nA chave está no"
            + " poço.\"}";

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
    private InMemoryRecordStore records;
    @Autowired
    private InMemoryArtifactVersionStore versions;
    @Autowired
    private InMemoryArtifactIndexStore index;
    @Autowired
    private SystemDeletion deletion;
    @Autowired
    private Scopes scopes;
    @Autowired
    private InMemoryLibraryStore library;
    @Autowired
    private InMemoryDeletionStore deletions;
    @Autowired
    private Vault vault;

    private final HttpClient client = HttpClient.newHttpClient();

    record Gm(UUID id, String session, UUID campaign, UUID system) {
    }

    /** As {@code record_shield_fkey}: a Shield's row cannot go while a note still names it. */
    @BeforeEach
    void shieldKey() {
        library.shieldHasNotes = shield -> records.artifacts.values().stream()
                .anyMatch(row -> shield.equals(row.shieldId()));
    }

    // --- create

    @Test
    void aNoteWrittenInAShieldIsANoteOfThatShieldAtVersionOneWithItsIndexJob() throws Exception {
        Gm gm = signUp();
        UUID shield = firstShield(gm);

        HttpResponse<String> created = send(gm, "POST", notes(gm.campaign(), shield), PISTAS);

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode note = json(created);
        String id = note.path("id").asString();
        assertThat(note.path("campaignId").asString()).isEqualTo(gm.campaign().toString());
        assertThat(note.path("recordType").asString()).isEqualTo("note");
        assertThat(note.path("shieldId").asString()).isEqualTo(shield.toString());
        assertThat(note.path("name").asString()).isEqualTo("Pistas");
        assertThat(note.path("content").asString()).isEqualTo("O sacerdote mente.\nA chave está no poço.");
        assertThat(note.path("fields")).isEmpty();
        assertThat(note.path("version").asInt()).isEqualTo(1);
        assertThat(note.path("currentVersion").asInt()).isEqualTo(1);
        assertThat(note.path("conversationId").isNull()).isTrue();
        // It opens on the artifact routes as any artifact does, with its Shield.
        assertThat(ok(get(gm, artifacts(gm.campaign()) + "/" + id))).isEqualTo(note);
        assertThat(ok(get(gm, artifacts(gm.campaign()) + "/" + id + "/versions/1")).path("shieldId").asString())
                .isEqualTo(shield.toString());

        // The identity names the Shield; version 1 is stored only encrypted; its index job is queued with it.
        ArtifactRow row = records.artifacts.get(UUID.fromString(id));
        assertThat(row.shieldId()).isEqualTo(shield);
        assertThat(row.type()).isEqualTo("note");
        assertThat(row.campaignId()).isEqualTo(gm.campaign());
        ArtifactVersionRow version = versions.rows.get(id + "/1");
        assertThat(new String(version.content(), StandardCharsets.ISO_8859_1)).doesNotContain("sacerdote");
        assertThat(vault.decryptText(new Binding("record_version", "name", UUID.fromString(id), gm.id()),
                version.name())).isEqualTo("Pistas");
        assertThat(index.rows.values()).filteredOn(job -> job.recordId().toString().equals(id)).singleElement()
                .satisfies(job -> assertThat(job.number()).isEqualTo(1));
        // A note with a title alone is valid.
        HttpResponse<String> bare = send(gm, "POST", notes(gm.campaign(), shield), "{\"name\":\"Lembrete\"}");
        assertThat(bare.statusCode()).as(bare.body()).isEqualTo(201);
        assertThat(json(bare).path("content").isNull()).isTrue();
    }

    @Test
    void aBlankNameATooLongTextOrAMalformedBodyIsRefusedAndNothingIsStored() throws Exception {
        Gm gm = signUp();
        UUID shield = firstShield(gm);
        int before = records.artifacts.size();

        for (String body : List.of("{\"name\":\"   \"}", "{\"name\":\"\",\"content\":\"x\"}", "{\"content\":\"x\"}")) {
            HttpResponse<String> refused = send(gm, "POST", notes(gm.campaign(), shield), body);
            assertThat(refused.statusCode()).as(body).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).isEqualTo("request.invalid");
            assertThat(json(refused).path("params").path("field").asString()).isEqualTo("name");
            assertThat(json(refused).path("params").path("max").asInt()).isEqualTo(200);
        }
        HttpResponse<String> tooLong = send(gm, "POST", notes(gm.campaign(), shield), "{\"name\":\"Longa\",\"content\":\""
                + "a".repeat(20_001) + "\"}");
        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(json(tooLong).path("params").path("field").asString()).isEqualTo("content");
        assertThat(tooLong.body()).doesNotContain("aaaa");
        for (String body : List.of("not json", "null")) {
            HttpResponse<String> malformed = send(gm, "POST", notes(gm.campaign(), shield), body);
            assertThat(malformed.statusCode()).as(body).isEqualTo(400);
            assertThat(json(malformed).path("code").asString()).isEqualTo("request.invalid");
            assertThat(json(malformed).path("params").has("field")).isFalse();
        }
        assertThat(records.artifacts).hasSize(before);
        assertThat(ok(get(gm, notes(gm.campaign(), shield))).path("total").asLong()).isZero();
    }

    // --- list

    @Test
    void aShieldListsItsOwnNotesNewestFirstAPageAtATimeAndTheCampaignListShowsThemAll() throws Exception {
        Gm gm = signUp();
        UUID first = firstShield(gm);
        UUID combat = shield(gm, "Combate");
        List<String> made = new ArrayList<>();
        for (String name : List.of("Um", "Dois", "Três")) {
            made.add(note(gm, first, name));
        }
        String other = note(gm, combat, "Outra");

        JsonNode page = ok(get(gm, notes(gm.campaign(), first)));
        assertThat(ids(page)).containsExactly(made.get(2), made.get(1), made.get(0));
        assertThat(page.path("total").asLong()).isEqualTo(3);
        assertThat(page.has("nextCursor")).isFalse();
        assertThat(page.path("items").get(0).path("name").asString()).isEqualTo("Três");
        assertThat(page.path("items").get(0).path("recordType").asString()).isEqualTo("note");
        assertThat(page.path("items").get(0).path("version").asInt()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("shieldId").asString()).isEqualTo(first.toString());
        assertThat(page.path("items").get(0).path("sharedFrom").isNull()).isTrue();
        assertThat(ids(ok(get(gm, notes(gm.campaign(), combat))))).containsExactly(other);

        // A page at a time: by cursor, and by offset with the total.
        JsonNode firstPage = ok(get(gm, notes(gm.campaign(), first) + "?limit=2"));
        assertThat(ids(firstPage)).containsExactly(made.get(2), made.get(1));
        assertThat(firstPage.path("nextCursor").asString()).isEqualTo(made.get(1));
        JsonNode secondPage = ok(get(gm, notes(gm.campaign(), first) + "?limit=2&cursor=" + made.get(1)));
        assertThat(ids(secondPage)).containsExactly(made.get(0));
        assertThat(secondPage.has("nextCursor")).isFalse();
        JsonNode numbered = ok(get(gm, notes(gm.campaign(), first) + "?limit=2&offset=2"));
        assertThat(ids(numbered)).containsExactly(made.get(0));
        assertThat(numbered.path("total").asLong()).isEqualTo(3);
        for (String query : List.of("?limit=0", "?limit=51", "?cursor=nope", "?offset=-1", "?offset=100001",
                "?offset=1&cursor=" + made.get(0))) {
            HttpResponse<String> refused = get(gm, notes(gm.campaign(), first) + query);
            assertThat(refused.statusCode()).as(query).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).isEqualTo("request.invalid");
        }

        // The campaign's artifacts list (the "Artefatos" tab) holds all four, each naming its Shield.
        JsonNode all = ok(get(gm, artifacts(gm.campaign())));
        assertThat(ids(all)).containsExactly(other, made.get(2), made.get(1), made.get(0));
        assertThat(all.path("types").toString()).isEqualTo("[{\"key\":\"note\",\"count\":4}]");
        assertThat(all.path("items").get(0).path("shieldId").asString()).isEqualTo(combat.toString());
        assertThat(all.path("items").get(1).path("shieldId").asString()).isEqualTo(first.toString());
    }

    @Test
    void aNoteWrittenThroughTheArtifactRouteHasNoShieldAndIsInNoShieldsList() throws Exception {
        Gm gm = signUp();
        UUID shield = firstShield(gm);

        HttpResponse<String> created = send(gm, "POST", artifacts(gm.campaign()),
                "{\"recordType\":\"note\",\"name\":\"Solta\",\"fields\":[],\"shieldId\":\"" + shield + "\"}");

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(json(created).has("shieldId")).isTrue();
        assertThat(json(created).path("shieldId").isNull()).isTrue();
        assertThat(records.artifacts.get(UUID.fromString(json(created).path("id").asString())).shieldId()).isNull();
        assertThat(ok(get(gm, notes(gm.campaign(), shield))).path("items")).isEmpty();
        assertThat(ok(get(gm, artifacts(gm.campaign()))).path("items").get(0).path("shieldId").isNull()).isTrue();
        // Its type can change, as any artifact's.
        HttpResponse<String> retyped = send(gm, "PATCH", artifacts(gm.campaign()) + "/" + json(created).path("id")
                .asString(), "{\"version\":1,\"recordType\":\"rule\",\"name\":\"Solta\",\"fields\":[]}");
        assertThat(retyped.statusCode()).as(retyped.body()).isEqualTo(200);
        assertThat(json(retyped).path("recordType").asString()).isEqualTo("rule");
    }

    @Test
    void aDeletedNoteLeavesItsShieldsListAtOnce() throws Exception {
        Gm gm = signUp();
        UUID shield = firstShield(gm);
        String kept = note(gm, shield, "Fica");
        String going = note(gm, shield, "Vai");

        assertThat(send(gm, "DELETE", artifacts(gm.campaign()) + "/" + going, null).statusCode()).isEqualTo(202);

        JsonNode page = ok(get(gm, notes(gm.campaign(), shield)));
        assertThat(ids(page)).containsExactly(kept);
        assertThat(page.path("total").asLong()).isEqualTo(1);
        deletion.sweep();
        assertThat(records.artifacts).doesNotContainKey(UUID.fromString(going));
        assertThat(ids(ok(get(gm, notes(gm.campaign(), shield))))).containsExactly(kept);
    }

    // --- edit

    @Test
    void aHeldNoteStaysANoteAnEditKeepsItsShieldAndAStaleOneIsAConflict() throws Exception {
        Gm gm = signUp();
        UUID shield = firstShield(gm);
        String id = note(gm, shield, "Pistas");
        String path = artifacts(gm.campaign()) + "/" + id;

        // Another type: refused, nothing stored, the editor's version unchanged.
        for (String type : List.of("rule", "item")) {
            HttpResponse<String> retyped = send(gm, "PATCH", path, "{\"version\":1,\"recordType\":\"" + type
                    + "\",\"name\":\"Pistas\",\"fields\":[],\"content\":\"Outra\"}");
            assertThat(retyped.statusCode()).as(retyped.body()).isEqualTo(409);
            assertThat(retyped.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
            assertThat(json(retyped).path("code").asString()).isEqualTo("request.note_type_fixed");
            assertThat(retyped.body()).doesNotContain("Pistas").doesNotContain("Outra");
        }
        assertThat(versionsOf(id)).isEqualTo(1);
        assertThat(records.artifacts.get(UUID.fromString(id)).type()).isEqualTo("note");
        assertThat(index.rows.values()).filteredOn(job -> job.recordId().toString().equals(id)).hasSize(1);
        assertThat(ok(get(gm, path)).path("version").asInt()).isEqualTo(1);

        // An edit as a note: version 2, the Shield kept, version 1 still there.
        JsonNode edited = ok(send(gm, "PATCH", path, "{\"version\":1,\"recordType\":\"note\",\"name\":\"Pistas\","
                + "\"fields\":[],\"content\":\"A chave está no poço.\"}"));
        assertThat(edited.path("version").asInt()).isEqualTo(2);
        assertThat(edited.path("shieldId").asString()).isEqualTo(shield.toString());
        assertThat(edited.path("content").asString()).isEqualTo("A chave está no poço.");
        assertThat(ok(get(gm, path + "/versions")).path("total").asLong()).isEqualTo(2);
        assertThat(records.artifacts.get(UUID.fromString(id)).shieldId()).isEqualTo(shield);
        assertThat(ids(ok(get(gm, notes(gm.campaign(), shield))))).containsExactly(id);
        assertThat(ok(get(gm, notes(gm.campaign(), shield))).path("items").get(0).path("version").asInt())
                .isEqualTo(2);

        // The second tab still names version 1: stale, nothing stored.
        HttpResponse<String> stale = send(gm, "PATCH", path, "{\"version\":1,\"recordType\":\"note\",\"name\":"
                + "\"Pistas\",\"fields\":[],\"content\":\"Da outra aba\"}");
        assertThat(stale.statusCode()).as(stale.body()).isEqualTo(409);
        assertThat(json(stale).path("code").asString()).isEqualTo("request.stale_version");
        assertThat(versionsOf(id)).isEqualTo(2);

        // A restore copies a note's version: it stays a note of its Shield.
        JsonNode restored = ok(send(gm, "POST", path + "/versions/1/restore", "{\"version\":2}"));
        assertThat(restored.path("version").asInt()).isEqualTo(3);
        assertThat(restored.path("recordType").asString()).isEqualTo("note");
        assertThat(restored.path("shieldId").asString()).isEqualTo(shield.toString());
    }

    // --- citations

    @Test
    void aNotesIdIsAmongTheCampaignTiersSources() throws Exception {
        Gm gm = signUp();
        String id = note(gm, firstShield(gm), "Pistas");

        Set<UUID> campaignTier = OwnerContext.callAs(gm.id(), () -> scopes.resolveSources(gm.campaign(),
                new ScopeSettings(true, false, false, false, Set.of())).artifacts());
        Set<UUID> withoutIt = OwnerContext.callAs(gm.id(), () -> scopes.resolveSources(gm.campaign(),
                new ScopeSettings(false, true, false, false, Set.of())).artifacts());

        assertThat(campaignTier).contains(UUID.fromString(id));
        assertThat(withoutIt).doesNotContain(UUID.fromString(id));
    }

    @Test
    void aHeldNoteSharedIntoAnotherCampaignReadsThereWithNoShield() throws Exception {
        Gm gm = signUp();
        UUID shield = firstShield(gm);
        String id = note(gm, shield, "Pistas");
        UUID other = campaign(gm, "Outra mesa");

        HttpResponse<String> shared = send(gm, "PUT", artifacts(gm.campaign()) + "/" + id + "/sharing",
                "{\"campaignIds\":[\"" + other + "\"]}");
        assertThat(shared.statusCode()).as(shared.body()).isEqualTo(200);

        JsonNode there = ok(get(gm, artifacts(other) + "/" + id));
        assertThat(there.path("sharedFrom").path("campaignId").asString()).isEqualTo(gm.campaign().toString());
        assertThat(there.path("shieldId").isNull()).isTrue();
        JsonNode listed = ok(get(gm, artifacts(other))).path("items").get(0);
        assertThat(listed.path("id").asString()).isEqualTo(id);
        assertThat(listed.path("shieldId").isNull()).isTrue();
        assertThat(ok(get(gm, artifacts(gm.campaign()) + "/" + id)).path("shieldId").asString())
                .isEqualTo(shield.toString());
    }

    // --- not found

    @Test
    void anotherOwnersOrAnUnknownCampaignOrShieldAShieldOfAnotherCampaignAndADeletingCampaignAreNotFound()
            throws Exception {
        Gm a = signUp();
        Gm b = signUp();
        UUID aShield = firstShield(a);
        String aNote = note(a, aShield, "Segredo");
        UUID bOther = campaign(b, "Outra");
        UUID bOtherShield = UUID.fromString(ok(get(b, shields(bOther))).path("items").get(0).path("id").asString());
        int stored = records.artifacts.size();
        JsonNode expected = notFound(get(b, notes(UUID.randomUUID(), UUID.randomUUID())));

        List<HttpResponse<String>> refused = new ArrayList<>();
        for (String campaign : List.of(a.campaign().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            refused.add(get(b, "/api/v1/campaigns/" + campaign + "/shields/" + aShield + "/notes"));
            refused.add(send(b, "POST", "/api/v1/campaigns/" + campaign + "/shields/" + aShield + "/notes", PISTAS));
        }
        // B's own campaign with A's Shield, B's Shield of its other campaign, an unknown and a malformed Shield id.
        for (String shield : List.of(aShield.toString(), bOtherShield.toString(), UUID.randomUUID().toString(), "123")) {
            refused.add(get(b, shields(b.campaign()) + "/" + shield + "/notes"));
            refused.add(send(b, "POST", shields(b.campaign()) + "/" + shield + "/notes", PISTAS));
        }
        // An invalid body changes nothing either: the Shield is checked first.
        refused.add(send(b, "POST", notes(a.campaign(), aShield), "{\"name\":\" \"}"));
        for (HttpResponse<String> response : refused) {
            assertThat(withoutTraceId(notFound(response))).isEqualTo(withoutTraceId(expected));
            assertThat(response.body()).doesNotContain(aShield.toString()).doesNotContain(aNote)
                    .doesNotContain("Segredo");
        }
        assertThat(records.artifacts).hasSize(stored);
        assertThat(ids(ok(get(a, notes(a.campaign(), aShield))))).containsExactly(aNote);
        // Every route needs a session.
        assertThat(client.send(HttpRequest.newBuilder(uri(notes(a.campaign(), aShield))).header("Authorization",
                "Bearer " + CREDENTIAL).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);

        // A campaign being deleted: not found on both routes, from the commit of the GM's delete.
        assertThat(send(b, "POST", notes(bOther, bOtherShield), "{\"name\":\"Vai junto\"}").statusCode())
                .isEqualTo(201);
        assertThat(send(b, "DELETE", "/api/v1/campaigns/" + bOther, null).statusCode()).isEqualTo(202);
        int before = records.artifacts.size();
        notFound(get(b, notes(bOther, bOtherShield)));
        notFound(send(b, "POST", notes(bOther, bOtherShield), PISTAS));
        assertThat(records.artifacts).hasSize(before);
        // Its removal takes its notes as artifacts.
        deletion.sweep();
        assertThat(records.artifacts.values()).noneMatch(row -> row.campaignId().equals(bOther));
    }

    // --- move (part B)

    @Test
    void aMoveToAnotherShieldKeepsTheVersionsAndCreatesNoVersionAndNoIndexJob() throws Exception {
        Gm gm = signUp();
        UUID first = firstShield(gm);
        UUID combat = shield(gm, "Combate");
        String id = note(gm, first, "Pistas");
        String staying = note(gm, first, "Fica");
        HttpResponse<String> edited = send(gm, "PATCH", artifacts(gm.campaign()) + "/" + id,
                "{\"version\":1,\"recordType\":\"note\",\"name\":\"Pistas\",\"fields\":[],\"content\":\"Duas\"}");
        assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
        int jobs = index.rows.size();

        HttpResponse<String> moved = send(gm, "PUT", artifacts(gm.campaign()) + "/" + id + "/shield",
                "{\"shieldId\":\"" + combat + "\"}");

        assertThat(moved.statusCode()).as(moved.body()).isEqualTo(200);
        assertThat(moved.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode note = json(moved);
        assertThat(note.path("shieldId").asString()).isEqualTo(combat.toString());
        assertThat(note.path("version").asInt()).isEqualTo(2);
        assertThat(note.path("currentVersion").asInt()).isEqualTo(2);
        assertThat(note.path("content").asString()).isEqualTo("Duas");
        assertThat(note.path("recordType").asString()).isEqualTo("note");
        assertThat(ok(get(gm, artifacts(gm.campaign()) + "/" + id))).isEqualTo(note);
        assertThat(records.artifacts.get(UUID.fromString(id)).shieldId()).isEqualTo(combat);
        assertThat(ids(ok(get(gm, notes(gm.campaign(), first))))).containsExactly(staying);
        assertThat(ids(ok(get(gm, notes(gm.campaign(), combat))))).containsExactly(id);
        JsonNode history = ok(get(gm, artifacts(gm.campaign()) + "/" + id + "/versions"));
        assertThat(history.path("total").asLong()).isEqualTo(2);
        assertThat(versionsOf(id)).isEqualTo(2);
        assertThat(index.rows).hasSize(jobs);

        // To its own Shield: answered unchanged.
        HttpResponse<String> again = send(gm, "PUT", artifacts(gm.campaign()) + "/" + id + "/shield",
                "{\"shieldId\":\"" + combat + "\"}");
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        assertThat(json(again)).isEqualTo(note);
        assertThat(versionsOf(id)).isEqualTo(2);
        assertThat(index.rows).hasSize(jobs);
    }

    @Test
    void aMoveToAShieldOfAnotherCampaignADeletingOrUnknownShieldOrOfAnUnheldArtifactIsNotFound() throws Exception {
        Gm gm = signUp();
        UUID first = firstShield(gm);
        UUID combat = shield(gm, "Combate");
        UUID going = shield(gm, "Vai");
        String id = note(gm, first, "Pistas");
        UUID other = campaign(gm, "Outra mesa");
        UUID otherShield = UUID.fromString(ok(get(gm, shields(other))).path("items").get(0).path("id").asString());
        assertThat(send(gm, "DELETE", shields(gm.campaign()) + "/" + going, null).statusCode()).isEqualTo(202);
        HttpResponse<String> loose = send(gm, "POST", artifacts(gm.campaign()),
                "{\"recordType\":\"note\",\"name\":\"Solta\",\"fields\":[]}");
        String unheld = json(loose).path("id").asString();
        JsonNode before = ok(get(gm, artifacts(gm.campaign()) + "/" + id));

        for (String target : List.of(otherShield.toString(), going.toString(), UUID.randomUUID().toString(), "123")) {
            notFound(send(gm, "PUT", artifacts(gm.campaign()) + "/" + id + "/shield",
                    "{\"shieldId\":\"" + target + "\"}"));
        }
        // A note no Shield holds, an unknown note and the note from another campaign of the owner.
        notFound(send(gm, "PUT", artifacts(gm.campaign()) + "/" + unheld + "/shield",
                "{\"shieldId\":\"" + combat + "\"}"));
        notFound(send(gm, "PUT", artifacts(gm.campaign()) + "/" + UUID.randomUUID() + "/shield",
                "{\"shieldId\":\"" + combat + "\"}"));
        notFound(send(gm, "PUT", artifacts(other) + "/" + id + "/shield", "{\"shieldId\":\"" + otherShield + "\"}"));
        for (String body : List.of("not json", "null", "{}", "{\"shieldId\":null}")) {
            HttpResponse<String> malformed = send(gm, "PUT", artifacts(gm.campaign()) + "/" + id + "/shield", body);
            assertThat(malformed.statusCode()).as(body).isEqualTo(400);
            assertThat(json(malformed).path("code").asString()).isEqualTo("request.invalid");
        }

        assertThat(ok(get(gm, artifacts(gm.campaign()) + "/" + id))).isEqualTo(before);
        assertThat(records.artifacts.get(UUID.fromString(id)).shieldId()).isEqualTo(first);
        assertThat(records.artifacts.get(UUID.fromString(unheld)).shieldId()).isNull();
    }

    // --- a Shield's deletion with its notes (part B)

    @Test
    void thePreviewCountsTheLiveNotesAndADeletionTakesThemAtOnceAndItsPassRemovesThemBeforeTheShield()
            throws Exception {
        Gm gm = signUp();
        UUID first = firstShield(gm);
        UUID going = shield(gm, "Vai");
        List<String> notes = new ArrayList<>();
        for (String name : List.of("Um", "Dois", "Três")) {
            notes.add(note(gm, going, name));
        }
        String deleted = note(gm, going, "Apagada");
        assertThat(send(gm, "DELETE", artifacts(gm.campaign()) + "/" + deleted, null).statusCode()).isEqualTo(202);
        String kept = note(gm, first, "Fica");

        JsonNode preview = ok(get(gm, shields(gm.campaign()) + "/" + going + "/deletion"));
        assertThat(preview.path("notes").asLong()).isEqualTo(3);
        assertThat(preview.path("rolls").asLong()).isZero();
        assertThat(preview.path("last").asBoolean()).isFalse();

        HttpResponse<String> started = send(gm, "DELETE", shields(gm.campaign()) + "/" + going, null);

        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        assertThat(started.body()).isEmpty();
        // At once: the Shield is gone from the list and every read, and its notes from the artifacts and the tier.
        assertThat(ids(ok(get(gm, shields(gm.campaign()))))).containsExactly(first.toString());
        notFound(get(gm, shields(gm.campaign()) + "/" + going));
        notFound(get(gm, shields(gm.campaign()) + "/" + going + "/deletion"));
        notFound(get(gm, notes(gm.campaign(), going)));
        notFound(send(gm, "DELETE", shields(gm.campaign()) + "/" + going, null));
        assertThat(ids(ok(get(gm, artifacts(gm.campaign()))))).containsExactly(kept);
        for (String id : notes) {
            notFound(get(gm, artifacts(gm.campaign()) + "/" + id));
        }
        Set<UUID> tier = OwnerContext.callAs(gm.id(), () -> scopes.resolveSources(gm.campaign(),
                new ScopeSettings(true, false, false, false, Set.of())).artifacts());
        assertThat(tier).containsExactly(UUID.fromString(kept));
        assertThat(deletions.rows.values()).filteredOn(row -> row.targetId().equals(going)).singleElement()
                .satisfies(row -> assertThat(row.kind()).isEqualTo(DeletionRow.SHIELD));
        assertThat(library.shields.get(going).deleting()).isTrue();

        deletion.sweep();

        // After the pass: no row or version of its notes (the deleted one included), one removal job each, no Shield.
        for (String id : List.of(notes.get(0), notes.get(1), notes.get(2), deleted)) {
            assertThat(records.artifacts).doesNotContainKey(UUID.fromString(id));
            assertThat(versionsOf(id)).isZero();
            assertThat(index.removalRows.values()).filteredOn(row -> row.artifactId().toString().equals(id))
                    .hasSize(1);
        }
        assertThat(library.shields).doesNotContainKey(going).containsKey(first);
        assertThat(deletions.rows.values()).filteredOn(row -> row.targetId().equals(going)).singleElement()
                .satisfies(row -> assertThat(row.doneAt()).isNotNull());
        assertThat(ids(ok(get(gm, notes(gm.campaign(), first))))).containsExactly(kept);
        assertThat(records.artifacts).containsKey(UUID.fromString(kept));
    }

    @Test
    void theLastLiveShieldIsRefusedWhileAnotherIsDeletingAndNoNoteLandsInADeletingShield() throws Exception {
        Gm gm = signUp();
        UUID first = firstShield(gm);
        UUID going = shield(gm, "Vai");
        String id = note(gm, first, "Pistas");
        assertThat(send(gm, "DELETE", shields(gm.campaign()) + "/" + going, null).statusCode()).isEqualTo(202);

        JsonNode last = ok(get(gm, shields(gm.campaign()) + "/" + first + "/deletion"));
        assertThat(last.path("last").asBoolean()).isTrue();
        assertThat(last.path("notes").asLong()).isOne();
        HttpResponse<String> refused = send(gm, "DELETE", shields(gm.campaign()) + "/" + first, null);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(json(refused).path("code").asString()).isEqualTo("request.last_shield");

        int stored = records.artifacts.size();
        notFound(send(gm, "POST", notes(gm.campaign(), going), PISTAS));
        notFound(send(gm, "PUT", artifacts(gm.campaign()) + "/" + id + "/shield", "{\"shieldId\":\"" + going + "\"}"));
        assertThat(records.artifacts).hasSize(stored);
        assertThat(records.artifacts.get(UUID.fromString(id)).shieldId()).isEqualTo(first);
    }

    @Test
    void aCampaignDeletedWhileItsShieldsDeletionIsPendingEndsBoth() throws Exception {
        Gm gm = signUp();
        UUID other = campaign(gm, "Outra mesa");
        UUID first = UUID.fromString(ok(get(gm, shields(other))).path("items").get(0).path("id").asString());
        HttpResponse<String> made = send(gm, "POST", shields(other), "{\"name\":\"Vai\"}");
        UUID going = UUID.fromString(json(made).path("id").asString());
        String held = json(send(gm, "POST", notes(other, going), "{\"name\":\"Vai junto\"}")).path("id").asString();
        String kept = json(send(gm, "POST", notes(other, first), "{\"name\":\"Fica\"}")).path("id").asString();
        assertThat(send(gm, "DELETE", shields(other) + "/" + going, null).statusCode()).isEqualTo(202);
        assertThat(send(gm, "DELETE", "/api/v1/campaigns/" + other, null).statusCode()).isEqualTo(202);

        deletion.sweep();
        deletion.sweep();

        assertThat(records.artifacts).doesNotContainKeys(UUID.fromString(held), UUID.fromString(kept));
        assertThat(library.shields).doesNotContainKeys(first, going);
        assertThat(library.campaigns).doesNotContainKey(other);
        assertThat(deletions.rows.values()).filteredOn(row -> row.targetId().equals(going)
                || row.targetId().equals(other)).hasSize(2).allSatisfy(row -> assertThat(row.doneAt()).isNotNull());
    }

    @Test
    void anotherOwnersMoveOrShieldDeletionIsNotFoundAndChangesNothing() throws Exception {
        Gm a = signUp();
        Gm b = signUp();
        UUID aFirst = firstShield(a);
        UUID aCombat = shield(a, "Combate");
        String aNote = note(a, aFirst, "Segredo");
        UUID bShield = firstShield(b);

        notFound(send(b, "PUT", artifacts(a.campaign()) + "/" + aNote + "/shield", "{\"shieldId\":\"" + aCombat + "\"}"));
        notFound(send(b, "PUT", artifacts(b.campaign()) + "/" + aNote + "/shield", "{\"shieldId\":\"" + bShield + "\"}"));
        notFound(send(a, "PUT", artifacts(a.campaign()) + "/" + aNote + "/shield", "{\"shieldId\":\"" + bShield + "\"}"));
        notFound(send(b, "DELETE", shields(a.campaign()) + "/" + aCombat, null));
        notFound(send(b, "DELETE", shields(b.campaign()) + "/" + aCombat, null));

        assertThat(records.artifacts.get(UUID.fromString(aNote)).shieldId()).isEqualTo(aFirst);
        assertThat(library.shields.get(aCombat).deleting()).isFalse();
        assertThat(ok(get(a, shields(a.campaign()))).path("total").asLong()).isEqualTo(2);
    }

    // --- helpers

    private static String artifacts(UUID campaign) {
        return "/api/v1/campaigns/" + campaign + "/artifacts";
    }

    private static String shields(UUID campaign) {
        return "/api/v1/campaigns/" + campaign + "/shields";
    }

    private static String notes(UUID campaign, UUID shield) {
        return shields(campaign) + "/" + shield + "/notes";
    }

    private UUID firstShield(Gm gm) throws Exception {
        return UUID.fromString(ok(get(gm, shields(gm.campaign()))).path("items").get(0).path("id").asString());
    }

    private UUID shield(Gm gm, String name) throws Exception {
        HttpResponse<String> created = send(gm, "POST", shields(gm.campaign()), "{\"name\":\"" + name + "\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(json(created).path("id").asString());
    }

    private String note(Gm gm, UUID shield, String name) throws Exception {
        HttpResponse<String> created = send(gm, "POST", notes(gm.campaign(), shield), "{\"name\":\"" + name + "\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return json(created).path("id").asString();
    }

    private UUID campaign(Gm gm, String name) throws Exception {
        HttpResponse<String> created = send(gm, "POST", "/api/v1/campaigns", "{\"name\":\"" + name
                + "\",\"systemId\":\"" + gm.system() + "\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(json(created).path("id").asString());
    }

    private long versionsOf(String id) {
        return versions.rows.values().stream().filter(row -> row.recordId().toString().equals(id)).count();
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asString()));
        return ids;
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

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> get(Gm gm, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Session-Token", gm.session()).GET().build(), HttpResponse.BodyHandlers.ofString());
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
        String sub = "note-" + UUID.randomUUID();
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
