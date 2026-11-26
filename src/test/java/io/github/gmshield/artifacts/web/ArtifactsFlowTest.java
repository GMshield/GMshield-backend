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
import io.github.gmshield.artifacts.data.InMemoryArtifactVersionStore;
import io.github.gmshield.audit.data.AuditStore;
import io.github.gmshield.audit.data.InMemoryAuditStore;
import io.github.gmshield.conversations.data.ConversationTestStores;
import io.github.gmshield.jobs.data.JobMemoryStores;
import io.github.gmshield.knowledge.data.ArtifactRow;
import io.github.gmshield.knowledge.data.InMemoryKnowledgeStore;
import io.github.gmshield.knowledge.data.InMemoryRecordStore;
import io.github.gmshield.knowledge.data.RecordTypeRow;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.data.LibraryTestStores;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.materials.data.MaterialTestStores;
import io.github.gmshield.materials.service.ExtractedRecords;
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
 * Story 5.1 part A, without a database: write an artifact by hand. Every row of the story's I/O matrix, through the
 * public API: an item with two fields and a content is created (201, version 1) and opens as written; a blank name, 65
 * fields, a content over 20,000 code points and a type the system lacks are each {@code request.invalid} naming the
 * field, and nothing is stored; 30 artifacts list newest first a page at a time (by cursor and by offset) with their
 * total and the types in use, and a type narrows the list; an empty campaign lists nothing; a campaign's character
 * sheets list by their type; the stored rows are ciphertexts (the restart is {@code ArtifactsIntegrationTest}'s);
 * another owner's campaign or artifact and an artifact of another campaign are {@code request.not_found}. Besides the matrix: an artifact never shows as a system's record (the type's list and
 * counts, both record pages, the port the answers and searches read records through), an approved type of the system
 * is accepted (an archived campaign takes artifacts too) and cannot be deleted while an artifact uses it, a malformed
 * body or query is {@code request.invalid} without a field, both deletion dialogs count the artifacts that go, and a
 * campaign's deletion takes its artifacts. The cross-owner rows are also in {@code CrossOwnerAuditFlowTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GMSHIELD_MODE=test")
@ActiveProfiles({"test-mode", "unit"})
class ArtifactsFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CREDENTIAL = "gmshield-test-mode-frontend-credential-not-a-secret";
    private static final String KAEL = "{\"recordType\":\"item\",\"name\":\"  Espada de Kael \",\"fields\":["
            + "{\"name\":\"Dano\",\"value\":\"1d8 cortante\"},{\"name\":\" Peso \",\"value\":\" 1,5 kg \"}],"
            + "\"content\":\"Forjada nas montanhas do norte.\\nBrilha perto de orcs.\"}";

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
    private InMemoryKnowledgeStore knowledge;
    @Autowired
    private InMemoryArtifactVersionStore versions;
    @Autowired
    private InMemoryLibraryStore library;
    @Autowired
    private ExtractedRecords extracted;
    @Autowired
    private SystemDeletion deletion;
    @Autowired
    private Vault vault;

    private final HttpClient client = HttpClient.newHttpClient();

    record Gm(UUID id, String session, UUID campaign, UUID system) {
    }

    // --- create

    @Test
    void anItemWithTwoFieldsAndAContentIsSavedAsVersionOneAndOpensAsWritten() throws Exception {
        Gm gm = signUp();

        HttpResponse<String> created = send(gm, "POST", artifacts(gm.campaign()), KAEL);

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode body = json(created);
        String id = body.path("id").asString();
        assertThat(body.path("campaignId").asString()).isEqualTo(gm.campaign().toString());
        assertThat(body.path("recordType").asString()).isEqualTo("item");
        assertThat(body.path("name").asString()).isEqualTo("Espada de Kael");
        assertThat(body.path("fields").toString())
                .isEqualTo("[{\"name\":\"Dano\",\"value\":\"1d8 cortante\"},{\"name\":\"Peso\",\"value\":\"1,5 kg\"}]");
        assertThat(body.path("content").asString()).isEqualTo("Forjada nas montanhas do norte.\nBrilha perto de orcs.");
        assertThat(body.path("version").asInt()).isEqualTo(1);
        assertThat(body.path("createdAt").asString()).isNotBlank();

        HttpResponse<String> read = get(gm, artifacts(gm.campaign()) + "/" + id);
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(json(read)).isEqualTo(body);

        // The identity is a record of the campaign; the content is version 1, stored only encrypted.
        ArtifactRow row = records.artifacts.get(UUID.fromString(id));
        assertThat(row.campaignId()).isEqualTo(gm.campaign());
        assertThat(row.type()).isEqualTo("item");
        assertThat(records.records).doesNotContainKey(UUID.fromString(id));
        ArtifactVersionRow version = versions.rows.get(id + "/1");
        assertThat(version.ownerId()).isEqualTo(gm.id());
        assertThat(new String(version.name(), StandardCharsets.ISO_8859_1)).doesNotContain("Kael");
        assertThat(new String(version.fields(), StandardCharsets.ISO_8859_1)).doesNotContain("cortante");
        assertThat(new String(version.content(), StandardCharsets.ISO_8859_1)).doesNotContain("montanhas");
        assertThat(vault.decryptText(new Binding("record_version", "name", UUID.fromString(id), gm.id()),
                version.name())).isEqualTo("Espada de Kael");
        assertThat(vault.decryptText(new Binding("record_version", "content", UUID.fromString(id), gm.id()),
                version.content())).startsWith("Forjada");
        // The row's description names no stored value.
        assertThat(version.toString()).doesNotContain("Kael").contains("number=1");
    }

    @Test
    void aCharacterSheetStoresWhatWasWrittenAndAnArtifactWithoutFieldsOrContentIsValid() throws Exception {
        Gm gm = signUp();

        HttpResponse<String> sheet = send(gm, "POST", artifacts(gm.campaign()), "{\"recordType\":\"character_sheet\","
                + "\"name\":\"Kael\",\"fields\":[{\"name\":\"Força\",\"value\":\"16\"},{\"name\":\"Modificador\","
                + "\"value\":\"+5\"}]}");
        HttpResponse<String> bare = send(gm, "POST", artifacts(gm.campaign()),
                "{\"recordType\":\"note\",\"name\":\"Lembrete\",\"fields\":[],\"content\":\"   \"}");

        assertThat(sheet.statusCode()).as(sheet.body()).isEqualTo(201);
        // Nothing is calculated: the modifier stays as written, though 16 would give +3.
        assertThat(json(sheet).path("fields").get(1).path("value").asString()).isEqualTo("+5");
        assertThat(json(sheet).path("content").isNull()).isTrue();
        assertThat(bare.statusCode()).as(bare.body()).isEqualTo(201);
        assertThat(json(bare).path("fields").size()).isZero();
        assertThat(json(bare).path("content").isNull()).isTrue();
        assertThat(versions.rows.get(json(bare).path("id").asString() + "/1").content()).isNull();
    }

    @Test
    void anApprovedTypeOfTheSystemIsAcceptedAndAnArchivedCampaignStillTakesArtifacts() throws Exception {
        Gm gm = signUp();
        String key = approvedType(gm);
        long version = json(get(gm, "/api/v1/campaigns/" + gm.campaign())).path("version").asLong();
        HttpResponse<String> archived = send(gm, "PATCH", "/api/v1/campaigns/" + gm.campaign(),
                "{\"version\":" + version + ",\"archived\":true}");
        assertThat(archived.statusCode()).as(archived.body()).isEqualTo(200);
        assertThat(json(archived).path("archived").asBoolean()).isTrue();

        HttpResponse<String> created = send(gm, "POST", artifacts(gm.campaign()),
                "{\"recordType\":\"" + key + "\",\"name\":\"Arco élfico\",\"fields\":[]}");

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(json(created).path("recordType").asString()).isEqualTo(key);
        assertThat(ids(json(get(gm, artifacts(gm.campaign()))))).containsExactly(json(created).path("id").asString());
    }

    @Test
    void anApprovedTypeThatAnArtifactUsesIsInUseAndCannotBeDeleted() throws Exception {
        Gm gm = signUp();
        UUID used = approvedTypeId(gm, gm.system(), "arma_magica");
        UUID unused = approvedTypeId(gm, gm.system(), "pocao_rara");
        create(gm, gm.campaign(), "arma_magica", "Arco élfico");

        HttpResponse<String> refused = send(gm, "DELETE", "/api/v1/record-types/" + used, null);

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(json(refused).path("code").asString()).isEqualTo("ontology.record_type_in_use");
        assertThat(get(gm, "/api/v1/record-types/" + used).statusCode()).isEqualTo(200);
        assertThat(send(gm, "DELETE", "/api/v1/record-types/" + unused, null).statusCode()).isEqualTo(204);
    }

    // --- bad input

    @Test
    void inputOutsideItsLimitsIsRefusedNamingItsFieldAndNothingIsStored() throws Exception {
        Gm gm = signUp();
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            many.append(i == 0 ? "" : ",").append("{\"name\":\"C").append(i).append("\",\"value\":\"v\"}");
        }

        assertInvalid(gm, "{\"recordType\":\"item\",\"name\":\"   \",\"fields\":[]}", "name", 1, 200, null);
        assertInvalid(gm, "{\"recordType\":\"item\",\"name\":\"" + "a".repeat(201) + "\",\"fields\":[]}", "name", 1,
                200, null);
        assertInvalid(gm, "{\"recordType\":\"item\",\"name\":\"Espada\",\"fields\":[" + many + "]}", "fields", null, 64,
                null);
        assertInvalid(gm, "{\"recordType\":\"item\",\"name\":\"Espada\",\"fields\":[{\"name\":\"Dano\",\"value\":\"1\"},"
                + "{\"name\":\" \",\"value\":\"2\"}]}", "fieldName", 1, 200, 1);
        assertInvalid(gm, "{\"recordType\":\"item\",\"name\":\"Espada\",\"fields\":[{\"name\":\"Dano\",\"value\":\""
                + "x".repeat(8001) + "\"}]}", "fieldValue", 1, 8000, 0);
        assertInvalid(gm, "{\"recordType\":\"item\",\"name\":\"Espada\",\"fields\":[],\"content\":\""
                + "a".repeat(20_001) + "\"}", "content", 0, 20_000, null);
        assertInvalid(gm, "{\"recordType\":\"weapon\",\"name\":\"Espada\",\"fields\":[]}", "recordType", null, null,
                null);
        assertInvalid(gm, "{\"recordType\":\"Item!\",\"name\":\"Espada\",\"fields\":[]}", "recordType", null, null,
                null);
        // A type approved in another system of the owner is not this campaign's system's.
        assertInvalid(gm, "{\"recordType\":\"" + approvedTypeOfAnotherSystem(gm) + "\",\"name\":\"Espada\","
                + "\"fields\":[]}", "recordType", null, null, null);

        assertThat(artifactsOf(gm)).isEmpty();
        assertThat(versions.rows.values()).noneMatch(row -> row.ownerId().equals(gm.id()));
    }

    @Test
    void theLimitsCountCodePointsSoTheLongestValuesAreSaved() throws Exception {
        Gm gm = signUp();
        String emoji = "🗡";

        HttpResponse<String> created = send(gm, "POST", artifacts(gm.campaign()), "{\"recordType\":\"item\",\"name\":\""
                + emoji.repeat(200) + "\",\"fields\":[{\"name\":\"" + "n".repeat(200) + "\",\"value\":\""
                + emoji.repeat(8000) + "\"}],\"content\":\"" + emoji.repeat(20_000) + "\"}");

        assertThat(created.statusCode()).as(created.body().substring(0, Math.min(200, created.body().length())))
                .isEqualTo(201);
        assertThat(json(created).path("content").asString()).hasSize(40_000);
    }

    @Test
    void aMalformedBodyOrQueryIsInvalidWithoutAField() throws Exception {
        Gm gm = signUp();

        for (String body : List.of("{\"name\":\"Espada\",\"fields\":[]}", "{\"recordType\":\"item\",\"fields\":[]}",
                "{\"recordType\":\"item\",\"name\":\"Espada\"}", "[]", "{")) {
            HttpResponse<String> refused = send(gm, "POST", artifacts(gm.campaign()), body);
            assertThat(refused.statusCode()).as(body + " " + refused.body()).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).isEqualTo("request.invalid");
            assertThat(json(refused).path("params").has("field")).as(body).isFalse();
        }
        for (String query : List.of("limit=0", "limit=51", "limit=x", "offset=-1", "offset=100001", "cursor=nope",
                "recordType=Item", "cursor=" + UUID.randomUUID() + "&offset=10")) {
            HttpResponse<String> refused = get(gm, artifacts(gm.campaign()) + "?" + query);
            assertThat(refused.statusCode()).as(query).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).isEqualTo("request.invalid");
        }
        assertThat(artifactsOf(gm)).isEmpty();
    }

    // --- list

    @Test
    void thirtyArtifactsListNewestFirstAPageAtATimeWithTheTypesInUseAndATypeNarrowsTheList() throws Exception {
        Gm gm = signUp();
        List<String> made = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String type = i % 3 == 0 ? "character_sheet" : i % 3 == 1 ? "item" : "note";
            HttpResponse<String> created = send(gm, "POST", artifacts(gm.campaign()), "{\"recordType\":\"" + type
                    + "\",\"name\":\"Artefato " + i + "\",\"fields\":[]}");
            assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
            made.add(json(created).path("id").asString());
        }
        List<String> newestFirst = made.reversed();

        JsonNode first = json(get(gm, artifacts(gm.campaign())));
        assertThat(ids(first)).isEqualTo(newestFirst.subList(0, 20));
        assertThat(first.path("total").asLong()).isEqualTo(30);
        assertThat(first.path("items").get(0).path("name").asString()).isEqualTo("Artefato 29");
        assertThat(first.path("items").get(0).path("recordType").asString()).isEqualTo("note");
        assertThat(first.path("items").get(0).path("version").asInt()).isEqualTo(1);
        assertThat(first.path("types").toString()).isEqualTo("[{\"key\":\"character_sheet\",\"count\":10},"
                + "{\"key\":\"item\",\"count\":10},{\"key\":\"note\",\"count\":10}]");
        assertThat(first.path("nextCursor").asString()).isEqualTo(newestFirst.get(19));

        JsonNode second = json(get(gm, artifacts(gm.campaign()) + "?cursor=" + first.path("nextCursor").asString()));
        assertThat(ids(second)).isEqualTo(newestFirst.subList(20, 30));
        assertThat(second.has("nextCursor")).isFalse();
        JsonNode numbered = json(get(gm, artifacts(gm.campaign()) + "?limit=10&offset=10"));
        assertThat(ids(numbered)).isEqualTo(newestFirst.subList(10, 20));
        assertThat(numbered.path("total").asLong()).isEqualTo(30);

        JsonNode items = json(get(gm, artifacts(gm.campaign()) + "?recordType=item&limit=50"));
        assertThat(items.path("total").asLong()).isEqualTo(10);
        assertThat(ids(items)).hasSize(10).allMatch(id -> made.indexOf(id) % 3 == 1);
        // The chips still show every type in use, whatever the filter.
        assertThat(items.path("types").size()).isEqualTo(3);
        JsonNode none = json(get(gm, artifacts(gm.campaign()) + "?recordType=spell"));
        assertThat(none.path("items").size()).isZero();
        assertThat(none.path("total").asLong()).isZero();
    }

    @Test
    void aCampaignWithoutArtifactsListsNothing() throws Exception {
        Gm gm = signUp();

        HttpResponse<String> list = get(gm, artifacts(gm.campaign()));

        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        assertThat(list.body()).isEqualTo("{\"items\":[],\"total\":0,\"types\":[]}");
    }

    @Test
    void aCampaignsCharacterSheetsListByTheirTypeAndEachOpens() throws Exception {
        Gm gm = signUp();
        String first = create(gm, gm.campaign(), "character_sheet", "Kael");
        create(gm, gm.campaign(), "item", "Espada de Kael");
        String second = create(gm, gm.campaign(), "character_sheet", "Lyra");

        JsonNode sheets = json(get(gm, artifacts(gm.campaign()) + "?recordType=character_sheet"));

        assertThat(ids(sheets)).containsExactly(second, first);
        assertThat(sheets.path("items").get(0).path("name").asString()).isEqualTo("Lyra");
        assertThat(get(gm, artifacts(gm.campaign()) + "/" + first).statusCode()).isEqualTo(200);
    }

    // --- other owner, other campaign

    @Test
    void anotherOwnersCampaignOrArtifactIsNotFoundAndNothingIsStored() throws Exception {
        Gm a = signUp();
        Gm b = signUp();
        String theirs = create(a, a.campaign(), "item", "Espada de Kael");
        String random = UUID.randomUUID().toString();

        assertNotFound(get(b, artifacts(a.campaign())));
        assertNotFound(get(b, artifacts(a.campaign()) + "/" + theirs));
        assertNotFound(get(b, artifacts(b.campaign()) + "/" + theirs));
        assertNotFound(send(b, "POST", artifacts(a.campaign()), KAEL));
        assertNotFound(get(b, artifacts(UUID.fromString(random))));
        assertNotFound(get(b, "/api/v1/campaigns/nope/artifacts"));
        assertNotFound(get(b, artifacts(b.campaign()) + "/nope"));
        assertThat(get(b, artifacts(a.campaign()) + "/" + theirs).body().replaceAll("\"traceId\":\"[^\"]*\"", ""))
                .isEqualTo(get(b, artifacts(a.campaign()) + "/" + random).body()
                        .replaceAll("\"traceId\":\"[^\"]*\"", ""));
        assertThat(artifactsOf(b)).isEmpty();
        assertThat(json(get(b, artifacts(b.campaign()))).path("total").asLong()).isZero();
        assertThat(get(a, artifacts(a.campaign()) + "/" + theirs).statusCode()).isEqualTo(200);
    }

    @Test
    void anArtifactUnderAnotherCampaignOfTheSameOwnerIsNotFound() throws Exception {
        Gm gm = signUp();
        UUID other = UUID.fromString(json(send(gm, "POST", "/api/v1/campaigns", "{\"name\":\"Outra mesa\","
                + "\"systemId\":\"" + gm.system() + "\"}")).path("id").asString());
        String id = create(gm, gm.campaign(), "item", "Espada de Kael");

        assertNotFound(get(gm, artifacts(other) + "/" + id));
        assertThat(json(get(gm, artifacts(other))).path("total").asLong()).isZero();
        assertThat(get(gm, artifacts(gm.campaign()) + "/" + id).statusCode()).isEqualTo(200);
    }

    // --- an artifact is never a system's record

    @Test
    void anArtifactNeverShowsInTheSystemsRecordsOrTheReadsOfAnswersAndSearches() throws Exception {
        Gm gm = signUp();
        String id = create(gm, gm.campaign(), "item", "Espada de Kael");
        JsonNode item = recordType(gm, "item");

        assertThat(item.path("records").asLong()).isZero();
        JsonNode listed = json(get(gm, "/api/v1/record-types/" + item.path("id").asString() + "/records"));
        assertThat(listed.path("total").asLong()).isZero();
        assertNotFound(get(gm, "/api/v1/systems/" + gm.system() + "/records/" + id));
        assertNotFound(get(gm, "/api/v1/campaigns/" + gm.campaign() + "/records/" + id));
        assertNotFound(send(gm, "PATCH", "/api/v1/campaigns/" + gm.campaign() + "/records/" + id,
                "{\"version\":0,\"name\":\"X\",\"recordType\":\"item\",\"fields\":[]}"));
        assertNotFound(send(gm, "DELETE", "/api/v1/systems/" + gm.system() + "/records/" + id, null));
        // The port the answers and the searches read records through (Stories 4.3 part B and C) never answers it.
        UUID artifact = UUID.fromString(id);
        OwnerContext.runAs(gm.id(), () -> {
            assertThat(extracted.located(List.of(artifact), ExtractedRecords.Reach.ALL)).isEmpty();
            assertThat(extracted.read(List.of(artifact), ExtractedRecords.Reach.ALL)).isEmpty();
            assertThat(extracted.names(List.of(artifact))).isEmpty();
            assertThat(extracted.deleted(List.of(artifact))).isEmpty();
        });
        assertThat(get(gm, artifacts(gm.campaign()) + "/" + id).statusCode()).isEqualTo(200);
    }

    // --- a campaign's deletion

    @Test
    void bothDialogsCountTheArtifactsAndACampaignsDeletionTakesItsOwnAndLeavesTheOthers() throws Exception {
        Gm gm = signUp();
        UUID other = UUID.fromString(json(send(gm, "POST", "/api/v1/campaigns", "{\"name\":\"Outra mesa\","
                + "\"systemId\":\"" + gm.system() + "\"}")).path("id").asString());
        String going = create(gm, other, "item", "Espada de Kael");
        String staying = create(gm, gm.campaign(), "item", "Escudo");
        create(gm, other, "note", "Lembrete");

        // Both dialogs name the artifacts that go: the campaign's own, and every campaign's of the system.
        JsonNode campaignDialog = json(get(gm, "/api/v1/campaigns/" + other + "/deletion"));
        assertThat(campaignDialog.path("artifacts").asLong()).as(campaignDialog.toString()).isEqualTo(2);
        JsonNode systemDialog = json(get(gm, "/api/v1/systems/" + gm.system() + "/deletion"));
        assertThat(systemDialog.path("artifacts").asLong()).as(systemDialog.toString()).isEqualTo(3);
        assertThat(json(get(gm, "/api/v1/campaigns/" + gm.campaign() + "/deletion")).path("artifacts").asLong())
                .isEqualTo(1);

        HttpResponse<String> deleted = send(gm, "DELETE", "/api/v1/campaigns/" + other, null);
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(202);
        assertNotFound(get(gm, artifacts(other)));
        deletion.sweep();

        assertThat(library.campaigns).doesNotContainKey(other);
        assertThat(records.artifacts).doesNotContainKey(UUID.fromString(going)).containsKey(UUID.fromString(staying));
        assertThat(versions.rows).doesNotContainKey(going + "/1").containsKey(staying + "/1");
    }

    // --- helpers

    private void assertInvalid(Gm gm, String body, String field, Integer min, Integer max, Integer index)
            throws Exception {
        HttpResponse<String> refused = send(gm, "POST", artifacts(gm.campaign()), body);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
        JsonNode problem = json(refused);
        assertThat(problem.path("code").asString()).isEqualTo("request.invalid");
        assertThat(problem.path("params").path("field").asString()).isEqualTo(field);
        if (min != null) {
            assertThat(problem.path("params").path("min").asInt()).isEqualTo(min);
        }
        if (max != null) {
            assertThat(problem.path("params").path("max").asInt()).isEqualTo(max);
        }
        if (index != null) {
            assertThat(problem.path("params").path("index").asInt()).isEqualTo(index);
        }
        // No param carries what was written.
        assertThat(problem.path("params").toString()).doesNotContain("Espada").doesNotContain("aaaa");
    }

    private static void assertNotFound(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
        assertThat(JSON.readTree(response.body()).path("code").asString()).isEqualTo("request.not_found");
        assertThat(response.body()).doesNotContain("Kael");
    }

    private List<ArtifactRow> artifactsOf(Gm gm) {
        return records.artifacts.values().stream().filter(row -> row.ownerId().equals(gm.id())).toList();
    }

    private String create(Gm gm, UUID campaign, String type, String name) throws Exception {
        HttpResponse<String> created = send(gm, "POST", artifacts(campaign), "{\"recordType\":\"" + type
                + "\",\"name\":\"" + name + "\",\"fields\":[]}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return json(created).path("id").asString();
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asString()));
        return ids;
    }

    private JsonNode recordType(Gm gm, String core) throws Exception {
        for (JsonNode type : json(get(gm, "/api/v1/systems/" + gm.system() + "/record-types?limit=50")).path("items")) {
            if (core.equals(type.path("core").asString(null))) {
                return type;
            }
        }
        throw new AssertionError("no " + core + " type");
    }

    /** A record type approved in the GM's system (Story 4.4), stored as the approval stores it; its key. */
    private String approvedType(Gm gm) {
        return approvedIn(gm, gm.system(), "arma_magica");
    }

    /** A record type approved in another system of the GM; its key. */
    private String approvedTypeOfAnotherSystem(Gm gm) throws Exception {
        UUID system = UUID.fromString(json(send(gm, "POST", "/api/v1/systems", "{\"name\":\"Outro sistema\"}"))
                .path("id").asString());
        return approvedIn(gm, system, "pocao_rara");
    }

    /** A record type the GM approved in {@code system} with this key (Story 4.4), stored directly: its key. */
    private String approvedIn(Gm gm, UUID system, String key) {
        approvedTypeId(gm, system, key);
        return key;
    }

    /** A record type the GM approved in {@code system} with this key (Story 4.4), stored directly: its id. */
    private UUID approvedTypeId(Gm gm, UUID system, String key) {
        UUID id = Ids.newId();
        String name = "Tipo " + key;
        OwnerContext.runAs(gm.id(), () -> knowledge.insertRecordTypes(List.of(new RecordTypeRow(id, gm.id(), system,
                null, key, Ids.newId(), vault.encryptText(new Binding("record_type", "name", id, gm.id()), name),
                vault.fingerprint("record_type", "name", Library.uniqueForm(name)), 0, Instant.now()))));
        return id;
    }

    private static String artifacts(UUID campaign) {
        return "/api/v1/campaigns/" + campaign + "/artifacts";
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
        String sub = "art-" + UUID.randomUUID();
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
