package io.github.gmshield.artifacts.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.accounts.data.AccountRow;
import io.github.gmshield.accounts.data.AccountStore;
import io.github.gmshield.accounts.data.InMemoryAccountStore;
import io.github.gmshield.accounts.data.InMemorySessionStore;
import io.github.gmshield.accounts.data.InMemorySignInAttemptStore;
import io.github.gmshield.accounts.data.SessionStore;
import io.github.gmshield.accounts.data.SignInAttemptStore;
import io.github.gmshield.artifacts.data.ArtifactReferenceRow;
import io.github.gmshield.artifacts.data.ArtifactVersionRow;
import io.github.gmshield.artifacts.data.InMemoryArtifactReferenceStore;
import io.github.gmshield.artifacts.data.InMemoryArtifactShareStore;
import io.github.gmshield.artifacts.data.InMemoryArtifactIndexStore;
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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
 *
 * <p>Story 5.2 part A, every row of its matrix: an edit stores version 2 and keeps version 1 (a refused one stores
 * nothing); three versions list newest first a page at a time; a restore copies an earlier version's type, name, fields
 * and content as the newest, and a stale one is refused; a cited version opens as it was (an unknown number is not
 * found); a stale edit from a second tab is {@code request.stale_version} and saves once resent on the newest version;
 * another owner's and another campaign's artifact or version are not found for each new route. Besides the matrix: a
 * restore of a version whose type the system no longer has names the record type, and a malformed edit, restore or
 * versions query is {@code request.invalid}. The index order is {@code ArtifactIndexingTest}'s.
 *
 * <p>Story 5.2 part B: a delete is 202 (no body, {@code no-store}) and the artifact leaves every read, list and count
 * at once, then its pass removes its identity, versions and index rows; a second delete or a read of it is not found;
 * another owner's or another campaign's artifact is not found to a delete and nothing changes; a deleting artifact
 * leaves both deletion dialogs' counts and the type-in-use guard, and a campaign's deletion still removes it. The claim
 * wait and the removal job are {@code ArtifactIndexingTest}'s, the answers {@code RecordsFlowTest}'s.
 *
 * <p>Story 5.4 part A: an artifact written by hand reads with no conversation and no references; a saved draft's copied
 * references (stored here as a save stores them, sealed under the artifact) are read decrypted and in order, each
 * removed as of the read (an unknown version, a deleted cited artifact), kept by an edit and a restore and by a version
 * read, and removed with the artifact by its deletion's pass. The save itself is {@code ConversationFlowTest}'s.
 *
 * <p>Story 5.6 part A, the API rows of its matrix: an artifact is shared with the owner's other campaigns of its system
 * (an archived one included) by the whole set, listed by name, and unshared by an empty set, with one row per pair and
 * no copy, version or index job; its own campaign, another system's, another owner's, an unknown, malformed or null id
 * is {@code artifact.share_target} and nothing changes; a malformed body or over 200 ids is {@code request.invalid}; in
 * a campaign it is shared into it reads read-only (newest or a cited version, naming its own campaign, with no mark,
 * relation, sharing or conversation) and every other route is not found there; unshared or deleting it is not found
 * there, and its deletion's pass takes its shares; another owner finds none of it. The scope rows are
 * {@code RecordsFlowTest}'s, the cross-owner ones {@code CrossOwnerAuditFlowTest}'s.
 *
 * <p>Story 5.7 part A, every API row of its matrix: one PDF prints the name, type, fields, content and numbered sources
 * with its accents and a removed source marked (in pt-BR and en, read back with {@code PDFTextStripper}); several
 * artifacts make one Markdown, TXT or PDF file in the order given; one JPG is one image and several a zip of one image
 * each in order, and a 20,000-character artifact is one tall image of all its pages; an artifact shared into the
 * campaign is exported there; any unreadable id (another owner's, unknown, another campaign's, deleting, malformed)
 * makes the whole export {@code request.not_found}; 51 ids, a duplicate, an empty one, a bad format or language is
 * {@code request.invalid}; a PDF or JPG of more than 200 pages in all is {@code artifact.export_too_large} while TXT
 * and Markdown still work (the busy gate is {@code ArtifactExportsTest}'s). Besides the matrix: a name is made safe
 * for the file (a name beyond ASCII sent in both forms), an approved type prints its name, and Markdown prints the text
 * as text. That nothing is written to disk is {@code ArtifactExportsTest}'s.
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
    private InMemoryArtifactIndexStore index;
    @Autowired
    private InMemoryArtifactReferenceStore references;
    @Autowired
    private InMemoryArtifactShareStore shares;
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
        // Story 5.4 part A: written by hand, so no conversation and no references.
        assertThat(body.has("conversationId")).isTrue();
        assertThat(body.path("conversationId").isNull()).isTrue();
        assertThat(body.path("references").isArray()).isTrue();
        assertThat(body.path("references")).isEmpty();
        // Story 6.2 part A: no Shield holds an artifact written here.
        assertThat(body.has("shieldId")).isTrue();
        assertThat(body.path("shieldId").isNull()).isTrue();

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

    // --- edit and versions (Story 5.2)

    @Test
    void anEditStoresVersionTwoKeepsVersionOneAndARefusedEditStoresNothing() throws Exception {
        Gm gm = signUp();
        approvedIn(gm, gm.system(), "weapon");
        String id = json(send(gm, "POST", artifacts(gm.campaign()), KAEL)).path("id").asString();

        HttpResponse<String> edited = send(gm, "PATCH", artifacts(gm.campaign()) + "/" + id, "{\"version\":1,"
                + "\"recordType\":\"weapon\",\"name\":\" Espada de Kael, a Rubra \",\"fields\":[{\"name\":\"Dano\","
                + "\"value\":\"2d6 fogo\"}],\"content\":\"Reforjada no vulcão.\"}");

        // Matrix row "Edit": version 2, with the new type, name, field and content.
        assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
        assertThat(edited.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode body = json(edited);
        assertThat(body.path("id").asString()).isEqualTo(id);
        assertThat(body.path("version").asInt()).isEqualTo(2);
        assertThat(body.path("currentVersion").asInt()).isEqualTo(2);
        assertThat(body.path("recordType").asString()).isEqualTo("weapon");
        assertThat(body.path("name").asString()).isEqualTo("Espada de Kael, a Rubra");
        assertThat(body.path("fields").toString()).isEqualTo("[{\"name\":\"Dano\",\"value\":\"2d6 fogo\"}]");
        assertThat(body.path("content").asString()).isEqualTo("Reforjada no vulcão.");
        assertThat(json(get(gm, artifacts(gm.campaign()) + "/" + id))).isEqualTo(body);
        // The identity mirrors the newest version's type, so the list and its chips read it.
        assertThat(records.artifacts.get(UUID.fromString(id)).type()).isEqualTo("weapon");
        JsonNode listed = json(get(gm, artifacts(gm.campaign())));
        assertThat(listed.path("items").get(0).path("recordType").asString()).isEqualTo("weapon");
        assertThat(listed.path("items").get(0).path("version").asInt()).isEqualTo(2);
        assertThat(listed.path("types").toString()).isEqualTo("[{\"key\":\"weapon\",\"count\":1}]");
        // Version 1 is kept as it was, encrypted, with its own type.
        ArtifactVersionRow first = versions.rows.get(id + "/1");
        assertThat(first.recordType()).isEqualTo("item");
        ArtifactVersionRow second = versions.rows.get(id + "/2");
        assertThat(second.recordType()).isEqualTo("weapon");
        assertThat(new String(second.name(), StandardCharsets.ISO_8859_1)).doesNotContain("Rubra");
        assertThat(new String(second.content(), StandardCharsets.ISO_8859_1)).doesNotContain("vulcão");
        JsonNode kept = json(get(gm, artifacts(gm.campaign()) + "/" + id + "/versions/1"));
        assertThat(kept.path("name").asString()).isEqualTo("Espada de Kael");
        assertThat(kept.path("recordType").asString()).isEqualTo("item");

        // Bad input: 400 on that field, nothing stored.
        for (String bad : List.of("{\"version\":2,\"recordType\":\"item\",\"name\":\"  \",\"fields\":[]}",
                "{\"version\":2,\"recordType\":\"nope\",\"name\":\"Espada\",\"fields\":[]}",
                "{\"version\":2,\"recordType\":\"item\",\"name\":\"Espada\",\"fields\":[{\"name\":\"Dano\","
                        + "\"value\":\" \"}]}",
                "{\"version\":2,\"recordType\":\"item\",\"name\":\"Espada\",\"fields\":[],\"content\":\""
                        + "a".repeat(20_001) + "\"}")) {
            HttpResponse<String> refused = send(gm, "PATCH", artifacts(gm.campaign()) + "/" + id, bad);
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).isEqualTo("request.invalid");
            assertThat(json(refused).path("params").has("field")).isTrue();
        }
        assertThat(versionsOf(id)).isEqualTo(2);
        assertThat(records.artifacts.get(UUID.fromString(id)).type()).isEqualTo("weapon");
    }

    @Test
    void anEditThatChangesNothingAnswersTheNewestVersionAndStoresNoVersionAndNoIndexJob() throws Exception {
        Gm gm = signUp();
        String id = json(send(gm, "POST", artifacts(gm.campaign()), KAEL)).path("id").asString();
        JsonNode before = json(get(gm, artifacts(gm.campaign()) + "/" + id));
        String same = "{\"version\":1,\"recordType\":\"item\",\"name\":\"Espada de Kael \",\"fields\":["
                + "{\"name\":\" Dano\",\"value\":\"1d8 cortante\"},{\"name\":\"Peso\",\"value\":\"1,5 kg \"}],"
                + "\"content\":\" Forjada nas montanhas do norte.\\nBrilha perto de orcs. \"}";

        HttpResponse<String> unchanged = send(gm, "PATCH", artifacts(gm.campaign()) + "/" + id, same);

        // The same type, name, fields and content after trimming: version 1 as it is, nothing stored, no new job.
        assertThat(unchanged.statusCode()).as(unchanged.body()).isEqualTo(200);
        assertThat(json(unchanged)).isEqualTo(before);
        assertThat(versionsOf(id)).isEqualTo(1);
        assertThat(index.rows.values()).filteredOn(row -> row.recordId().toString().equals(id)).hasSize(1);
        // A stale version is still refused, and a real change still stores version 2.
        assertThat(send(gm, "PATCH", artifacts(gm.campaign()) + "/" + id, same.replace("\"version\":1",
                "\"version\":2")).statusCode()).isEqualTo(409);
        assertThat(send(gm, "PATCH", artifacts(gm.campaign()) + "/" + id, same.replace("Kael ", "Kael, a Rubra"))
                .statusCode()).isEqualTo(200);
        assertThat(versionsOf(id)).isEqualTo(2);
        assertThat(index.rows.values()).filteredOn(row -> row.recordId().toString().equals(id)).hasSize(2);
    }

    @Test
    void threeVersionsListNewestFirstAPageAtATimeWithTheirTotal() throws Exception {
        Gm gm = signUp();
        String id = create(gm, gm.campaign(), "item", "Espada");
        edit(gm, id, 1, "note", "Espada lembrada");
        edit(gm, id, 2, "item", "Espada de Kael");

        JsonNode all = json(get(gm, artifacts(gm.campaign()) + "/" + id + "/versions"));

        // Matrix row "Versions": number, type, name and time, newest first.
        assertThat(all.path("total").asLong()).isEqualTo(3);
        assertThat(all.has("nextCursor")).isFalse();
        assertThat(numbers(all)).containsExactly(3, 2, 1);
        JsonNode newest = all.path("items").get(0);
        assertThat(fieldNames(newest)).containsExactly("number", "recordType", "name", "createdAt");
        assertThat(newest.path("name").asString()).isEqualTo("Espada de Kael");
        assertThat(all.path("items").get(1).path("recordType").asString()).isEqualTo("note");
        assertThat(all.path("items").get(2).path("name").asString()).isEqualTo("Espada");
        // The current one is the artifact's currentVersion.
        assertThat(json(get(gm, artifacts(gm.campaign()) + "/" + id)).path("currentVersion").asInt()).isEqualTo(3);

        JsonNode page = json(get(gm, artifacts(gm.campaign()) + "/" + id + "/versions?limit=2"));
        assertThat(numbers(page)).containsExactly(3, 2);
        assertThat(page.path("nextCursor").asInt()).isEqualTo(2);
        JsonNode rest = json(get(gm, artifacts(gm.campaign()) + "/" + id + "/versions?limit=2&cursor=2"));
        assertThat(numbers(rest)).containsExactly(1);
        assertThat(rest.has("nextCursor")).isFalse();
        JsonNode numbered = json(get(gm, artifacts(gm.campaign()) + "/" + id + "/versions?limit=2&offset=2"));
        assertThat(numbers(numbered)).containsExactly(1);
        assertThat(numbered.path("total").asLong()).isEqualTo(3);
    }

    @Test
    void aRestoreCopiesAnEarlierVersionAsTheNewestAndAStaleOneIsRefused() throws Exception {
        Gm gm = signUp();
        String id = json(send(gm, "POST", artifacts(gm.campaign()), KAEL)).path("id").asString();
        edit(gm, id, 1, "note", "Espada lembrada");
        edit(gm, id, 2, "character_sheet", "Kael");

        // Stale: the GM saw version 2.
        HttpResponse<String> stale = send(gm, "POST", artifacts(gm.campaign()) + "/" + id + "/versions/1/restore",
                "{\"version\":2}");
        assertThat(stale.statusCode()).as(stale.body()).isEqualTo(409);
        assertThat(json(stale).path("code").asString()).isEqualTo("request.stale_version");
        assertThat(versionsOf(id)).isEqualTo(3);

        HttpResponse<String> restored = send(gm, "POST", artifacts(gm.campaign()) + "/" + id
                + "/versions/1/restore", "{\"version\":3}");

        // Matrix row "Restore": version 4 holds version 1's type, name, fields and content; 1 to 3 are kept.
        assertThat(restored.statusCode()).as(restored.body()).isEqualTo(200);
        JsonNode body = json(restored);
        assertThat(body.path("version").asInt()).isEqualTo(4);
        assertThat(body.path("currentVersion").asInt()).isEqualTo(4);
        assertThat(body.path("recordType").asString()).isEqualTo("item");
        assertThat(body.path("name").asString()).isEqualTo("Espada de Kael");
        assertThat(body.path("fields").toString())
                .isEqualTo("[{\"name\":\"Dano\",\"value\":\"1d8 cortante\"},{\"name\":\"Peso\",\"value\":\"1,5 kg\"}]");
        assertThat(body.path("content").asString()).startsWith("Forjada nas montanhas do norte.");
        assertThat(versionsOf(id)).isEqualTo(4);
        assertThat(records.artifacts.get(UUID.fromString(id)).type()).isEqualTo("item");
        assertThat(json(get(gm, artifacts(gm.campaign()) + "/" + id + "/versions/3")).path("name").asString())
                .isEqualTo("Kael");
        assertThat(numbers(json(get(gm, artifacts(gm.campaign()) + "/" + id + "/versions"))))
                .containsExactly(4, 3, 2, 1);
        // An unknown number is not found.
        assertNotFound(send(gm, "POST", artifacts(gm.campaign()) + "/" + id + "/versions/9/restore",
                "{\"version\":4}"));
        assertThat(versionsOf(id)).isEqualTo(4);
    }

    @Test
    void aRestoreOfAVersionWhoseTypeTheSystemNoLongerHasNamesTheRecordType() throws Exception {
        Gm gm = signUp();
        UUID type = approvedTypeId(gm, gm.system(), "arma_magica");
        String id = create(gm, gm.campaign(), "arma_magica", "Arco élfico");
        edit(gm, id, 1, "item", "Arco élfico");
        // No artifact has the type any more, so it can be deleted.
        assertThat(send(gm, "DELETE", "/api/v1/record-types/" + type, null).statusCode()).isEqualTo(204);

        HttpResponse<String> refused = send(gm, "POST", artifacts(gm.campaign()) + "/" + id
                + "/versions/1/restore", "{\"version\":2}");

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
        assertThat(json(refused).path("code").asString()).isEqualTo("request.invalid");
        assertThat(json(refused).path("params").path("field").asString()).isEqualTo("recordType");
        assertThat(versionsOf(id)).isEqualTo(2);
        // Version 1 still opens with the type it had.
        assertThat(json(get(gm, artifacts(gm.campaign()) + "/" + id + "/versions/1")).path("recordType").asString())
                .isEqualTo("arma_magica");
    }

    @Test
    void aCitedVersionOpensAsItWasAndAnUnknownNumberIsNotFound() throws Exception {
        Gm gm = signUp();
        String id = json(send(gm, "POST", artifacts(gm.campaign()), KAEL)).path("id").asString();
        edit(gm, id, 1, "note", "Espada lembrada");

        HttpResponse<String> cited = get(gm, artifacts(gm.campaign()) + "/" + id + "/versions/1");

        // Matrix row "Cited version": version 1's content, with the newest number beside it.
        assertThat(cited.statusCode()).as(cited.body()).isEqualTo(200);
        assertThat(cited.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode body = json(cited);
        assertThat(body.path("version").asInt()).isEqualTo(1);
        assertThat(body.path("currentVersion").asInt()).isEqualTo(2);
        assertThat(body.path("recordType").asString()).isEqualTo("item");
        assertThat(body.path("name").asString()).isEqualTo("Espada de Kael");
        assertThat(body.path("content").asString()).startsWith("Forjada");
        JsonNode newest = json(get(gm, artifacts(gm.campaign()) + "/" + id + "/versions/2"));
        assertThat(newest).isEqualTo(json(get(gm, artifacts(gm.campaign()) + "/" + id)));
        for (String number : List.of("9", "0", "01", "x", "-1", "1234567890")) {
            assertNotFound(get(gm, artifacts(gm.campaign()) + "/" + id + "/versions/" + number));
        }
    }

    @Test
    void aStaleEditFromASecondTabIsAConflictAndSavesOnceResentOnTheNewestVersion() throws Exception {
        Gm gm = signUp();
        String id = create(gm, gm.campaign(), "item", "Espada");
        edit(gm, id, 1, "item", "Espada de Kael");
        // Tabs A and B are both on version 2; A saves first.
        edit(gm, id, 2, "item", "Espada da aba A");

        String tabB = "{\"version\":2,\"recordType\":\"note\",\"name\":\"Espada da aba B\",\"fields\":[]}";
        HttpResponse<String> refused = send(gm, "PATCH", artifacts(gm.campaign()) + "/" + id, tabB);

        // Matrix row "Stale edit": B gets 409 and nothing is stored.
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(json(refused).path("code").asString()).isEqualTo("request.stale_version");
        assertThat(refused.body()).doesNotContain("aba");
        assertThat(versionsOf(id)).isEqualTo(3);
        assertThat(json(get(gm, artifacts(gm.campaign()) + "/" + id)).path("name").asString())
                .isEqualTo("Espada da aba A");

        // "Manter a minha como nova versão": the same draft on the newest version.
        HttpResponse<String> kept = send(gm, "PATCH", artifacts(gm.campaign()) + "/" + id,
                tabB.replace("\"version\":2", "\"version\":3"));
        assertThat(kept.statusCode()).as(kept.body()).isEqualTo(200);
        assertThat(json(kept).path("version").asInt()).isEqualTo(4);
        assertThat(json(kept).path("name").asString()).isEqualTo("Espada da aba B");
        assertThat(json(kept).path("recordType").asString()).isEqualTo("note");
        // A version ahead of the newest is stale too.
        assertThat(send(gm, "PATCH", artifacts(gm.campaign()) + "/" + id, tabB.replace("\"version\":2",
                "\"version\":9")).statusCode()).isEqualTo(409);
    }

    @Test
    void aMalformedEditRestoreOrVersionsQueryIsInvalid() throws Exception {
        Gm gm = signUp();
        String id = create(gm, gm.campaign(), "item", "Espada");
        String artifact = artifacts(gm.campaign()) + "/" + id;

        for (String body : List.of("{\"recordType\":\"item\",\"name\":\"Espada\",\"fields\":[]}",
                "{\"version\":0,\"recordType\":\"item\",\"name\":\"Espada\",\"fields\":[]}",
                "{\"version\":1,\"name\":\"Espada\",\"fields\":[]}", "{\"version\":1,\"recordType\":\"item\","
                        + "\"fields\":[]}", "{\"version\":1,\"recordType\":\"item\",\"name\":\"Espada\"}", "[]", "{")) {
            HttpResponse<String> refused = send(gm, "PATCH", artifact, body);
            assertThat(refused.statusCode()).as(body + " " + refused.body()).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).isEqualTo("request.invalid");
        }
        for (String body : List.of("{}", "{\"version\":0}", "{\"version\":\"x\"}", "[]")) {
            HttpResponse<String> refused = send(gm, "POST", artifact + "/versions/1/restore", body);
            assertThat(refused.statusCode()).as(body + " " + refused.body()).isEqualTo(400);
        }
        for (String query : List.of("limit=0", "limit=51", "offset=-1", "cursor=0", "cursor=x", "cursor=2&offset=1")) {
            HttpResponse<String> refused = get(gm, artifact + "/versions?" + query);
            assertThat(refused.statusCode()).as(query).isEqualTo(400);
        }
        assertThat(versionsOf(id)).isEqualTo(1);
    }

    @Test
    void anotherOwnersOrAnotherCampaignsArtifactOrVersionIsNotFoundOnEveryNewRoute() throws Exception {
        Gm a = signUp();
        Gm b = signUp();
        String theirs = create(a, a.campaign(), "item", "Espada de Kael");
        UUID other = UUID.fromString(json(send(a, "POST", "/api/v1/campaigns", "{\"name\":\"Outra mesa\","
                + "\"systemId\":\"" + a.system() + "\"}")).path("id").asString());
        String edit = "{\"version\":1,\"recordType\":\"note\",\"name\":\"Tomado\",\"fields\":[]}";

        // Matrix row "Other owner or campaign": B edits, lists, reads or restores A's artifact or version.
        for (String path : List.of(artifacts(a.campaign()) + "/" + theirs, artifacts(b.campaign()) + "/" + theirs)) {
            assertNotFound(send(b, "PATCH", path, edit));
            assertNotFound(get(b, path + "/versions"));
            assertNotFound(get(b, path + "/versions/1"));
            assertNotFound(send(b, "POST", path + "/versions/1/restore", "{\"version\":1}"));
        }
        // A's own artifact under A's other campaign.
        String underOther = artifacts(other) + "/" + theirs;
        assertNotFound(send(a, "PATCH", underOther, edit));
        assertNotFound(get(a, underOther + "/versions"));
        assertNotFound(get(a, underOther + "/versions/1"));
        assertNotFound(send(a, "POST", underOther + "/versions/1/restore", "{\"version\":1}"));
        assertThat(versionsOf(theirs)).isEqualTo(1);
        assertThat(json(get(a, artifacts(a.campaign()) + "/" + theirs)).path("name").asString())
                .isEqualTo("Espada de Kael");
    }

    // --- a saved draft's references (Story 5.4 part A)

    @Test
    void aSavedDraftsReferencesAreReadAsOfNowAndKeptByAnEditARestoreAndAVersionRead() throws Exception {
        Gm gm = signUp();
        String cited = create(gm, gm.campaign(), "creature", "Esqueleto");
        String id = create(gm, gm.campaign(), "encounter", "Emboscada");
        UUID artifact = UUID.fromString(id);
        UUID passage = Ids.newId();
        UUID version = Ids.newId();
        UUID record = Ids.newId();
        // As a save stores them: a passage's place (its version unknown here, so removed), a record's and an artifact's.
        OwnerContext.runAs(gm.id(), () -> references.insert(List.of(
                reference(gm, artifact, 1, version, passage, null, null, null),
                reference(gm, artifact, 2, version, passage, record, "spell", null),
                reference(gm, artifact, 3, null, null, UUID.fromString(cited), "creature", 1))));
        String path = artifacts(gm.campaign()) + "/" + id;

        JsonNode read = json(get(gm, path)).path("references");

        assertThat(read).hasSize(3);
        assertThat(read.get(0).path("kind").asString()).isEqualTo("passage");
        assertThat(read.get(0).path("materialName").asString()).isEqualTo("SRD Livro");
        assertThat(read.get(0).path("sectionPath").toString()).isEqualTo("[\"Magias\",\"Fire Bolt\"]");
        assertThat(read.get(0).path("page").asInt()).isEqualTo(3);
        assertThat(read.get(0).path("passageIds").toString()).isEqualTo("[\"" + passage + "\"]");
        assertThat(read.get(0).path("tier").asString()).isEqualTo("system");
        // A version that is gone reads as removed.
        assertThat(read.get(0).path("removed").asBoolean()).isTrue();
        assertThat(read.get(0).has("recordId")).isFalse();
        assertThat(read.get(1).path("kind").asString()).isEqualTo("record");
        assertThat(read.get(1).path("recordId").asString()).isEqualTo(record.toString());
        assertThat(read.get(1).path("name").asString()).isEqualTo("Nome 2");
        assertThat(read.get(1).path("fields").toString()).isEqualTo("[{\"name\":\"Dano\",\"value\":\"1d10\"}]");
        assertThat(read.get(2).path("kind").asString()).isEqualTo("artifact");
        assertThat(read.get(2).path("artifactId").asString()).isEqualTo(cited);
        assertThat(read.get(2).path("artifactVersion").asInt()).isEqualTo(1);
        assertThat(read.get(2).path("removed").asBoolean()).isFalse();
        assertThat(read.get(2).has("materialName")).isFalse();
        assertThat(read.get(2).has("page")).isFalse();

        // An edit and a restore keep them; so does reading the earlier version.
        edit(gm, id, 1, "note", "Emboscada II");
        assertThat(json(get(gm, path)).path("references")).isEqualTo(read);
        HttpResponse<String> restored = send(gm, "POST", path + "/versions/1/restore", "{\"version\":2}");
        assertThat(restored.statusCode()).as(restored.body()).isEqualTo(200);
        assertThat(json(restored).path("references")).isEqualTo(read);
        assertThat(json(get(gm, path + "/versions/1")).path("references")).isEqualTo(read);

        // The cited artifact deleted: its reference reads as removed, the content unchanged.
        assertThat(send(gm, "DELETE", artifacts(gm.campaign()) + "/" + cited, null).statusCode()).isEqualTo(202);
        assertThat(json(get(gm, path)).path("references").get(2).path("removed").asBoolean()).isTrue();

        // The artifact deleted: its pass removes its references with it.
        assertThat(send(gm, "DELETE", path, null).statusCode()).isEqualTo(202);
        deletion.sweep();
        assertThat(references.rows.values()).noneMatch(row -> row.recordId().equals(artifact));
    }

    /** A saved draft's reference as {@code Artifacts.saveDraft} stores it: each value sealed under the artifact. */
    private ArtifactReferenceRow reference(Gm gm, UUID artifact, int number, UUID version, UUID passage, UUID cited,
                                           String type, Integer artifactVersion) {
        Binding name = new Binding("artifact_reference", "record_name:" + number, artifact, gm.id());
        Binding fields = new Binding("artifact_reference", "record_fields:" + number, artifact, gm.id());
        byte[] recordName = cited == null ? null : vault.encryptText(name, "Nome " + number);
        byte[] recordFields = cited == null ? null
                : vault.encrypt(fields, "[{\"name\":\"Dano\",\"value\":\"1d10\"}]".getBytes(StandardCharsets.UTF_8));
        if (artifactVersion != null) {
            return new ArtifactReferenceRow(artifact, gm.id(), number, null, null, null, null, null, null, "campaign",
                    null, type, recordName, recordFields, cited, artifactVersion, Instant.now());
        }
        return new ArtifactReferenceRow(artifact, gm.id(), number, version, Ids.newId(), 3, List.of(passage),
                vault.encryptText(new Binding("artifact_reference", "material_name:" + number, artifact, gm.id()),
                        "SRD Livro"),
                vault.encrypt(new Binding("artifact_reference", "section_path:" + number, artifact, gm.id()),
                        "[\"Magias\",\"Fire Bolt\"]".getBytes(StandardCharsets.UTF_8)), "system", cited, type,
                recordName, recordFields, null, null, Instant.now());
    }

    // --- delete (Story 5.2 part B)

    @Test
    void aDeletedArtifactIsGoneFromEveryReadAtOnceAndItsRowsGoWithThePass() throws Exception {
        Gm gm = signUp();
        String id = json(send(gm, "POST", artifacts(gm.campaign()), KAEL)).path("id").asString();
        edit(gm, id, 1, "item", "Espada de Kael II");
        edit(gm, id, 2, "item", "Espada de Kael III");
        String kept = create(gm, gm.campaign(), "character_sheet", "Kael");
        String path = artifacts(gm.campaign()) + "/" + id;

        HttpResponse<String> deleted = send(gm, "DELETE", path, null);

        // Matrix row "Delete": 202, no body, no-store.
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(202);
        assertThat(deleted.body()).isEmpty();
        assertThat(deleted.headers().firstValue("Cache-Control")).hasValue("no-store");
        // Before its pass: in no read, list or count.
        assertNotFound(get(gm, path));
        assertNotFound(get(gm, path + "/versions"));
        assertNotFound(get(gm, path + "/versions/1"));
        assertNotFound(send(gm, "PATCH", path, "{\"version\":3,\"recordType\":\"note\",\"name\":\"X\",\"fields\":[]}"));
        assertNotFound(send(gm, "POST", path + "/versions/1/restore", "{\"version\":3}"));
        JsonNode listed = json(get(gm, artifacts(gm.campaign())));
        assertThat(ids(listed)).containsExactly(kept);
        assertThat(listed.path("total").asLong()).isEqualTo(1);
        assertThat(listed.path("types").toString()).doesNotContain("\"item\"");
        assertThat(json(get(gm, artifacts(gm.campaign()) + "?recordType=item")).path("total").asLong()).isZero();
        assertThat(versionsOf(id)).isEqualTo(3);

        deletion.sweep();

        // Once the pass ran: no identity, no version, no index row; one removal job (ArtifactIndexingTest).
        assertThat(records.artifacts).doesNotContainKey(UUID.fromString(id)).containsKey(UUID.fromString(kept));
        assertThat(versionsOf(id)).isZero();
        assertThat(index.rows.values()).noneMatch(row -> row.recordId().toString().equals(id));
        assertThat(index.removalRows.values()).anyMatch(row -> row.artifactId().toString().equals(id));
        assertNotFound(get(gm, path));
        assertThat(get(gm, artifacts(gm.campaign()) + "/" + kept).statusCode()).isEqualTo(200);
    }

    @Test
    void aSecondDeleteOrOpeningADeletedArtifactIsNotFoundAndChangesNothing() throws Exception {
        Gm gm = signUp();
        String id = create(gm, gm.campaign(), "item", "Espada de Kael");
        String path = artifacts(gm.campaign()) + "/" + id;
        assertThat(send(gm, "DELETE", path, null).statusCode()).isEqualTo(202);

        // Matrix row "Already deleted": a second tab deletes it again, or opens it, before and after the pass.
        assertNotFound(send(gm, "DELETE", path, null));
        assertNotFound(get(gm, path));
        deletion.sweep();
        assertNotFound(send(gm, "DELETE", path, null));
        assertNotFound(get(gm, path));
        assertThat(records.artifacts).doesNotContainKey(UUID.fromString(id));
    }

    @Test
    void anotherOwnersOrAnotherCampaignsArtifactIsNotFoundToADeleteAndNothingChanges() throws Exception {
        Gm a = signUp();
        Gm b = signUp();
        String theirs = create(a, a.campaign(), "item", "Espada de Kael");
        UUID other = UUID.fromString(json(send(a, "POST", "/api/v1/campaigns", "{\"name\":\"Outra mesa\","
                + "\"systemId\":\"" + a.system() + "\"}")).path("id").asString());

        // Matrix row "Other owner or campaign".
        assertNotFound(send(b, "DELETE", artifacts(a.campaign()) + "/" + theirs, null));
        assertNotFound(send(b, "DELETE", artifacts(b.campaign()) + "/" + theirs, null));
        assertNotFound(send(a, "DELETE", artifacts(other) + "/" + theirs, null));
        assertNotFound(send(a, "DELETE", artifacts(a.campaign()) + "/" + UUID.randomUUID(), null));
        assertNotFound(send(a, "DELETE", artifacts(a.campaign()) + "/nope", null));
        assertNotFound(send(a, "DELETE", "/api/v1/campaigns/nope/artifacts/" + theirs, null));
        deletion.sweep();

        assertThat(records.deletingArtifacts).doesNotContain(UUID.fromString(theirs));
        assertThat(records.artifacts).containsKey(UUID.fromString(theirs));
        assertThat(get(a, artifacts(a.campaign()) + "/" + theirs).statusCode()).isEqualTo(200);
        assertThat(versionsOf(theirs)).isEqualTo(1);
    }

    @Test
    void aDeletingArtifactLeavesTheDialogCountsAndTheTypeGuardAndACampaignsDeletionStillRemovesIt()
            throws Exception {
        Gm gm = signUp();
        UUID used = approvedTypeId(gm, gm.system(), "arma_magica");
        UUID other = UUID.fromString(json(send(gm, "POST", "/api/v1/campaigns", "{\"name\":\"Outra mesa\","
                + "\"systemId\":\"" + gm.system() + "\"}")).path("id").asString());
        String deleting = create(gm, other, "arma_magica", "Arco élfico");
        String staying = create(gm, other, "note", "Lembrete");
        assertThat(json(get(gm, "/api/v1/campaigns/" + other + "/deletion")).path("artifacts").asLong()).isEqualTo(2);

        assertThat(send(gm, "DELETE", artifacts(other) + "/" + deleting, null).statusCode()).isEqualTo(202);

        // Before its pass: the dialogs count the live artifact only, and its type is no longer in use.
        assertThat(json(get(gm, "/api/v1/campaigns/" + other + "/deletion")).path("artifacts").asLong()).isEqualTo(1);
        assertThat(json(get(gm, "/api/v1/systems/" + gm.system() + "/deletion")).path("artifacts").asLong())
                .isEqualTo(1);
        // A campaign's deletion committed before the artifact's pass still takes the deleting artifact.
        assertThat(send(gm, "DELETE", "/api/v1/campaigns/" + other, null).statusCode()).isEqualTo(202);
        deletion.sweep();
        deletion.sweep();

        assertThat(records.artifacts).doesNotContainKeys(UUID.fromString(deleting), UUID.fromString(staying));
        assertThat(versionsOf(deleting)).isZero();
        assertThat(library.campaigns).doesNotContainKey(other);
        assertThat(send(gm, "DELETE", "/api/v1/record-types/" + used, null).statusCode()).isEqualTo(204);
    }

    @Test
    void theTypeOfADeletingArtifactIsNotInUse() throws Exception {
        Gm gm = signUp();
        UUID used = approvedTypeId(gm, gm.system(), "arma_magica");
        String id = create(gm, gm.campaign(), "arma_magica", "Arco élfico");
        assertThat(send(gm, "DELETE", "/api/v1/record-types/" + used, null).statusCode()).isEqualTo(409);

        assertThat(send(gm, "DELETE", artifacts(gm.campaign()) + "/" + id, null).statusCode()).isEqualTo(202);

        assertThat(send(gm, "DELETE", "/api/v1/record-types/" + used, null).statusCode()).isEqualTo(204);
        deletion.sweep();
        assertThat(records.artifacts).doesNotContainKey(UUID.fromString(id));
    }

    // --- override (Story 5.5 part A; the matrix with a system's records is in RecordsFlowTest)

    @Test
    void anArtifactOverridesNothingUntilMarkedAndItsMarkRoutesAnswerOnlyItsOwnCampaign() throws Exception {
        Gm gm = signUp();
        String id = create(gm, gm.campaign(), "spell", "Bola de Fogo (casa)");
        String path = artifacts(gm.campaign()) + "/" + id;
        JsonNode read = json(get(gm, path));
        assertThat(read.has("overrides")).isTrue();
        assertThat(read.path("overrides").isNull()).isTrue();

        // Removing a mark it does not have is no error, and repeating it neither.
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> removed = send(gm, "DELETE", path + "/override", null);
            assertThat(removed.statusCode()).as(removed.body()).isEqualTo(204);
            assertThat(removed.headers().firstValue("Cache-Control")).hasValue("no-store");
        }
        // With no system material, nothing is a target: an unknown id, a malformed one, none, or the artifact itself.
        for (String body : List.of("{\"recordId\":\"" + Ids.newId() + "\"}", "{\"recordId\":\"x\"}", "{}",
                "{\"recordId\":\"" + id + "\"}")) {
            HttpResponse<String> refused = send(gm, "PUT", path + "/override", body);
            assertThat(refused.statusCode()).as(body).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).isEqualTo("artifact.override_target");
        }
        JsonNode targets = json(get(gm, "/api/v1/campaigns/" + gm.campaign() + "/override-targets?recordType=spell"));
        assertThat(targets.path("items")).isEmpty();
        assertThat(targets.path("total").asLong()).isZero();

        // Another campaign's artifact and another owner's are not found on PUT and DELETE, nor are another owner's
        // campaign's targets; a deleting artifact is not found on PUT and DELETE.
        Gm them = signUp();
        String theirs = create(them, them.campaign(), "spell", "Deles");
        for (String other : List.of(artifacts(gm.campaign()) + "/" + theirs, artifacts(them.campaign()) + "/" + id)) {
            assertNotFound(send(gm, "PUT", other + "/override", "{\"recordId\":\"" + Ids.newId() + "\"}"));
            assertNotFound(send(gm, "DELETE", other + "/override", null));
        }
        assertNotFound(get(gm, "/api/v1/campaigns/" + them.campaign() + "/override-targets?recordType=spell"));
        assertThat(send(gm, "DELETE", path, null).statusCode()).isEqualTo(202);
        assertNotFound(send(gm, "DELETE", path + "/override", null));
        assertNotFound(send(gm, "PUT", path + "/override", "{\"recordId\":\"" + Ids.newId() + "\"}"));
    }

    // --- relations (Story 5.5 part B; the matrix with a system's records is in RecordsFlowTest)

    @Test
    void twoArtifactsOfTheCampaignAreRelatedWithNoSystemMaterialAndTheRelationRoutesAnswerOnlyTheirOwnCampaign()
            throws Exception {
        Gm gm = signUp();
        String crypt = create(gm, gm.campaign(), "encounter", "Cripta");
        String guard = create(gm, gm.campaign(), "creature", "Guarda");
        String path = artifacts(gm.campaign()) + "/" + crypt;
        JsonNode read = json(get(gm, path));
        assertThat(read.path("related").isArray()).isTrue();
        assertThat(read.path("related")).isEmpty();

        // Removing a relation it does not have is no error, and repeating it neither.
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> removed = send(gm, "DELETE", path + "/relations/" + guard, null);
            assertThat(removed.statusCode()).as(removed.body()).isEqualTo(204);
            assertThat(removed.headers().firstValue("Cache-Control")).hasValue("no-store");
        }
        // An unknown id, a malformed one, none, or the artifact itself is no target.
        for (String body : List.of("{\"recordId\":\"" + Ids.newId() + "\"}", "{\"recordId\":\"x\"}", "{}",
                "{\"recordId\":\"" + crypt + "\"}")) {
            HttpResponse<String> refused = send(gm, "POST", path + "/relations", body);
            assertThat(refused.statusCode()).as(body).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).isEqualTo("artifact.relation_target");
        }
        // With no material, the campaign's other artifacts are the targets.
        JsonNode targets = json(get(gm, path + "/relation-targets?recordType=creature"));
        assertThat(targets.path("items")).extracting(item -> item.path("id").asString()).containsExactly(guard);
        assertThat(targets.path("items").get(0).path("tier").asString()).isEqualTo("campaign");
        assertThat(targets.path("items").get(0).path("artifact").asBoolean()).isTrue();
        assertThat(targets.path("total").asLong()).isOne();

        HttpResponse<String> related = send(gm, "POST", path + "/relations", "{\"recordId\":\"" + guard + "\"}");
        assertThat(related.statusCode()).as(related.body()).isEqualTo(200);
        assertThat(related.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(json(related).path("related").get(0).propertyNames()).containsExactlyInAnyOrder("recordId",
                "name", "recordType", "tier", "artifact", "removed");
        assertThat(json(get(gm, artifacts(gm.campaign()) + "/" + guard)).path("related").get(0).path("recordId")
                .asString()).isEqualTo(crypt);
        assertThat(json(get(gm, path + "/relation-targets?recordType=creature")).path("total").asLong()).isZero();

        // Another campaign's artifact and another owner's are not found on every route; nor is a deleting artifact.
        Gm them = signUp();
        String theirs = create(them, them.campaign(), "creature", "Deles");
        for (String other : List.of(artifacts(gm.campaign()) + "/" + theirs, artifacts(them.campaign()) + "/" + crypt)) {
            assertNotFound(send(gm, "POST", other + "/relations", "{\"recordId\":\"" + guard + "\"}"));
            assertNotFound(send(gm, "DELETE", other + "/relations/" + guard, null));
            assertNotFound(get(gm, other + "/relation-targets?recordType=creature"));
        }
        // Their artifact is no target of this campaign's.
        HttpResponse<String> foreign = send(gm, "POST", path + "/relations", "{\"recordId\":\"" + theirs + "\"}");
        assertThat(foreign.statusCode()).isEqualTo(400);
        assertThat(send(gm, "DELETE", artifacts(gm.campaign()) + "/" + guard, null).statusCode()).isEqualTo(202);
        assertThat(json(get(gm, path)).path("related")).isEmpty();
        String guardPath = artifacts(gm.campaign()) + "/" + guard;
        assertNotFound(send(gm, "DELETE", guardPath + "/relations/" + crypt, null));
        assertNotFound(send(gm, "POST", guardPath + "/relations", "{\"recordId\":\"" + crypt + "\"}"));
        assertNotFound(get(gm, guardPath + "/relation-targets?recordType=encounter"));
        // A deleting artifact is no target either.
        HttpResponse<String> deleting = send(gm, "POST", path + "/relations", "{\"recordId\":\"" + guard + "\"}");
        assertThat(deleting.statusCode()).isEqualTo(400);
    }

    // --- sharing (Story 5.6 part A; the scope rows, with answers, are in RecordsFlowTest)

    @Test
    void anArtifactIsSharedWithTheOwnersOtherCampaignsOfItsSystemByNameAndUnsharedWithAnEmptySet() throws Exception {
        Gm gm = signUp();
        UUID quinta = campaign(gm, "Mesa de quinta");
        UUID domingo = campaign(gm, "Mesa de domingo");
        String kael = create(gm, gm.campaign(), "item", "Espada de Kael");
        String path = artifacts(gm.campaign()) + "/" + kael;
        JsonNode read = json(get(gm, path));
        assertThat(read.path("sharedWith").isArray()).isTrue();
        assertThat(read.path("sharedWith")).isEmpty();
        assertThat(read.has("sharedFrom")).isTrue();
        assertThat(read.path("sharedFrom").isNull()).isTrue();

        // Matrix row "Share": the whole set, by name; an archived campaign of the system is a target too.
        HttpResponse<String> shared = share(gm, path, quinta, domingo);
        assertThat(shared.statusCode()).as(shared.body()).isEqualTo(200);
        assertThat(shared.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode sharedWith = json(shared).path("sharedWith");
        assertThat(sharedWith).extracting(one -> one.path("name").asString())
                .containsExactly("Mesa de domingo", "Mesa de quinta");
        assertThat(sharedWith.get(0).propertyNames()).containsExactlyInAnyOrder("campaignId", "name", "archived");
        assertThat(sharedWith.get(0).path("campaignId").asString()).isEqualTo(domingo.toString());
        assertThat(sharedWith.get(0).path("archived").asBoolean()).isFalse();
        assertThat(json(shared).path("sharedFrom").isNull()).isTrue();
        // One row per pair, and it stays one artifact: no copy, no new version and no index job.
        assertThat(shares.rows.values()).filteredOn(row -> row.artifactId().toString().equals(kael))
                .extracting(row -> row.campaignId()).containsExactlyInAnyOrder(quinta, domingo);
        assertThat(artifactsOf(gm)).hasSize(1);
        assertThat(versionsOf(kael)).isOne();
        assertThat(index.rows.values()).filteredOn(row -> row.recordId().toString().equals(kael)).hasSize(1);
        // Archiving a campaign it is shared with marks it; sending the same set again changes nothing.
        long version = json(get(gm, "/api/v1/campaigns/" + domingo)).path("version").asLong();
        assertThat(send(gm, "PATCH", "/api/v1/campaigns/" + domingo, "{\"version\":" + version
                + ",\"archived\":true}").statusCode()).isEqualTo(200);
        JsonNode again = json(share(gm, path, quinta, domingo));
        assertThat(again.path("sharedWith").get(0).path("archived").asBoolean()).isTrue();
        assertThat(again.path("sharedWith")).hasSize(2);

        // The set replaces the last one: one campaign left, then none.
        assertThat(json(share(gm, path, quinta)).path("sharedWith")).extracting(one -> one.path("campaignId")
                .asString()).containsExactly(quinta.toString());
        assertThat(json(get(gm, path)).path("sharedWith")).hasSize(1);
        HttpResponse<String> unshared = share(gm, path);
        assertThat(unshared.statusCode()).as(unshared.body()).isEqualTo(200);
        assertThat(json(unshared).path("sharedWith")).isEmpty();
        assertThat(shares.rows.values()).noneMatch(row -> row.artifactId().toString().equals(kael));
    }

    @Test
    void aCampaignThatIsNoTargetIsRefusedAndNothingIsStored() throws Exception {
        Gm gm = signUp();
        UUID quinta = campaign(gm, "Mesa de quinta");
        UUID farSystem = UUID.fromString(json(send(gm, "POST", "/api/v1/systems", "{\"name\":\"Outro sistema\"}"))
                .path("id").asString());
        UUID far = UUID.fromString(json(send(gm, "POST", "/api/v1/campaigns", "{\"name\":\"Mesa distante\","
                + "\"systemId\":\"" + farSystem + "\"}")).path("id").asString());
        Gm them = signUp();
        String kael = create(gm, gm.campaign(), "item", "Espada de Kael");
        String path = artifacts(gm.campaign()) + "/" + kael;
        assertThat(share(gm, path, quinta).statusCode()).isEqualTo(200);

        // Matrix row "Wrong target": its own campaign, another system's, another owner's, an unknown or malformed id,
        // each beside a good one; the share it had stays as it was.
        for (String wrong : List.of(gm.campaign().toString(), far.toString(), them.campaign().toString(),
                Ids.newId().toString(), "x")) {
            HttpResponse<String> refused = send(gm, "PUT", path + "/sharing", "{\"campaignIds\":[\"" + quinta
                    + "\",\"" + wrong + "\"]}");
            assertThat(refused.statusCode()).as(wrong).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).isEqualTo("artifact.share_target");
            assertThat(refused.body()).doesNotContain(wrong);
        }
        HttpResponse<String> nullId = send(gm, "PUT", path + "/sharing", "{\"campaignIds\":[null]}");
        assertThat(json(nullId).path("code").asString()).isEqualTo("artifact.share_target");
        assertThat(shares.rows.values()).filteredOn(row -> row.artifactId().toString().equals(kael))
                .extracting(row -> row.campaignId()).containsExactly(quinta);
        // A malformed body, or over 200 ids, is request.invalid.
        StringBuilder many = new StringBuilder("{\"campaignIds\":[");
        for (int i = 0; i < 201; i++) {
            many.append(i == 0 ? "" : ",").append('"').append(quinta).append('"');
        }
        for (String body : List.of("{}", "{\"campaignIds\":null}", "{\"campaignIds\":\"x\"}",
                many.append("]}").toString())) {
            HttpResponse<String> invalid = send(gm, "PUT", path + "/sharing", body);
            assertThat(invalid.statusCode()).as(body).isEqualTo(400);
            assertThat(json(invalid).path("code").asString()).isEqualTo("request.invalid");
        }
        assertThat(json(get(gm, path)).path("sharedWith")).hasSize(1);
    }

    @Test
    void inTheCampaignItIsSharedIntoTheArtifactReadsReadOnlyAndEveryOtherRouteIsNotFound() throws Exception {
        Gm gm = signUp();
        UUID quinta = campaign(gm, "Mesa de quinta");
        UUID domingo = campaign(gm, "Mesa de domingo");
        String kael = create(gm, gm.campaign(), "item", "Espada de Kael");
        String guard = create(gm, gm.campaign(), "creature", "Guarda");
        String own = artifacts(gm.campaign()) + "/" + kael;
        assertThat(send(gm, "POST", own + "/relations", "{\"recordId\":\"" + guard + "\"}").statusCode())
                .isEqualTo(200);
        edit(gm, kael, 1, "item", "Espada Rubra de Kael");
        assertThat(share(gm, own, quinta).statusCode()).isEqualTo(200);
        String there = artifacts(quinta) + "/" + kael;

        // Matrix row "Share": it opens in the other campaign, read-only, naming its own campaign.
        HttpResponse<String> read = get(gm, there);
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.headers().firstValue("Cache-Control")).hasValue("no-store");
        JsonNode artifact = json(read);
        assertThat(artifact.path("id").asString()).isEqualTo(kael);
        assertThat(artifact.path("campaignId").asString()).isEqualTo(gm.campaign().toString());
        assertThat(artifact.path("name").asString()).isEqualTo("Espada Rubra de Kael");
        assertThat(artifact.path("version").asInt()).isEqualTo(2);
        assertThat(artifact.path("sharedFrom").propertyNames()).containsExactlyInAnyOrder("campaignId", "name");
        assertThat(artifact.path("sharedFrom").path("campaignId").asString()).isEqualTo(gm.campaign().toString());
        assertThat(artifact.path("sharedFrom").path("name").asString())
                .isEqualTo(json(get(gm, "/api/v1/campaigns/" + gm.campaign())).path("name").asString());
        assertThat(artifact.path("sharedWith")).isEmpty();
        assertThat(artifact.path("related")).isEmpty();
        assertThat(artifact.path("overrides").isNull()).isTrue();
        assertThat(artifact.path("conversationId").isNull()).isTrue();
        // A cited version opens there too.
        JsonNode first = json(get(gm, there + "/versions/1"));
        assertThat(first.path("name").asString()).isEqualTo("Espada de Kael");
        assertThat(first.path("sharedFrom").path("campaignId").asString()).isEqualTo(gm.campaign().toString());
        assertNotFound(get(gm, there + "/versions/3"));
        // Its own page still has its relations, and no sharedFrom.
        assertThat(json(get(gm, own)).path("related")).hasSize(1);
        assertThat(json(get(gm, own)).path("sharedFrom").isNull()).isTrue();

        // Matrix row "From B": every other route is not found there, and nothing changes.
        assertNotFound(send(gm, "PUT", there + "/sharing", "{\"campaignIds\":[]}"));
        assertNotFound(send(gm, "PATCH", there, "{\"version\":2,\"recordType\":\"item\",\"name\":\"Outra\","
                + "\"fields\":[]}"));
        assertNotFound(send(gm, "POST", there + "/versions/1/restore", "{\"version\":2}"));
        assertNotFound(get(gm, there + "/versions"));
        assertNotFound(send(gm, "DELETE", there, null));
        assertNotFound(send(gm, "PUT", there + "/override", "{\"recordId\":\"" + Ids.newId() + "\"}"));
        assertNotFound(send(gm, "DELETE", there + "/override", null));
        assertNotFound(send(gm, "POST", there + "/relations", "{\"recordId\":\"" + guard + "\"}"));
        assertNotFound(send(gm, "DELETE", there + "/relations/" + guard, null));
        assertNotFound(get(gm, there + "/relation-targets?recordType=creature"));
        // Story 5.6 part B: the list there holds it.
        assertThat(ids(json(get(gm, artifacts(quinta))))).containsExactly(kael);
        assertThat(versionsOf(kael)).isEqualTo(2);
        assertThat(json(get(gm, own)).path("sharedWith")).hasSize(1);
        // A campaign it is not shared with does not read it.
        assertNotFound(get(gm, artifacts(domingo) + "/" + kael));

        // Unshared, or deleting, it is not found there any more.
        assertThat(share(gm, own).statusCode()).isEqualTo(200);
        assertNotFound(get(gm, there));
        assertThat(share(gm, own, quinta).statusCode()).isEqualTo(200);
        assertThat(get(gm, there).statusCode()).isEqualTo(200);
        assertThat(send(gm, "DELETE", own, null).statusCode()).isEqualTo(202);
        assertNotFound(get(gm, there));
        assertNotFound(get(gm, there + "/versions/1"));
        // Its deletion's pass takes its shares.
        deletion.sweep();
        assertThat(shares.rows.values()).noneMatch(row -> row.artifactId().toString().equals(kael));
    }

    @Test
    void readInTheCampaignItIsSharedIntoAReferenceToAnotherArtifactIsRemovedUntilThatOneIsSharedThereToo()
            throws Exception {
        Gm gm = signUp();
        UUID quinta = campaign(gm, "Mesa de quinta");
        String kael = create(gm, gm.campaign(), "item", "Espada de Kael");
        String guard = create(gm, gm.campaign(), "creature", "Guarda");
        UUID artifact = UUID.fromString(kael);
        OwnerContext.runAs(gm.id(), () -> references.insert(List.of(
                reference(gm, artifact, 1, null, null, UUID.fromString(guard), "creature", 1))));
        assertThat(share(gm, artifacts(gm.campaign()) + "/" + kael, quinta).statusCode()).isEqualTo(200);
        String there = artifacts(quinta) + "/" + kael;

        // In its own campaign the cited artifact is live.
        assertThat(json(get(gm, artifacts(gm.campaign()) + "/" + kael)).path("references").get(0).path("removed")
                .asBoolean()).isFalse();
        // In the campaign it is shared into, the cited artifact is worked out there: not shared, it is removed.
        JsonNode reference = json(get(gm, there)).path("references").get(0);
        assertThat(reference.path("artifactId").asString()).isEqualTo(guard);
        assertThat(reference.path("removed").asBoolean()).isTrue();
        assertThat(json(get(gm, there + "/versions/1")).path("references").get(0).path("removed").asBoolean())
                .isTrue();
        // Shared there too, it is live there.
        assertThat(share(gm, artifacts(gm.campaign()) + "/" + guard, quinta).statusCode()).isEqualTo(200);
        assertThat(json(get(gm, there)).path("references").get(0).path("removed").asBoolean()).isFalse();
    }

    @Test
    void anotherOwnerFindsNoSharedArtifactAndCannotShareIntoOrFromTheirCampaigns() throws Exception {
        Gm gm = signUp();
        UUID quinta = campaign(gm, "Mesa de quinta");
        String kael = create(gm, gm.campaign(), "item", "Espada de Kael");
        assertThat(share(gm, artifacts(gm.campaign()) + "/" + kael, quinta).statusCode()).isEqualTo(200);
        Gm them = signUp();
        UUID theirOther = campaign(them, "Mesa deles");

        // Matrix row "Other owner": not found on every route, from any of the ids.
        for (String path : List.of(artifacts(gm.campaign()) + "/" + kael, artifacts(quinta) + "/" + kael,
                artifacts(them.campaign()) + "/" + kael)) {
            assertNotFound(get(them, path));
            assertNotFound(get(them, path + "/versions/1"));
            assertNotFound(send(them, "PUT", path + "/sharing", "{\"campaignIds\":[\"" + theirOther + "\"]}"));
        }
        assertThat(json(get(gm, artifacts(gm.campaign()) + "/" + kael)).path("sharedWith"))
                .extracting(one -> one.path("campaignId").asString()).containsExactly(quinta.toString());
        assertThat(shares.rows.values()).noneMatch(row -> row.ownerId().equals(them.id()));
    }

    // --- the list in the other campaigns (Story 5.6 part B)

    @Test
    void theListOfTheCampaignItIsSharedIntoHoldsItAmongItsOwnByIdMarkedWithItsOwnCampaignWhateverTheTiers()
            throws Exception {
        Gm gm = signUp();
        UUID quinta = campaign(gm, "Mesa de quinta");
        String older = create(gm, quinta, "note", "Lembrete");
        String espada = create(gm, gm.campaign(), "item", "Espada");
        String sheet = create(gm, gm.campaign(), "character_sheet", "Kael");
        String newer = create(gm, quinta, "item", "Escudo");
        String own = artifacts(gm.campaign());
        String ownName = json(get(gm, "/api/v1/campaigns/" + gm.campaign())).path("name").asString();
        JsonNode before = json(get(gm, artifacts(quinta)));
        assertThat(ids(before)).containsExactly(newer, older);
        assertThat(before.path("items").get(0).has("sharedFrom")).isTrue();
        assertThat(before.path("items").get(0).path("sharedFrom").isNull()).isTrue();
        assertThat(share(gm, own + "/" + espada, quinta).statusCode()).isEqualTo(200);
        assertThat(share(gm, own + "/" + sheet, quinta).statusCode()).isEqualTo(200);

        // Matrix row "Listed in B": among its own, by id, marked with its own campaign; total +1 for each.
        JsonNode listed = json(get(gm, artifacts(quinta)));
        assertThat(ids(listed)).containsExactly(newer, sheet, espada, older);
        assertThat(listed.path("total").asLong()).isEqualTo(4);
        JsonNode shared = listed.path("items").get(2);
        assertThat(shared.path("name").asString()).isEqualTo("Espada");
        assertThat(shared.path("recordType").asString()).isEqualTo("item");
        assertThat(shared.path("sharedFrom").propertyNames()).containsExactlyInAnyOrder("campaignId", "name");
        assertThat(shared.path("sharedFrom").path("campaignId").asString()).isEqualTo(gm.campaign().toString());
        assertThat(shared.path("sharedFrom").path("name").asString()).isEqualTo(ownName);
        assertThat(listed.path("items").get(0).path("sharedFrom").isNull()).isTrue();
        // The row opens part A's read-only read there.
        assertThat(json(get(gm, artifacts(quinta) + "/" + espada)).path("sharedFrom").path("campaignId").asString())
                .isEqualTo(gm.campaign().toString());
        // Matrix row "Type filter": own and shared of the type only; types count both, whatever the filter.
        JsonNode items = json(get(gm, artifacts(quinta) + "?recordType=item"));
        assertThat(ids(items)).containsExactly(newer, espada);
        assertThat(items.path("total").asLong()).isEqualTo(2);
        assertThat(items.path("types").toString()).isEqualTo("[{\"key\":\"character_sheet\",\"count\":1},"
                + "{\"key\":\"item\",\"count\":2},{\"key\":\"note\",\"count\":1}]");
        // Matrix row "Personagens": a character sheet shared into it lists by its type there.
        JsonNode sheets = json(get(gm, artifacts(quinta) + "?recordType=character_sheet"));
        assertThat(ids(sheets)).containsExactly(sheet);
        assertThat(sheets.path("items").get(0).path("sharedFrom").path("name").asString()).isEqualTo(ownName);
        // The cursor and the offset page the one list.
        JsonNode first = json(get(gm, artifacts(quinta) + "?limit=2"));
        assertThat(ids(first)).containsExactly(newer, sheet);
        assertThat(ids(json(get(gm, artifacts(quinta) + "?limit=2&cursor=" + first.path("nextCursor").asString()))))
                .containsExactly(espada, older);
        assertThat(ids(json(get(gm, artifacts(quinta) + "?limit=2&offset=1")))).containsExactly(sheet, espada);
        // Matrix row "Own list": unchanged, sharedFrom null.
        JsonNode ownList = json(get(gm, own));
        assertThat(ids(ownList)).containsExactly(sheet, espada);
        assertThat(ownList.path("items")).allMatch(item -> item.path("sharedFrom").isNull());
        assertThat(ownList.path("total").asLong()).isEqualTo(2);

        // Its own campaign archived, it is still listed there, naming that campaign; the total is unchanged.
        long ownVersion = json(get(gm, "/api/v1/campaigns/" + gm.campaign())).path("version").asLong();
        assertThat(send(gm, "PATCH", "/api/v1/campaigns/" + gm.campaign(), "{\"version\":" + ownVersion
                + ",\"archived\":true}").statusCode()).isEqualTo(200);
        JsonNode archivedOwn = json(get(gm, artifacts(quinta)));
        assertThat(ids(archivedOwn)).containsExactly(newer, sheet, espada, older);
        assertThat(archivedOwn.path("total").asLong()).isEqualTo(4);
        assertThat(archivedOwn.path("items").get(2).path("sharedFrom").path("campaignId").asString())
                .isEqualTo(gm.campaign().toString());
        assertThat(archivedOwn.path("items").get(2).path("sharedFrom").path("name").asString()).isEqualTo(ownName);

        // Whatever B's tier switches: listed with every tier off.
        assertThat(send(gm, "PUT", "/api/v1/campaigns/" + quinta + "/scope", "{\"version\":0,"
                + "\"campaignTier\":false,\"systemTier\":false,\"otherTier\":false,\"modelKnowledge\":false,"
                + "\"materialsOff\":[]}").statusCode()).isEqualTo(200);
        assertThat(ids(json(get(gm, artifacts(quinta))))).containsExactly(newer, sheet, espada, older);

        // Matrix row "Unshared or deleting": gone from the list and its total.
        assertThat(share(gm, own + "/" + sheet).statusCode()).isEqualTo(200);
        assertThat(send(gm, "DELETE", own + "/" + espada, null).statusCode()).isEqualTo(202);
        JsonNode after = json(get(gm, artifacts(quinta)));
        assertThat(ids(after)).containsExactly(newer, older);
        assertThat(after.path("total").asLong()).isEqualTo(2);
        assertThat(after.path("types").toString()).isEqualTo("[{\"key\":\"item\",\"count\":1},"
                + "{\"key\":\"note\",\"count\":1}]");
    }

    @Test
    void anArtifactSharedFromACampaignBeingDeletedLeavesTheListAndAnotherOwnersListHoldsNothingOfIt()
            throws Exception {
        Gm gm = signUp();
        UUID quinta = campaign(gm, "Mesa de quinta");
        UUID domingo = campaign(gm, "Mesa de domingo");
        String espada = create(gm, domingo, "item", "Espada");
        assertThat(share(gm, artifacts(domingo) + "/" + espada, quinta).statusCode()).isEqualTo(200);
        assertThat(ids(json(get(gm, artifacts(quinta))))).containsExactly(espada);
        Gm them = signUp();

        // Matrix row "Other owner": their campaign's list holds no artifact of A's.
        JsonNode theirs = json(get(them, artifacts(them.campaign())));
        assertThat(theirs.path("total").asLong()).isZero();
        assertNotFound(get(them, artifacts(quinta)));

        // Its own campaign being deleted, it is no longer listed in the other one.
        assertThat(send(gm, "DELETE", "/api/v1/campaigns/" + domingo, null).statusCode()).isEqualTo(202);
        JsonNode listed = json(get(gm, artifacts(quinta)));
        assertThat(ids(listed)).isEmpty();
        assertThat(listed.path("total").asLong()).isZero();
        assertThat(listed.path("types")).isEmpty();
    }

    // --- export (Story 5.7 part A)

    @Test
    void onePdfPrintsTheNameTypeFieldsContentAndNumberedSourcesWithItsAccentsAndARemovedOneMarked() throws Exception {
        Gm gm = signUp();
        String kael = json(send(gm, "POST", artifacts(gm.campaign()), KAEL)).path("id").asString();
        String skeleton = create(gm, gm.campaign(), "creature", "Esqueleto");
        UUID artifact = UUID.fromString(kael);
        // A material's place whose version is gone (removed) and a live artifact.
        OwnerContext.runAs(gm.id(), () -> references.insert(List.of(
                reference(gm, artifact, 1, Ids.newId(), Ids.newId(), null, null, null),
                reference(gm, artifact, 2, null, null, UUID.fromString(skeleton), "creature", 1))));

        HttpResponse<byte[]> exported = export(gm, gm.campaign(), "ids=" + kael + "&format=pdf");

        assertThat(exported.statusCode()).as(new String(exported.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        assertThat(exported.headers().firstValue("Content-Type")).hasValue("application/pdf");
        assertThat(exported.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"Espada de Kael.pdf\"; filename*=UTF-8''Espada%20de%20Kael.pdf");
        assertThat(exported.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(exported.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(exported.headers().firstValue("Content-Length")).hasValue(String.valueOf(exported.body().length));
        String text = pdfText(exported.body());
        assertThat(text).containsSubsequence("Espada de Kael", "Item", "Dano: 1d8 cortante", "Peso: 1,5 kg",
                "Forjada nas montanhas do norte.", "Brilha perto de orcs.", "Fontes",
                "[1] SRD Livro, Magias > Fire Bolt, p. 3 (fonte removida)", "[2] Criatura: Nome 2 (versão 1)");
        assertThat(pages(exported.body())).isEqualTo(1);

        // In English, the labels follow: the record type's, the title and the marks.
        String english = pdfText(export(gm, gm.campaign(), "ids=" + kael + "&format=pdf&language=en").body());
        assertThat(english).containsSubsequence("Espada de Kael", "Item", "Sources",
                "[1] SRD Livro, Magias > Fire Bolt, p. 3 (source removed)", "[2] Creature: Nome 2 (version 1)");
        assertThat(english).doesNotContain("Fontes");
    }

    @Test
    void severalArtifactsMakeOneFileInTheOrderGivenEachFromANewSectionOrPage() throws Exception {
        Gm gm = signUp();
        String a = create(gm, gm.campaign(), "note", "Alfa");
        String b = create(gm, gm.campaign(), "character_sheet", "Beta");

        HttpResponse<byte[]> markdown = export(gm, gm.campaign(), "ids=" + b + "," + a + "&format=markdown");

        assertThat(markdown.statusCode()).isEqualTo(200);
        assertThat(markdown.headers().firstValue("Content-Type")).hasValue("text/markdown;charset=UTF-8");
        assertThat(markdown.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"artefatos.md\"; filename*=UTF-8''artefatos.md");
        String md = new String(markdown.body(), StandardCharsets.UTF_8);
        assertThat(md).isEqualTo("# Beta\n\n*Ficha de personagem*\n\n---\n\n# Alfa\n\n*Nota*\n");

        HttpResponse<byte[]> txt = export(gm, gm.campaign(), "ids=" + a + "," + b + "&format=txt&language=en");
        assertThat(txt.headers().firstValue("Content-Type")).hasValue("text/plain;charset=UTF-8");
        assertThat(txt.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"artifacts.txt\"; filename*=UTF-8''artifacts.txt");
        assertThat(new String(txt.body(), StandardCharsets.UTF_8)).isEqualTo("Alfa\nNote"
                + "\n\n========================================\n\nBeta\nCharacter sheet\n");

        HttpResponse<byte[]> pdf = export(gm, gm.campaign(), "ids=" + b + "," + a + "&format=pdf");
        assertThat(pages(pdf.body())).isEqualTo(2);
        assertThat(pdfText(pdf.body())).containsSubsequence("Beta", "Ficha de personagem", "Alfa", "Nota");
    }

    @Test
    void oneJpgIsOneImageAndSeveralAreAZipOfOneImageEachInTheOrderGiven() throws Exception {
        Gm gm = signUp();
        String kael = json(send(gm, "POST", artifacts(gm.campaign()), KAEL)).path("id").asString();
        String a = create(gm, gm.campaign(), "note", "Alfa");
        String b = create(gm, gm.campaign(), "item", "Bê/ta");

        HttpResponse<byte[]> one = export(gm, gm.campaign(), "ids=" + kael + "&format=jpg");
        assertThat(one.statusCode()).isEqualTo(200);
        assertThat(one.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        assertThat(one.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"Espada de Kael.jpg\"; filename*=UTF-8''Espada%20de%20Kael.jpg");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(one.body()));
        assertThat(image.getWidth()).isEqualTo(909);
        assertThat(image.getHeight()).isEqualTo(1286);

        HttpResponse<byte[]> several = export(gm, gm.campaign(), "ids=" + b + "," + kael + "," + a + "&format=jpg");
        assertThat(several.statusCode()).isEqualTo(200);
        assertThat(several.headers().firstValue("Content-Type")).hasValue("application/zip");
        assertThat(several.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"artefatos.zip\"; filename*=UTF-8''artefatos.zip");
        Map<String, BufferedImage> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(several.body()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), ImageIO.read(new ByteArrayInputStream(zip.readAllBytes())));
            }
        }
        assertThat(entries.keySet()).containsExactly("01-Bê ta.jpg", "02-Espada de Kael.jpg", "03-Alfa.jpg");
        assertThat(entries.values()).allSatisfy(each -> assertThat(each.getWidth()).isEqualTo(909));
    }

    @Test
    void aLongArtifactIsOneTallJpgOfAllItsPages() throws Exception {
        Gm gm = signUp();
        StringBuilder content = new StringBuilder();
        while (content.length() < 20_000) {
            content.append("Linha da história de Kael, com acentos: ação, coração. ");
        }
        String body = JSON.writeValueAsString(Map.of("recordType", "note", "name", "Crônica", "fields", List.of(),
                "content", content.substring(0, 20_000).strip()));
        String id = json(send(gm, "POST", artifacts(gm.campaign()), body)).path("id").asString();

        int pages = pages(export(gm, gm.campaign(), "ids=" + id + "&format=pdf").body());
        HttpResponse<byte[]> jpg = export(gm, gm.campaign(), "ids=" + id + "&format=jpg");

        assertThat(pages).isGreaterThan(2);
        assertThat(jpg.statusCode()).isEqualTo(200);
        // A name beyond ASCII: its ASCII form, and the UTF-8 name percent-encoded.
        assertThat(jpg.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"Cronica.jpg\"; filename*=UTF-8''Cr%C3%B4nica.jpg");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpg.body()));
        assertThat(image.getWidth()).isEqualTo(909);
        assertThat(image.getHeight()).isEqualTo(pages * 1286);
    }

    @Test
    void anArtifactOfAnApprovedTypeIsExportedWithTheTypesNameNotItsKey() throws Exception {
        Gm gm = signUp();
        String key = approvedType(gm);
        String id = create(gm, gm.campaign(), key, "Arco élfico");

        HttpResponse<byte[]> txt = export(gm, gm.campaign(), "ids=" + id + "&format=txt");

        assertThat(txt.statusCode()).isEqualTo(200);
        assertThat(new String(txt.body(), StandardCharsets.UTF_8)).isEqualTo("Arco élfico\nTipo " + key + "\n");
    }

    @Test
    void aTypePastTheFirstPageOfFiftyRecordTypesIsExportedByItsName() throws Exception {
        Gm gm = signUp();
        // The 8 core types, then 50 approved ones: the last is the 58th, past the first page of 50.
        String last = null;
        for (int i = 0; i < 50; i++) {
            last = approvedIn(gm, gm.system(), "tipo_" + i);
        }
        String id = create(gm, gm.campaign(), last, "Talento raro");

        HttpResponse<byte[]> txt = export(gm, gm.campaign(), "ids=" + id + "&format=txt");

        assertThat(txt.statusCode()).isEqualTo(200);
        assertThat(new String(txt.body(), StandardCharsets.UTF_8)).isEqualTo("Talento raro\nTipo tipo_49\n");
    }

    @Test
    void aPdfOrJpgExportOfMoreThanTwoHundredPagesIsTooLargeWhileTxtAndMarkdownStillWork() throws Exception {
        Gm gm = signUp();
        // 64 fields of 8,000 characters: about 130 pages each, so two pass 200 pages in all.
        String value = "Uma linha longa de ficha com acentos: ação, coração. ".repeat(160).substring(0, 8_000).strip();
        List<Map<String, String>> fields = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            fields.add(Map.of("name", "Campo " + i, "value", value));
        }
        List<String> ids = new ArrayList<>();
        for (String name : List.of("Tomo I", "Tomo II")) {
            HttpResponse<String> created = send(gm, "POST", artifacts(gm.campaign()), JSON.writeValueAsString(
                    Map.of("recordType", "note", "name", name, "fields", fields)));
            assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
            ids.add(json(created).path("id").asString());
        }
        String both = "ids=" + String.join(",", ids);

        for (String format : List.of("pdf", "jpg")) {
            HttpResponse<byte[]> refused = export(gm, gm.campaign(), both + "&format=" + format);
            String problem = new String(refused.body(), StandardCharsets.UTF_8);
            assertThat(refused.statusCode()).as(format + " " + problem).isEqualTo(400);
            assertThat(JSON.readTree(problem).path("code").asString()).isEqualTo("artifact.export_too_large");
            assertThat(refused.headers().firstValue("Content-Disposition")).isEmpty();
            assertThat(problem).doesNotContain("Tomo");
        }
        // TXT and Markdown are not counted; one of them alone is within the limit.
        assertThat(export(gm, gm.campaign(), both + "&format=txt").statusCode()).isEqualTo(200);
        assertThat(export(gm, gm.campaign(), both + "&format=markdown").statusCode()).isEqualTo(200);
        HttpResponse<byte[]> one = export(gm, gm.campaign(), "ids=" + ids.getFirst() + "&format=pdf");
        assertThat(one.statusCode()).isEqualTo(200);
        assertThat(pages(one.body())).isBetween(101, 200);
    }

    @Test
    void anArtifactSharedIntoTheCampaignIsExportedThereAndANameIsMadeSafe() throws Exception {
        Gm gm = signUp();
        UUID quinta = campaign(gm, "Mesa de quinta");
        String kael = create(gm, gm.campaign(), "item", "Espada: \\\"Rubra\\\" / <Kael>?");
        assertThat(share(gm, artifacts(gm.campaign()) + "/" + kael, quinta).statusCode()).isEqualTo(200);

        HttpResponse<byte[]> exported = export(gm, quinta, "ids=" + kael + "&format=txt");

        assertThat(exported.statusCode()).isEqualTo(200);
        assertThat(exported.headers().firstValue("Content-Disposition")).hasValue("attachment;"
                + " filename=\"Espada Rubra Kael.txt\"; filename*=UTF-8''Espada%20Rubra%20Kael.txt");
        assertThat(new String(exported.body(), StandardCharsets.UTF_8))
                .isEqualTo("Espada: \"Rubra\" / <Kael>?\nItem\n");
        // Unshared, it is not readable there any more.
        assertThat(share(gm, artifacts(gm.campaign()) + "/" + kael).statusCode()).isEqualTo(200);
        assertExportNotFound(export(gm, quinta, "ids=" + kael + "&format=txt"));
    }

    @Test
    void anUnreadableIdMakesTheWholeExportNotFoundNamingNone() throws Exception {
        Gm gm = signUp();
        String kael = create(gm, gm.campaign(), "item", "Espada de Kael");
        UUID other = campaign(gm, "Mesa de quinta");
        String elsewhere = create(gm, other, "item", "Outra Kael");
        Gm them = signUp();
        String theirs = create(them, them.campaign(), "item", "Kael deles");

        for (String ids : List.of(theirs, kael + "," + theirs, kael + "," + Ids.newId(), elsewhere,
                kael + ",not-an-id")) {
            assertExportNotFound(export(gm, gm.campaign(), "ids=" + ids + "&format=pdf"));
        }
        // Another owner finds nothing of the GM's, from either campaign.
        assertExportNotFound(export(them, gm.campaign(), "ids=" + kael + "&format=pdf"));
        assertExportNotFound(export(them, them.campaign(), "ids=" + kael + "&format=pdf"));
        // A deleting one is not readable either.
        assertThat(send(gm, "DELETE", artifacts(gm.campaign()) + "/" + kael, null).statusCode()).isEqualTo(202);
        assertExportNotFound(export(gm, gm.campaign(), "ids=" + kael + "&format=txt"));
    }

    @Test
    void aBadListFormatOrLanguageIsInvalid() throws Exception {
        Gm gm = signUp();
        String kael = create(gm, gm.campaign(), "item", "Espada de Kael");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add(Ids.newId().toString());
        }

        for (String query : List.of("ids=" + String.join(",", many) + "&format=pdf",
                "ids=" + kael + "," + kael + "&format=pdf", "ids=" + kael + "," + kael.toUpperCase() + "&format=pdf",
                "ids=" + kael + "&format=docx", "ids=" + kael, "format=pdf", "ids=&format=pdf",
                "ids=" + kael + ",&format=pdf", "ids=" + kael + "&format=PDF",
                "ids=" + kael + "&format=pdf&language=fr")) {
            HttpResponse<byte[]> refused = export(gm, gm.campaign(), query);
            assertThat(refused.statusCode()).as(query).isEqualTo(400);
            assertThat(JSON.readTree(refused.body()).path("code").asString()).isEqualTo("request.invalid");
        }
        // 50 ids are allowed: unknown ones are then not found.
        assertExportNotFound(export(gm, gm.campaign(), "ids=" + String.join(",", many.subList(0, 50))
                + "&format=pdf"));
        // A malformed campaign is not found.
        assertExportNotFound(client.send(HttpRequest.newBuilder(uri("/api/v1/campaigns/x/artifacts/export?ids=" + kael
                        + "&format=pdf")).header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Session-Token", gm.session()).GET().build(), HttpResponse.BodyHandlers.ofByteArray()));
    }

    @Test
    void markdownPrintsTheArtifactsTextAsTextNeverAsMarkup() throws Exception {
        Gm gm = signUp();
        String body = JSON.writeValueAsString(Map.of("recordType", "note", "name", "# Título *forte* [x](y)",
                "fields", List.of(Map.of("name", "_Campo_", "value", "1. um\n- dois")),
                "content", "## Cabeçalho\n<img src=x>\n    código\n\n> citação | a & b ~ `c`"));
        String id = json(send(gm, "POST", artifacts(gm.campaign()), body)).path("id").asString();

        String md = new String(export(gm, gm.campaign(), "ids=" + id + "&format=markdown").body(),
                StandardCharsets.UTF_8);

        assertThat(md).isEqualTo("# \\# Título \\*forte\\* \\[x\\](y)\n\n*Nota*\n\n"
                + "- **\\_Campo\\_:** 1\\. um\\\n  \\- dois\n\n"
                + "\\#\\# Cabeçalho\\\n\\<img src=x\\>\\\n\u00A0\u00A0\u00A0\u00A0código\n\n"
                + "\\> citação \\| a \\& b \\~ \\`c\\`\n");
        // The PDF prints the same text as it is.
        assertThat(pdfText(export(gm, gm.campaign(), "ids=" + id + "&format=pdf").body()))
                .containsSubsequence("# Título *forte* [x](y)", "_Campo_: 1. um", "- dois", "## Cabeçalho",
                        "<img src=x>", "código", "> citação | a & b ~ `c`");
    }

    // --- helpers

    private HttpResponse<byte[]> export(Gm gm, UUID campaign, String query) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(artifacts(campaign) + "/export?" + query))
                .header("Authorization", "Bearer " + CREDENTIAL).header("X-Session-Token", gm.session()).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void assertExportNotFound(HttpResponse<byte[]> response) {
        String body = new String(response.body(), StandardCharsets.UTF_8);
        assertThat(response.statusCode()).as(body).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("json");
        assertThat(JSON.readTree(body).path("code").asString()).isEqualTo("request.not_found");
        assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
        assertThat(body).doesNotContain("Kael");
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static int pages(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        }
    }

    private void edit(Gm gm, String id, int version, String type, String name) throws Exception {
        HttpResponse<String> edited = send(gm, "PATCH", artifacts(gm.campaign()) + "/" + id, "{\"version\":" + version
                + ",\"recordType\":\"" + type + "\",\"name\":\"" + name + "\",\"fields\":[]}");
        assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
        assertThat(json(edited).path("version").asInt()).isEqualTo(version + 1);
    }

    private long versionsOf(String id) {
        return versions.rows.values().stream().filter(row -> row.recordId().toString().equals(id)).count();
    }

    private static List<Integer> numbers(JsonNode page) {
        List<Integer> numbers = new ArrayList<>();
        page.path("items").forEach(item -> numbers.add(item.path("number").asInt()));
        return numbers;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

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

    /** A new campaign of the GM's system (Story 5.6 part A); its id. */
    private UUID campaign(Gm gm, String name) throws Exception {
        HttpResponse<String> created = send(gm, "POST", "/api/v1/campaigns", "{\"name\":\"" + name
                + "\",\"systemId\":\"" + gm.system() + "\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(json(created).path("id").asString());
    }

    /** The GM's sharing of the artifact at {@code path} with exactly these campaigns (Story 5.6 part A). */
    private HttpResponse<String> share(Gm gm, String path, UUID... campaigns) throws Exception {
        return send(gm, "PUT", path + "/sharing", JSON.writeValueAsString(Map.of("campaignIds",
                Arrays.stream(campaigns).map(UUID::toString).toList())));
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
