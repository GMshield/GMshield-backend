package io.github.gmshield.knowledge.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import io.github.gmshield.conversations.data.InMemorySearchStore;
import io.github.gmshield.jobs.data.InMemoryJobStorage;
import io.github.gmshield.jobs.data.JobMemoryStores;
import io.github.gmshield.knowledge.data.InMemoryKnowledgeStore;
import io.github.gmshield.knowledge.data.InMemoryRecordStore;
import io.github.gmshield.knowledge.data.RecordTypeRow;
import io.github.gmshield.library.data.LibraryTestStores;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.data.IndexChangeRow;
import io.github.gmshield.materials.data.InMemoryIndexStore;
import io.github.gmshield.materials.data.InMemoryMaterialStore;
import io.github.gmshield.materials.data.MaterialTestStores;
import io.github.gmshield.materials.data.PassageRow;
import io.github.gmshield.materials.service.ExtractedRecords;
import io.github.gmshield.materials.service.ExtractedRecords.Extracted;
import io.github.gmshield.materials.service.ExtractedRecords.Field;
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
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 4.3 part A, without a database: browse a system's records. Every row of the story's I/O matrix, through the
 * public API, over materials ingested through the worker's internal API and accepted: a record type's records listed
 * alphabetically (case ignored, accents ordering only names otherwise equal, then by id) over two accepted versions, 20
 * a page by default, each with its material's name and first page; an empty core type and a type that is not core list
 * nothing; a record opens with its name, its type, its fields in stored order and one reference per distinct version,
 * section path and page, in page order; a record without fields has none; the record's version's pages (picture pages
 * and text pages) and pictures open on the record's ground, every page of it; and another owner's record, a record of
 * a version that needs review or is superseded, a record of another system, a malformed id and a record whose material
 * is deleted are all {@code request.not_found}, with no name or field; so is every read of a system being deleted. Each
 * reference carries the ids of the record's passages at its place, and a page of the record's version is the same
 * answer as the material's own page. The same reads against PostgreSQL are in {@code IngestionIntegrationTest}; the
 * cross-owner rows are also in {@code CrossOwnerAuditFlowTest}.
 *
 * <p>Story 4.3 part B, records in a campaign's library search, with the worker's part played through the internal
 * API: a record whose name equals the query (case and surrounding space aside) leads, with its type, place and passages,
 * whether the worker found it or not; two such records lead in the worker's order; records of a material switched off,
 * of a version that needs review, of another owner and of a records run not indexed yet are never added, and a worker
 * that returns one fails the search; a record-type filter keeps only that type; no line names a record. And the record
 * as a campaign shows it: in scope (any tier, the other tier included once on) it opens with the campaign's system's
 * type and its references open on the scope's ground; switched off, needing review, another owner's, unknown or
 * malformed, it is {@code request.not_found}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GMSHIELD_MODE=test")
@ActiveProfiles({"test-mode", "unit"})
class RecordsFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CREDENTIAL = "gmshield-test-mode-frontend-credential-not-a-secret";
    private static final String WORKER_CREDENTIAL = "gmshield-test-mode-worker-credential-not-a-secret";
    private static final String TUS = "1.0.0";
    private static final int MIB = 1024 * 1024;
    private static final Set<String> OPEN = Set.of("offered", "running", "waiting");

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
    private InMemoryJobStorage jobs;
    @Autowired
    private InMemoryMaterialStore materials;
    @Autowired
    private InMemoryRecordStore records;
    @Autowired
    private InMemoryKnowledgeStore knowledge;
    @Autowired
    private ExtractedRecords extracted;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private InMemorySearchStore searchStore;
    @Autowired
    private InMemoryIndexStore index;
    @Autowired
    private Vault vault;

    private final HttpClient client = HttpClient.newHttpClient();
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    /** A signed-up account: its id, its session token, its starter system and campaign. */
    record Gm(UUID id, String session, UUID system, UUID campaign) {
    }

    /** An ingested material: its id and version, and the version's passages by ordinal. */
    record Ingested(String materialId, UUID versionId, List<UUID> passages) {
    }

    @BeforeEach
    void startLog() {
        endIngestion();
        logged.list = new CopyOnWriteArrayList<>();
        logged.start();
        root.addAppender(logged);
    }

    @AfterEach
    void stopLog() {
        root.detachAppender(logged);
        logged.stop();
    }

    // --- open a type

    @Test
    void aTypesRecordsOverTwoAcceptedVersionsAreListedAlphabeticallyAndPagedWithTheirMaterialAndPage()
            throws Exception {
        Gm gm = signUp();
        // One material attached to the system, the other to its campaign: both count for the system.
        accepted(gm, metadata("Livro do Jogador.pdf", "system", gm.system().toString()), spells(
                "Ácido", "zumbido", "Bola de Fogo", "abelha", "Escudo", "Bola de fogo"));
        List<Extracted> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(record("spell", "Magia " + (char) ('A' + i % 26) + i, 2, List.of()));
        }
        many.add(record("item", "Corda", 1, List.of()));
        many.add(record("rule", "Agarrar", 3, List.of()));
        accepted(gm, metadata("Grimório.pdf", "campaign", gm.campaign().toString()), many);
        String spell = typeId(gm, gm.system(), "spell");

        JsonNode page = ok(get("/api/v1/record-types/" + spell + "/records", gm.session()));

        assertThat(page.path("total").asLong()).isEqualTo(46);
        assertThat(page.path("items")).hasSize(20);
        List<String> all = new ArrayList<>();
        for (int offset = 0; offset < 46; offset += 20) {
            ok(get("/api/v1/record-types/" + spell + "/records?offset=" + offset, gm.session())).path("items")
                    .forEach(item -> all.add(item.path("name").asString()));
        }
        // Case ignored, so the two "Bola de fogo" rows are equal names: two records, ordered by id.
        assertThat(all.subList(0, 2)).containsExactly("abelha", "Ácido");
        assertThat(all.subList(2, 4)).containsExactlyInAnyOrder("Bola de Fogo", "Bola de fogo");
        assertThat(all.subList(4, 6)).containsExactly("Escudo", "Magia A0");
        assertThat(all.getLast()).isEqualTo("zumbido");
        assertThat(all).hasSize(46).doesNotContain("Corda", "Agarrar");
        JsonNode bolas = ok(get("/api/v1/record-types/" + spell + "/records?limit=2&offset=2", gm.session()));
        List<UUID> bolaIds = new ArrayList<>();
        bolas.path("items").forEach(item -> bolaIds.add(UUID.fromString(item.path("id").asString())));
        assertThat(bolaIds).isSorted();
        JsonNode acid = ok(get("/api/v1/record-types/" + spell + "/records?limit=2", gm.session())).path("items")
                .get(1);
        assertThat(acid.propertyNames()).containsExactlyInAnyOrder("id", "name", "materialName", "page");
        assertThat(acid.path("materialName").asString()).isEqualTo("Livro do Jogador.pdf");
        assertThat(acid.path("page").asInt()).isEqualTo(1);
        JsonNode magic = ok(get("/api/v1/record-types/" + spell + "/records?limit=1&offset=5", gm.session()))
                .path("items").get(0);
        assertThat(magic.path("materialName").asString()).isEqualTo("Grimório.pdf");
        assertThat(magic.path("page").asInt()).isEqualTo(2);
        // The other types count their own.
        assertThat(ok(get("/api/v1/record-types/" + typeId(gm, gm.system(), "item") + "/records", gm.session()))
                .path("items").get(0).path("name").asString()).isEqualTo("Corda");
        // A page past the end is empty; a malformed page is refused.
        assertThat(ok(get("/api/v1/record-types/" + spell + "/records?offset=46", gm.session())).path("items"))
                .isEmpty();
        for (String bad : List.of("?limit=0", "?limit=51", "?offset=-1", "?offset=100001", "?offset=x")) {
            assertProblem(get("/api/v1/record-types/" + spell + "/records" + bad, gm.session()), 400,
                    "request.invalid");
        }
        // Names leave only in the answers: no log line holds one.
        assertThat(logged.list).noneMatch(event -> event.getFormattedMessage().contains("Bola")
                || String.valueOf(event.getKeyValuePairs()).contains("Bola")
                || String.valueOf(event.getKeyValuePairs()).contains("Grimório"));
        assertThat(logged.list).anyMatch(event -> event.getFormattedMessage().equals("knowledge.records_listed")
                && hasPair(event, "total", 46L));
    }

    // --- empty type

    @Test
    void anEmptyCoreTypeAndATypeThatIsNotCoreListNothing() throws Exception {
        Gm gm = signUp();
        accepted(gm, metadata("Livro.pdf", "system", gm.system().toString()), spells("Bênção"));

        UUID vehicle = notCore(gm, gm.system(), "Veículo");
        // Two core types without records, and a type that is not core (which types no record before Story 4.4).
        for (String type : List.of(typeId(gm, gm.system(), "creature"), typeId(gm, gm.system(), "rule"),
                vehicle.toString())) {
            JsonNode page = ok(get("/api/v1/record-types/" + type + "/records", gm.session()));
            assertThat(page.path("items")).as(type).isEmpty();
            assertThat(page.path("total").asLong()).as(type).isZero();
        }
        // A system with no material lists nothing either.
        UUID other = createSystem(gm, "Vazio");
        assertThat(ok(get("/api/v1/record-types/" + typeId(gm, other, "spell") + "/records", gm.session()))
                .path("total").asLong()).isZero();
    }

    // --- open a record

    @Test
    void aRecordOpensWithItsNameTypeFieldsInOrderAndOneReferencePerVersionSectionAndPageInPageOrder()
            throws Exception {
        Gm gm = signUp();
        Ingested book = ingested(gm, metadata("Livro do Jogador.pdf", "system", gm.system().toString()));
        List<UUID> p = book.passages();
        // Passages 0..3: page 1 (Magias), page 3 (Magias › Fogo) twice, page 2 (Magias); linked out of page order.
        store(gm, book, List.of(
                new Extracted("spell", "Bola de Fogo", List.of(new Field("Nível", "3"), new Field("Alcance",
                        "45 m"), new Field("Duração", "Instantânea"), new Field("Componentes", "V, S, M")),
                        List.of(p.get(2), p.get(0), p.get(1), p.get(3)), 1),
                new Extracted("spell", "Luz", List.of(), List.of(p.get(0)), 1)));
        accept(gm, book);
        Map<String, String> ids = ids(gm, typeId(gm, gm.system(), "spell"));

        JsonNode read = ok(get("/api/v1/systems/" + gm.system() + "/records/" + ids.get("Bola de Fogo"),
                gm.session()));

        assertThat(read.propertyNames()).containsExactlyInAnyOrder("id", "name", "recordType", "fields",
                "references");
        assertThat(read.path("id").asString()).isEqualTo(ids.get("Bola de Fogo"));
        assertThat(read.path("name").asString()).isEqualTo("Bola de Fogo");
        assertThat(read.path("recordType").path("id").asString()).isEqualTo(typeId(gm, gm.system(), "spell"));
        assertThat(read.path("recordType").path("core").asString()).isEqualTo("spell");
        assertThat(read.path("recordType").path("name").asString()).isEqualTo("spell");
        List<String> fields = new ArrayList<>();
        read.path("fields").forEach(field -> fields.add(field.path("name").asString() + "="
                + field.path("value").asString()));
        assertThat(fields).containsExactly("Nível=3", "Alcance=45 m", "Duração=Instantânea", "Componentes=V, S, M");
        List<String> references = new ArrayList<>();
        List<List<String>> marked = new ArrayList<>();
        read.path("references").forEach(reference -> {
            assertThat(reference.propertyNames()).containsExactlyInAnyOrder("versionId", "materialName",
                    "sectionPath", "page", "passageIds");
            assertThat(reference.path("versionId").asString()).isEqualTo(book.versionId().toString());
            assertThat(reference.path("materialName").asString()).isEqualTo("Livro do Jogador.pdf");
            List<String> path = new ArrayList<>();
            reference.path("sectionPath").forEach(title -> path.add(title.asString()));
            references.add(reference.path("page").asInt() + " " + String.join(" › ", path));
            List<String> passages = new ArrayList<>();
            reference.path("passageIds").forEach(id -> passages.add(id.asString()));
            marked.add(passages);
        });
        assertThat(references).containsExactly("1 Magias", "2 Magias", "3 Magias › Fogo");
        // Each reference carries the record's passages at its place, in the order the store links them (by id).
        assertThat(marked).containsExactly(List.of(p.get(0).toString()), List.of(p.get(3).toString()),
                List.of(p.get(1), p.get(2)).stream().sorted().map(UUID::toString).toList());

        // No fields: none is drawn.
        JsonNode light = ok(get("/api/v1/systems/" + gm.system() + "/records/" + ids.get("Luz"), gm.session()));
        assertThat(light.path("fields")).isEmpty();
        assertThat(light.path("references")).hasSize(1);
        assertThat(logged.list).noneMatch(event -> String.valueOf(event.getKeyValuePairs()).contains("Fogo")
                || event.getFormattedMessage().contains("Fogo") || String.valueOf(event.getKeyValuePairs())
                .contains("Alcance"));
    }

    // --- open a reference

    @Test
    void theRecordsVersionsPagesAndPicturesOpenOnTheRecordsGround() throws Exception {
        Gm gm = signUp();
        Ingested book = accepted(gm, metadata("Livro.pdf", "system", gm.system().toString()), spells("Luz"));
        String record = ids(gm, typeId(gm, gm.system(), "spell")).get("Luz");
        String path = "/api/v1/systems/" + gm.system() + "/records/" + record + "/pages/";

        for (int page = 1; page <= 3; page++) {
            JsonNode read = ok(get(path + page, gm.session()));
            assertThat(read.path("page").asInt()).isEqualTo(page);
            assertThat(read.path("pageTotal").asInt()).isEqualTo(3);
            assertThat(read.path("picture").path("width").asInt()).isEqualTo(1545);
            HttpResponse<byte[]> picture = bytes(path + page + "/picture", gm.session());
            assertThat(picture.statusCode()).isEqualTo(200);
            assertThat(picture.headers().firstValue("Content-Type").orElseThrow()).isEqualTo("image/webp");
            assertThat(picture.headers().firstValue("X-Content-Type-Options").orElseThrow()).isEqualTo("nosniff");
            assertThat(picture.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
            assertThat(new String(picture.body(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        }
        JsonNode first = ok(get(path + 1, gm.session()));
        assertThat(first.path("passages").get(0).path("id").asString()).isEqualTo(book.passages().get(0).toString());
        // The same answer as the material's own page, so one source view renders both.
        for (int page = 1; page <= 3; page++) {
            JsonNode own = ok(get("/api/v1/materials/" + book.materialId() + "/pages/" + page + "?ground=review",
                    gm.session()));
            JsonNode read = ok(get(path + page, gm.session()));
            assertThat(names(read)).as("page %d", page).isEqualTo(names(own));
            assertThat(read).as("page %d", page).isEqualTo(own);
        }
        for (String bad : List.of("0", "4", "01", "x")) {
            assertProblem(get(path + bad, gm.session()), 404, "request.not_found");
            assertThat(bytes(path + bad + "/picture", gm.session()).statusCode()).isEqualTo(404);
        }
    }

    @Test
    void aTextPageOfARecordsVersionOpensWithItsTextAndPassageRangesLikeTheMaterialsOwnPage() throws Exception {
        Gm gm = signUp();
        Ingested notes = ingestedText(gm, metadata("Notas.md", "system", gm.system().toString()));
        OwnerContext.callAs(gm.id(), () -> new TransactionTemplate(transactions).execute(status ->
                extracted.replaceRange(notes.versionId(), 1, 2, List.of(new Extracted("spell", "Luz", List.of(),
                        List.of(notes.passages().get(1)), 2)))));
        accept(gm, notes);
        String record = ids(gm, typeId(gm, gm.system(), "spell")).get("Luz");
        String path = "/api/v1/systems/" + gm.system() + "/records/" + record + "/pages/";

        for (int page = 1; page <= 2; page++) {
            JsonNode read = ok(get(path + page, gm.session()));
            JsonNode own = ok(get("/api/v1/materials/" + notes.materialId() + "/pages/" + page + "?ground=review",
                    gm.session()));
            assertThat(read).as("page %d", page).isEqualTo(own);
            assertThat(read.path("picture").isNull()).as("page %d", page).isTrue();
            assertThat(read.path("lines")).isEmpty();
            assertThat(read.path("text").isString()).as("page %d: %s", page, read).isTrue();
            assertThat(read.path("text").asString()).isNotEmpty();
            JsonNode passage = read.path("passages").get(0);
            assertThat(passage.path("id").asString()).isEqualTo(notes.passages().get(page - 1).toString());
            assertThat(passage.path("range").isArray()).as("page %d: %s", page, read).isTrue();
            assertThat(passage.path("range")).hasSize(2);
            assertThat(passage.has("boxes")).isFalse();
            // A text page has no picture.
            assertThat(bytes(path + page + "/picture", gm.session()).statusCode()).isEqualTo(404);
        }
        // The record's reference names its passage on the text page.
        JsonNode reference = ok(get("/api/v1/systems/" + gm.system() + "/records/" + record, gm.session()))
                .path("references").get(0);
        assertThat(reference.path("page").asInt()).isEqualTo(2);
        assertThat(reference.path("passageIds").get(0).asString()).isEqualTo(notes.passages().get(1).toString());
    }

    // --- not visible

    @Test
    void aRecordNotVisibleFromTheSystemIsNotFoundLikeAnUnknownOneAndNamesNothing() throws Exception {
        Gm a = signUp();
        Gm b = signUp();
        Ingested accepted = accepted(a, metadata("Livro.pdf", "system", a.system().toString()), spells("Aceita"));
        // A replacement accepted too: the first version is superseded.
        Ingested replaced = accepted(a, metadata("Livro.pdf", "material", accepted.materialId()), spells("Nova"));
        assertThat(materials.versions.get(accepted.versionId()).state()).isEqualTo("superseded");
        // A version that still needs review.
        Ingested reviewing = ingested(a, metadata("Rascunho.pdf", "system", a.system().toString()));
        store(a, reviewing, spells("Pendente"));
        String spell = typeId(a, a.system(), "spell");
        Map<String, String> listed = ids(a, spell);
        assertThat(listed).containsOnlyKeys("Nova");
        String visible = listed.get("Nova");
        String superseded = recordOf(accepted.versionId(), "Aceita");
        String pending = recordOf(reviewing.versionId(), "Pendente");
        UUID otherSystem = createSystem(a, "Outro");
        String random = UUID.randomUUID().toString();
        String system = a.system().toString();
        assertThat(replaced.versionId()).isNotEqualTo(accepted.versionId());

        List<String[]> refused = List.of(
                // another owner, with A's system and record, and with B's own system
                new String[] {b.session(), system, visible},
                new String[] {b.session(), b.system().toString(), visible},
                // a version that is superseded or needs review
                new String[] {a.session(), system, superseded},
                new String[] {a.session(), system, pending},
                // another system of the same owner
                new String[] {a.session(), otherSystem.toString(), visible},
                // unknown and malformed ids
                new String[] {a.session(), system, random},
                new String[] {a.session(), random, visible},
                new String[] {a.session(), system, "not-an-id"},
                new String[] {a.session(), "not-an-id", visible});
        Map<String, Object> unknown = withoutTraceId(assertProblem(get("/api/v1/systems/" + system + "/records/"
                + random, a.session()), 404, "request.not_found"));
        for (String[] request : refused) {
            String base = "/api/v1/systems/" + request[1] + "/records/" + request[2];
            for (String path : List.of(base, base + "/pages/1")) {
                HttpResponse<String> answer = get(path, request[0]);
                assertThat(withoutTraceId(assertProblem(answer, 404, "request.not_found"))).as(path)
                        .isEqualTo(unknown);
                assertThat(answer.body()).doesNotContain("Nova").doesNotContain("Aceita").doesNotContain("Pendente")
                        .doesNotContain("Livro");
            }
            assertThat(bytes(base + "/pages/1/picture", request[0]).statusCode()).isEqualTo(404);
        }
        // Another owner's record type lists nothing of A's: it is not found.
        assertProblem(get("/api/v1/record-types/" + spell + "/records", b.session()), 404, "request.not_found");
        assertProblem(get("/api/v1/record-types/not-an-id/records", a.session()), 404, "request.not_found");
        // A's own reads still answer.
        ok(get("/api/v1/systems/" + system + "/records/" + visible, a.session()));
    }

    @Test
    void withoutASessionTheRoutesAreRefusedBeforeAnyController() throws Exception {
        Gm gm = signUp();
        String spell = typeId(gm, gm.system(), "spell");
        String record = UUID.randomUUID().toString();

        assertThat(get("/api/v1/record-types/" + spell + "/records", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/systems/" + gm.system() + "/records/" + record, null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/systems/" + gm.system() + "/records/" + record + "/pages/1", null).statusCode())
                .isEqualTo(401);
        assertThat(bytes("/api/v1/systems/" + gm.system() + "/records/" + record + "/pages/1/picture", null)
                .statusCode()).isEqualTo(401);
    }

    // --- material deleted

    @Test
    void aRecordWhoseMaterialIsDeletedIsNotFoundAtTheNextRead() throws Exception {
        Gm gm = signUp();
        Ingested book = accepted(gm, metadata("Livro.pdf", "system", gm.system().toString()), spells("Luz"));
        String spell = typeId(gm, gm.system(), "spell");
        String record = ids(gm, spell).get("Luz");
        String path = "/api/v1/systems/" + gm.system() + "/records/" + record;
        ok(get(path, gm.session()));

        HttpResponse<String> deleted = send("DELETE", "/api/v1/materials/" + book.materialId(), gm.session(), null);
        assertThat(deleted.statusCode()).as(deleted.body()).isIn(202, 204);

        assertProblem(get(path, gm.session()), 404, "request.not_found");
        assertProblem(get(path + "/pages/1", gm.session()), 404, "request.not_found");
        assertThat(bytes(path + "/pages/1/picture", gm.session()).statusCode()).isEqualTo(404);
        assertThat(ok(get("/api/v1/record-types/" + spell + "/records", gm.session())).path("total").asLong())
                .isZero();
    }

    // --- system being deleted

    @Test
    void aSystemBeingDeletedAnswersNotFoundOnItsListRecordPageAndPicture() throws Exception {
        Gm gm = signUp();
        UUID system = createSystem(gm, "Passageiro");
        accepted(gm, metadata("Livro.pdf", "system", system.toString()), spells("Luz"));
        String spell = typeId(gm, system, "spell");
        String record = ids(gm, spell).get("Luz");
        String path = "/api/v1/systems/" + system + "/records/" + record;
        ok(get(path, gm.session()));
        ok(get(path + "/pages/1", gm.session()));
        assertThat(bytes(path + "/pages/1/picture", gm.session()).statusCode()).isEqualTo(200);

        assertThat(send("DELETE", "/api/v1/systems/" + system, gm.session(), null).statusCode()).isEqualTo(202);

        assertProblem(get("/api/v1/record-types/" + spell + "/records", gm.session()), 404, "request.not_found");
        assertProblem(get(path, gm.session()), 404, "request.not_found");
        assertProblem(get(path + "/pages/1", gm.session()), 404, "request.not_found");
        assertThat(bytes(path + "/pages/1/picture", gm.session()).statusCode()).isEqualTo(404);
        assertThat(logged.list).anyMatch(event -> event.getFormattedMessage().equals("knowledge.records_listed")
                && hasPair(event, "found", false));
    }

    // --- Story 4.3 part B: records in the library search

    @Test
    void aRecordWhoseNameEqualsTheQueryLeadsWithItsTypePlaceAndPassagesAndTheRestKeepTheWorkersOrder()
            throws Exception {
        Gm gm = signUp();
        Ingested book = accepted(gm, metadata("Tesouros.pdf", "system", gm.system().toString()), List.of(
                record("item", "Vorpal Sword", 1, List.of(new Field("Raridade", "Lendária"))),
                record("spell", "Luz", 1, List.of()), record("item", "Espada Curta", 1, List.of())));
        String vorpal = recordOf(book.versionId(), "Vorpal Sword");
        String light = recordOf(book.versionId(), "Luz");
        String sword = recordOf(book.versionId(), "Espada Curta");
        UUID passage = book.passages().getFirst();

        // Case and surrounding space aside, the query is the record's name; the worker did not return the record.
        Search search = search(gm, Map.of("query", "  vorpal SWORD "));
        assertThat(search.claim().path("input").has("recordType")).isFalse();
        report(search.jobId(), search.claim(), "{\"ids\":[\"" + passage + "\",\"" + light + "\",\"" + sword
                + "\"]}");

        JsonNode results = ok(get("/api/v1/searches/" + search.id(), gm.session())).path("results");
        assertThat(results).hasSize(4);
        JsonNode first = results.get(0);
        assertThat(first.propertyNames()).containsExactlyInAnyOrder("kind", "recordId", "versionId", "materialName",
                "sectionPath", "page", "name", "recordType", "passageIds", "tier");
        assertThat(first.path("kind").asString()).isEqualTo("record");
        assertThat(first.path("recordId").asString()).isEqualTo(vorpal);
        assertThat(first.path("name").asString()).isEqualTo("Vorpal Sword");
        assertThat(first.path("recordType").asString()).isEqualTo("item");
        assertThat(first.path("versionId").asString()).isEqualTo(book.versionId().toString());
        assertThat(first.path("materialName").asString()).isEqualTo("Tesouros.pdf");
        assertThat(first.path("sectionPath").get(0).asString()).isEqualTo("Magias");
        assertThat(first.path("page").asInt()).isEqualTo(1);
        assertThat(first.path("passageIds")).extracting(JsonNode::asString).containsExactly(passage.toString());
        assertThat(first.path("tier").asString()).isEqualTo("system");
        // Then the worker's order: a passage, and the two other records with their own types.
        assertThat(results.get(1).path("kind").asString()).isEqualTo("passage");
        assertThat(results.get(1).path("passageId").asString()).isEqualTo(passage.toString());
        assertThat(results.get(1).has("firstWords")).isTrue();
        assertThat(results.get(2).path("recordId").asString()).isEqualTo(light);
        assertThat(results.get(2).path("recordType").asString()).isEqualTo("spell");
        assertThat(results.get(3).path("recordId").asString()).isEqualTo(sword);
        assertThat(results.get(3).has("firstWords")).isFalse();
        // The record opens as the campaign shows it, and its reference's page opens on the scope's ground.
        JsonNode opened = ok(get("/api/v1/campaigns/" + gm.campaign() + "/records/" + vorpal, gm.session()));
        assertThat(opened.path("name").asString()).isEqualTo("Vorpal Sword");
        assertThat(opened.path("recordType").path("id").asString()).isEqualTo(typeId(gm, gm.system(), "item"));
        assertThat(opened.path("fields").get(0).path("value").asString()).isEqualTo("Lendária");
        JsonNode reference = opened.path("references").get(0);
        ok(get("/api/v1/campaigns/" + gm.campaign() + "/sources/" + reference.path("versionId").asString()
                + "/pages/" + reference.path("page").asInt(), gm.session()));
        // No line names a record or the query.
        assertThat(logged.list).noneMatch(event -> event.getFormattedMessage().toLowerCase(Locale.ROOT)
                .contains("vorpal") || String.valueOf(event.getKeyValuePairs()).toLowerCase(Locale.ROOT)
                .contains("vorpal") || String.valueOf(event.getKeyValuePairs()).contains("Lendária"));
        assertThat(logged.list).anyMatch(event -> event.getFormattedMessage().equals("search.done")
                && hasPair(event, "records", 3L) && hasPair(event, "exactNames", 1));
    }

    @Test
    void twoRecordsWithTheQueryAsTheirNameLeadInTheWorkersOrderThenTheRest() throws Exception {
        Gm gm = signUp();
        Ingested one = accepted(gm, metadata("Um.pdf", "system", gm.system().toString()), spells("Luz", "Escudo"));
        Ingested two = accepted(gm, metadata("Dois.pdf", "campaign", gm.campaign().toString()), spells("LUZ"));
        String lightOfOne = recordOf(one.versionId(), "Luz");
        String lightOfTwo = recordOf(two.versionId(), "LUZ");
        String shield = recordOf(one.versionId(), "Escudo");
        UUID passage = two.passages().get(1);

        Search search = search(gm, Map.of("query", "Luz"));
        report(search.jobId(), search.claim(), "{\"ids\":[\"" + passage + "\",\"" + shield + "\",\""
                + lightOfTwo + "\",\"" + lightOfOne + "\"]}");

        JsonNode results = ok(get("/api/v1/searches/" + search.id(), gm.session())).path("results");
        assertThat(results).extracting(result -> result.path("recordId").asString(""))
                .containsExactly(lightOfTwo, lightOfOne, "", shield);
        assertThat(results.get(0).path("tier").asString()).isEqualTo("campaign");
        assertThat(results.get(1).path("tier").asString()).isEqualTo("system");
    }

    @Test
    void onlyInScopeAcceptedAndIndexedRecordsAreAddedAndAWorkerThatReturnsAnotherFailsTheSearch()
            throws Exception {
        Gm gm = signUp();
        Gm them = signUp();
        Ingested book = accepted(gm, metadata("Livro.pdf", "system", gm.system().toString()), spells("Luz"));
        Ingested off = accepted(gm, metadata("Desligado.pdf", "system", gm.system().toString()), spells("Luz"));
        Ingested theirs = accepted(them, metadata("Deles.pdf", "system", them.system().toString()), spells("Luz"));
        Ingested review = ingested(gm, metadata("Rascunho.pdf", "system", gm.system().toString()));
        store(gm, review, spells("Luz"));
        String visible = recordOf(book.versionId(), "Luz");
        String switchedOff = recordOf(off.versionId(), "Luz");
        String reviewing = recordOf(review.versionId(), "Luz");
        String another = recordOf(theirs.versionId(), "Luz");
        scope(gm, true, List.of(off.materialId()));

        // Each one the worker could not have found is never added by name; only the visible record leads.
        Search search = search(gm, Map.of("query", "luz"));
        report(search.jobId(), search.claim(), "{\"ids\":[]}");
        assertThat(ok(get("/api/v1/searches/" + search.id(), gm.session())).path("results"))
                .extracting(result -> result.path("recordId").asString()).containsExactly(visible);

        // A worker that returns any of them fails the whole search, as a forged passage does.
        for (String forged : List.of(switchedOff, reviewing, another, UUID.randomUUID().toString())) {
            Search refused = search(gm, Map.of("query", "Luz"));
            report(refused.jobId(), refused.claim(), "{\"ids\":[\"" + visible + "\",\"" + forged + "\"]}");
            JsonNode read = ok(get("/api/v1/searches/" + refused.id(), gm.session()));
            assertThat(read.path("state").asString()).as(forged).isEqualTo("failed");
            assertThat(read.path("code").asString()).isEqualTo("scope.passage_refused");
            assertThat(read.path("results")).isEmpty();
        }

        // A version whose records run has not ended: its passages are found, its records are not yet.
        Ingested pending = ingested(gm, metadata("Novo.pdf", "system", gm.system().toString()));
        store(gm, pending, spells("Luz"));
        HttpResponse<String> accepted = send("POST", "/api/v1/materials/" + pending.materialId() + "/accept",
                gm.session(), null);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        Search indexing = search(gm, Map.of("query", "Luz"));
        assertThat(strings(indexing.claim().path("input").path("tiers").get(1).path("pending")))
                .contains(pending.passages().getFirst().toString());
        report(indexing.jobId(), indexing.claim(), "{\"ids\":[\"" + pending.passages().getFirst() + "\"]}");
        assertThat(ok(get("/api/v1/searches/" + indexing.id(), gm.session())).path("results"))
                .extracting(result -> result.path("recordId").asString("") + result.path("passageId").asString(""))
                .containsExactly(visible, pending.passages().getFirst().toString());
        assertThat(logged.list).anyMatch(event -> event.getFormattedMessage().equals("scope.named_records_read")
                && hasPair(event, "unindexed", 1));
    }

    @Test
    void aRecordWhoseRecordsRunFailedForGoodIsNotAddedByName() throws Exception {
        Gm gm = signUp();
        Ingested book = ingested(gm, metadata("Livro.pdf", "system", gm.system().toString()));
        store(gm, book, spells("Luz"));
        acceptHoldingRuns(gm, book);
        IndexChangeRow run = recordsRuns(book.versionId()).getFirst();
        // Its job ends failed, and its retries are spent: it stays failed for good.
        jobs.rows.computeIfPresent(run.jobId(), (id, row) -> row.released("failed", "index.embedding_unavailable",
                row.updatedAt()));
        index.changes.put(run.jobId(), new IndexChangeRow(run.jobId(), run.ownerId(), run.versionId(),
                run.firstOrdinal(), run.lastOrdinal(), null, IndexChangeRow.MAX_RETRIES, true));
        endIngestion();
        assertThat(jobs.rows.get(run.jobId()).state()).isEqualTo("failed");

        Search search = search(gm, Map.of("query", "Luz"));
        report(search.jobId(), search.claim(), "{\"ids\":[]}");

        assertThat(ok(get("/api/v1/searches/" + search.id(), gm.session())).path("results")).isEmpty();
        assertThat(logged.list).anyMatch(event -> event.getFormattedMessage().equals("scope.named_records_read")
                && hasPair(event, "found", 0) && hasPair(event, "unindexed", 1));
    }

    @Test
    void onlyTheRecordsOfARunNotEndedAreLeftOutAndOneTheWorkerReturnedStillLeads() throws Exception {
        Gm gm = signUp();
        Ingested book = ingested(gm, metadata("Livro.pdf", "system", gm.system().toString()));
        // 201 records on page 1 make one run of page 1; a record on page 3 makes a second run.
        List<Extracted> found = new ArrayList<>(spells("Luz"));
        for (int i = 0; i < 200; i++) {
            found.add(record("rule", "Regra " + i, 1, List.of()));
        }
        found.add(new Extracted("spell", "LUZ", List.of(), List.of(book.passages().get(1)), 3));
        store(gm, book, found);
        acceptHoldingRuns(gm, book);
        List<IndexChangeRow> runs = recordsRuns(book.versionId());
        assertThat(runs).extracting(IndexChangeRow::firstOrdinal, IndexChangeRow::lastOrdinal)
                .containsExactly(tuple(1, 1), tuple(3, 3));
        // The first run ends; the second has not.
        jobs.rows.computeIfPresent(runs.getFirst().jobId(), (id, row) -> row.released("cancelled", null,
                row.updatedAt()));
        String onPageOne = recordOf(book.versionId(), "Luz");
        String onPageThree = recordOf(book.versionId(), "LUZ");

        Search search = search(gm, Map.of("query", "luz"));
        report(search.jobId(), search.claim(), "{\"ids\":[]}");
        assertThat(ok(get("/api/v1/searches/" + search.id(), gm.session())).path("results"))
                .extracting(result -> result.path("recordId").asString()).containsExactly(onPageOne);

        // The worker found the unfinished run's record anyway (it is in the index): it leads with the other.
        Search returned = search(gm, Map.of("query", "luz"));
        report(returned.jobId(), returned.claim(), "{\"ids\":[\"" + book.passages().getFirst() + "\",\""
                + onPageThree + "\"]}");
        assertThat(ok(get("/api/v1/searches/" + returned.id(), gm.session())).path("results"))
                .extracting(result -> result.path("recordId").asString("")).containsExactly(onPageThree, onPageOne, "");
        endIngestion();
    }

    @Test
    void aRecordTypeFilterKeepsOnlyThatTypeAndAResultOfAnotherTypeFailsTheSearch() throws Exception {
        Gm gm = signUp();
        Ingested book = accepted(gm, metadata("Livro.pdf", "system", gm.system().toString()), List.of(
                record("spell", "Luz", 1, List.of()), record("item", "Lanterna", 1, List.of())));
        String light = recordOf(book.versionId(), "Luz");
        String lantern = recordOf(book.versionId(), "Lanterna");

        Search search = search(gm, Map.of("query", "Luz", "recordType", "item"));
        assertThat(search.claim().path("input").path("recordType").asString()).isEqualTo("item");
        report(search.jobId(), search.claim(), "{\"ids\":[\"" + lantern + "\"]}");

        // The spell named "Luz" is not added: only items.
        JsonNode read = ok(get("/api/v1/searches/" + search.id(), gm.session()));
        assertThat(read.path("recordType").asString()).isEqualTo("item");
        assertThat(read.path("results")).extracting(result -> result.path("recordId").asString())
                .containsExactly(lantern);
        // Under the filter, a spell from the worker fails the search.
        Search wrong = search(gm, Map.of("query", "Luz", "recordType", "item"));
        report(wrong.jobId(), wrong.claim(), "{\"ids\":[\"" + light + "\"]}");
        assertThat(ok(get("/api/v1/searches/" + wrong.id(), gm.session())).path("code").asString())
                .isEqualTo("scope.passage_refused");
        // Under "spell" the exact name leads; nothing matching is an empty, done search.
        Search spell = search(gm, Map.of("query", "luz", "recordType", "spell"));
        report(spell.jobId(), spell.claim(), "{\"ids\":[]}");
        assertThat(ok(get("/api/v1/searches/" + spell.id(), gm.session())).path("results"))
                .extracting(result -> result.path("recordId").asString()).containsExactly(light);
        Search none = search(gm, Map.of("query", "Luz", "recordType", "creature"));
        report(none.jobId(), none.claim(), "{\"ids\":[]}");
        JsonNode empty = ok(get("/api/v1/searches/" + none.id(), gm.session()));
        assertThat(empty.path("state").asString()).isEqualTo("done");
        assertThat(empty.path("results")).isEmpty();
    }

    @Test
    void aStoredRecordResultLeavesTheSearchWhenItsMaterialIsSwitchedOffAndComesBackWhenOn() throws Exception {
        Gm gm = signUp();
        Ingested book = accepted(gm, metadata("Livro.pdf", "system", gm.system().toString()), spells("Luz"));
        String light = recordOf(book.versionId(), "Luz");
        Search search = search(gm, Map.of("query", "Escuridão"));
        report(search.jobId(), search.claim(), "{\"ids\":[\"" + light + "\"]}");
        assertThat(ok(get("/api/v1/searches/" + search.id(), gm.session())).path("results")).hasSize(1);

        scope(gm, false, List.of(book.materialId()));
        assertThat(ok(get("/api/v1/searches/" + search.id(), gm.session())).path("results")).isEmpty();
        scope(gm, false, List.of());
        assertThat(ok(get("/api/v1/searches/" + search.id(), gm.session())).path("results")).hasSize(1);
    }

    // --- Story 4.3 part B: a record as a campaign shows it

    @Test
    void aRecordOpensFromACampaignWithinItsCurrentScopeAndAnythingElseIsNotFound() throws Exception {
        Gm gm = signUp();
        Gm them = signUp();
        Ingested book = accepted(gm, metadata("Livro.pdf", "system", gm.system().toString()), spells("Luz"));
        UUID otherSystem = createSystem(gm, "Outro");
        Ingested elsewhere = accepted(gm, metadata("Outro.pdf", "system", otherSystem.toString()), spells("Sombra"));
        Ingested review = ingested(gm, metadata("Rascunho.pdf", "system", gm.system().toString()));
        store(gm, review, spells("Pendente"));
        String light = recordOf(book.versionId(), "Luz");
        String shadow = recordOf(elsewhere.versionId(), "Sombra");
        String pending = recordOf(review.versionId(), "Pendente");
        String campaign = "/api/v1/campaigns/" + gm.campaign() + "/records/";

        // The same answer as the system's, with the campaign's system's type.
        JsonNode read = ok(get(campaign + light, gm.session()));
        assertThat(read).isEqualTo(ok(get("/api/v1/systems/" + gm.system() + "/records/" + light, gm.session())));
        // Another system's material is in the other tier: not found while it is off, found once it is on, with the
        // campaign's system's record type.
        assertProblem(get(campaign + shadow, gm.session()), 404, "request.not_found");
        scope(gm, true, List.of());
        JsonNode other = ok(get(campaign + shadow, gm.session()));
        assertThat(other.path("recordType").path("id").asString()).isEqualTo(typeId(gm, gm.system(), "spell"));
        // Switched off, needing review, another owner's, unknown or malformed: all the same not found.
        scope(gm, true, List.of(UUID.fromString(book.materialId())));
        Map<String, Object> unknown = withoutTraceId(assertProblem(get(campaign + UUID.randomUUID(), gm.session()),
                404, "request.not_found"));
        List<String[]> refused = List.of(
                new String[] {gm.session(), campaign + light},
                new String[] {gm.session(), campaign + pending},
                new String[] {gm.session(), campaign + "not-an-id"},
                new String[] {gm.session(), "/api/v1/campaigns/not-an-id/records/" + shadow},
                new String[] {gm.session(), "/api/v1/campaigns/" + UUID.randomUUID() + "/records/" + shadow},
                new String[] {them.session(), campaign + shadow},
                new String[] {them.session(), "/api/v1/campaigns/" + them.campaign() + "/records/" + shadow});
        for (String[] request : refused) {
            HttpResponse<String> answer = get(request[1], request[0]);
            assertThat(withoutTraceId(assertProblem(answer, 404, "request.not_found"))).as(request[1])
                    .isEqualTo(unknown);
            assertThat(answer.body()).doesNotContain("Luz").doesNotContain("Sombra").doesNotContain("Pendente");
        }
        assertThat(get(campaign + shadow, null).statusCode()).isEqualTo(401);
        assertThat(logged.list).anyMatch(event -> event.getFormattedMessage().equals("knowledge.campaign_record_read")
                && hasPair(event, "found", false));
    }

    // --- helpers: searches

    /** A search of the GM's current campaign, created through the public API and claimed as the worker. */
    private record Search(UUID id, UUID jobId, JsonNode claim) {
    }

    private Search search(Gm gm, Map<String, Object> body) throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/campaigns/" + gm.campaign() + "/searches", gm.session(),
                body);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(202);
        UUID id = UUID.fromString(json(created).path("id").asString());
        UUID jobId = searchStore.searches.get(id).jobId();
        return new Search(id, jobId, claim(jobId));
    }

    /** Accepts the version and leaves its index changes as they are (offered), unlike {@link #accept}. */
    private void acceptHoldingRuns(Gm gm, Ingested ingested) throws Exception {
        HttpResponse<String> accepted = send("POST", "/api/v1/materials/" + ingested.materialId() + "/accept",
                gm.session(), null);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
    }

    /** The version's records runs, by first page. */
    private List<IndexChangeRow> recordsRuns(UUID versionId) {
        return index.changes.values().stream().filter(row -> row.versionId().equals(versionId) && row.records())
                .sorted(Comparator.comparingInt(IndexChangeRow::firstOrdinal)).toList();
    }

    /** The GM's campaign's scope: every tier on, the other tier as given, these materials switched off. */
    private void scope(Gm gm, boolean otherTier, List<?> materialsOff) throws Exception {
        String path = "/api/v1/campaigns/" + gm.campaign() + "/scope";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", ok(get(path, gm.session())).path("version").asLong());
        body.put("campaignTier", true);
        body.put("systemTier", true);
        body.put("otherTier", otherTier);
        body.put("modelKnowledge", false);
        body.put("materialsOff", materialsOff.stream().map(String::valueOf).toList());
        ok(send("PUT", path, gm.session(), body));
    }

    private static List<String> strings(JsonNode node) {
        List<String> found = new ArrayList<>();
        node.forEach(item -> found.add(item.asString()));
        return found;
    }

    // --- helpers: records

    private static List<Extracted> spells(String... names) {
        List<Extracted> found = new ArrayList<>();
        for (String name : names) {
            found.add(record("spell", name, 1, List.of(new Field("Nível", "1"))));
        }
        return found;
    }

    /** A record on {@code page}, linked to the version's first passage (filled in by {@link #store}). */
    private static Extracted record(String type, String name, int page, List<Field> fields) {
        return new Extracted(type, name, fields, List.of(), page);
    }

    /** Stores the records of the version through the port, as a records step would: those without links get one. */
    private void store(Gm gm, Ingested ingested, List<Extracted> found) {
        List<Extracted> linked = found.stream().map(record -> record.passageIds().isEmpty()
                ? new Extracted(record.type(), record.name(), record.fields(), List.of(ingested.passages().getFirst()),
                record.firstPage()) : record).toList();
        OwnerContext.callAs(gm.id(), () -> new TransactionTemplate(transactions).execute(status ->
                extracted.replaceRange(ingested.versionId(), 1, 3, linked)));
    }

    /** The id of the stored record of the version with this name, read from the store. */
    private String recordOf(UUID versionId, String name) {
        return records.records.values().stream().filter(row -> row.versionId().equals(versionId))
                .filter(row -> name.equals(vault.decryptText(new Binding("record", "name", row.id(), row.ownerId()),
                        row.name()))).findFirst().orElseThrow().id().toString();
    }

    /** The records of a type by name, as its owner's list names them. */
    private Map<String, String> ids(Gm gm, String recordTypeId) throws Exception {
        Map<String, String> found = new LinkedHashMap<>();
        ok(get("/api/v1/record-types/" + recordTypeId + "/records?limit=50", gm.session())).path("items")
                .forEach(item -> found.put(item.path("name").asString(), item.path("id").asString()));
        return found;
    }

    /** A record type that is not core, stored as Story 4.4's approval will (as {@code OntologyFlowTest} does): its id. */
    private UUID notCore(Gm gm, UUID system, String name) {
        UUID id = Ids.newId();
        OwnerContext.runAs(gm.id(), () -> knowledge.insertRecordTypes(List.of(new RecordTypeRow(id, gm.id(), system,
                null, vault.encryptText(new Binding("record_type", "name", id, gm.id()), name),
                vault.fingerprint("record_type", "name", Library.uniqueForm(name)), 0, Instant.now()))));
        return id;
    }

    /** Whether a log event carries this key with this value (no pattern over the pairs' text, Sonar S8786). */
    private static boolean hasPair(ILoggingEvent event, String key, Object value) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        return pairs != null && pairs.stream().anyMatch(pair -> key.equals(pair.key) && value.equals(pair.value));
    }

    /** Every property name of a JSON tree, by path, so two answers' shapes compare. */
    private static List<String> names(JsonNode node) {
        List<String> found = new ArrayList<>();
        names(node, "", found);
        return found;
    }

    private static void names(JsonNode node, String at, List<String> found) {
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                found.add(at + "." + name);
                names(node.path(name), at + "." + name, found);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                names(node.get(i), at + "[" + i + "]", found);
            }
        }
    }

    private String typeId(Gm gm, UUID system, String core) throws Exception {
        for (JsonNode item : ok(get("/api/v1/systems/" + system + "/record-types", gm.session())).path("items")) {
            if (core.equals(item.path("core").asString())) {
                return item.path("id").asString();
            }
        }
        throw new AssertionError("no " + core);
    }

    // --- helpers: ingestion through the worker's internal API

    /** {@link #ingested}, then {@code found} stored and the version accepted. */
    private Ingested accepted(Gm gm, String metadata, List<Extracted> found) throws Exception {
        Ingested ingested = ingested(gm, metadata);
        store(gm, ingested, found);
        accept(gm, ingested);
        return ingested;
    }

    private void accept(Gm gm, Ingested ingested) throws Exception {
        HttpResponse<String> accepted = send("POST", "/api/v1/materials/" + ingested.materialId() + "/accept",
                gm.session(), null);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(json(accepted).path("state").asString()).isEqualTo("ready");
        // Its index changes would hold the ingestion lane.
        endIngestion();
    }

    /**
     * A material of three pages uploaded with {@code metadata}, cleaned, triaged as text, read directly with a picture
     * of each page, and cut into four passages: page 1 under "Magias", two on page 3 under "Magias › Fogo", and page 2
     * under "Magias", in that order. It needs review.
     */
    private Ingested ingested(Gm gm, String metadata) throws Exception {
        String[] uploaded = upload(gm, pdf(MIB), metadata);
        UUID versionId = UUID.fromString(uploaded[1]);
        UUID clean = offeredJob(versionId);
        JsonNode claim = claim(clean);
        UUID original = output(clean, claim, pdf(4000));
        UUID batch = output(clean, claim, pdf(3000));
        report(clean, claim, "{\"pipelineVersion\":\"2\",\"pageTotal\":3,\"original\":{\"fileId\":\"" + original
                + "\"},\"batches\":[{\"fileId\":\"" + batch + "\",\"firstPage\":1,\"lastPage\":3}],\"next\":[{\"stage\""
                + ":\"triage\",\"firstPage\":1,\"lastPage\":3,\"input\":\"" + batch + "\"},{\"stage\":\"passages\","
                + "\"firstPage\":1,\"lastPage\":3,\"input\":\"texts\"}]}");

        UUID triage = offeredJob(versionId);
        report(triage, claim(triage), "{\"pipelineVersion\":\"2\",\"layers\":[{\"firstPage\":1,\"lastPage\""
                + ":3,\"layer\":\"text\"}],\"next\":[{\"stage\":\"layout\",\"firstPage\":1,\"lastPage\":3,\"input\":\""
                + batch + "\"}]}");

        UUID layout = offeredJob(versionId);
        JsonNode layoutClaim = claim(layout);
        UUID text = output(layout, layoutClaim, "{\"pages\":[]}".getBytes(StandardCharsets.UTF_8));
        StringBuilder views = new StringBuilder();
        for (int page = 1; page <= 3; page++) {
            UUID picture = output(layout, layoutClaim, webp(1000 + page));
            views.append(page == 1 ? "" : ",").append("{\"page\":").append(page).append(",\"picture\":{\"fileId\":\"")
                    .append(picture).append("\",\"width\":1545,\"height\":2000},\"lines\":[{\"text\":\"Linha\","
                    + "\"box\":[0.1,0.2,0.9,0.25]}]}");
        }
        report(layout, layoutClaim, "{\"pipelineVersion\":\"2\",\"pages\":[{\"firstPage\":1,\"lastPage\":3,"
                + "\"outcome\":\"read_directly\"}],\"texts\":[{\"fileId\":\"" + text + "\",\"firstPage\":1,"
                + "\"lastPage\":3}],\"pageViews\":[" + views + "],\"next\":[]}");

        UUID passages = offeredJob(versionId);
        report(passages, claim(passages), "{\"pipelineVersion\":\"2\",\"passages\":["
                + passage(1, "\"Magias\"") + "," + passage(3, "\"Magias\",\"Fogo\"") + ","
                + passage(3, "\"Magias\",\"Fogo\"") + "," + passage(2, "\"Magias\"") + "],\"outline\":[{\"level\":1,"
                + "\"title\":\"Magias\",\"page\":1},{\"level\":2,\"title\":\"Fogo\",\"page\":3}],\"next\":[]}");
        assertThat(materials.versions.get(versionId).state()).isEqualTo("needs_review");
        List<UUID> stored = materials.passages.values().stream().filter(row -> row.versionId().equals(versionId))
                .sorted(Comparator.comparingInt(PassageRow::ordinal)).map(PassageRow::id).toList();
        assertThat(stored).hasSize(4);
        return new Ingested(uploaded[0], versionId, stored);
    }

    /**
     * A Markdown material of two text pages uploaded with {@code metadata}, read by its text step (a text view of each
     * page) and cut into one passage per page, each with its code-point range ("A" on page 1, "A › B" on page 2). It
     * needs review.
     */
    private Ingested ingestedText(Gm gm, String metadata) throws Exception {
        byte[] text = "# A\n\nUnder A.\n\n## B\n\nUnder B.\n".getBytes(StandardCharsets.UTF_8);
        String[] uploaded = upload(gm, text, metadata);
        UUID versionId = UUID.fromString(uploaded[1]);
        UUID read = offeredJob(versionId);
        JsonNode claim = claim(read);
        UUID original = output(read, claim, text);
        UUID pages = output(read, claim, ("{\"pages\":[{\"page\":1,\"headingPath\":[\"A\"],\"text\":\"# A\\n\\n"
                + "Under A.\\n\\n\",\"start\":0,\"end\":15},{\"page\":2,\"headingPath\":[\"A\",\"B\"],\"text\":"
                + "\"## B\\n\\nUnder B.\\n\",\"start\":15,\"end\":30}]}").getBytes(StandardCharsets.UTF_8));
        report(read, claim, "{\"pipelineVersion\":\"2\",\"pageTotal\":2,\"original\":{\"fileId\":\"" + original
                + "\"},\"pages\":[{\"firstPage\":1,\"lastPage\":2,\"outcome\":\"read_directly\"}],\"texts\":[{"
                + "\"fileId\":\"" + pages + "\",\"firstPage\":1,\"lastPage\":2}],\"pageViews\":[{\"page\":1,\"text\":"
                + "\"# A\\n\\nUnder A.\\n\\n\"},{\"page\":2,\"text\":\"## B\\n\\nUnder B.\\n\"}],\"next\":[{\"stage\":"
                + "\"passages\",\"firstPage\":1,\"lastPage\":2,\"input\":\"texts\"}]}");

        UUID passages = offeredJob(versionId);
        report(passages, claim(passages), "{\"pipelineVersion\":\"2\",\"passages\":[{\"page\":1,\"sectionPath\":"
                + "[\"A\"],\"text\":\"# A\\n\\nUnder A.\",\"range\":[0,13]},{\"page\":2,\"sectionPath\":[\"A\","
                + "\"B\"],\"text\":\"## B\\n\\nUnder B.\",\"range\":[0,14]}],\"outline\":[{\"level\":1,\"title\":"
                + "\"A\",\"page\":1},{\"level\":2,\"title\":\"B\",\"page\":2}],\"next\":[]}");
        assertThat(materials.versions.get(versionId).state()).isEqualTo("needs_review");
        List<UUID> stored = materials.passages.values().stream().filter(row -> row.versionId().equals(versionId))
                .sorted(Comparator.comparingInt(PassageRow::ordinal)).map(PassageRow::id).toList();
        assertThat(stored).hasSize(2);
        return new Ingested(uploaded[0], versionId, stored);
    }

    private static String passage(int page, String sectionPath) {
        return "{\"page\":" + page + ",\"sectionPath\":[" + sectionPath + "],\"text\":\"Texto da página " + page
                + "\",\"boxes\":[[0.1,0.2,0.9,0.3]]}";
    }

    /** Ends the ingestion lane's open jobs (an accepted version's index changes), so the next ingestion runs. */
    private void endIngestion() {
        jobs.rows.replaceAll((id, row) -> row.lane().equals("ingestion") && OPEN.contains(row.state())
                ? row.released("cancelled", null, row.updatedAt()) : row);
    }

    /** The version's one step whose job is offered. */
    private UUID offeredJob(UUID versionId) {
        List<UUID> offered = materials.steps.values().stream().filter(step -> step.versionId().equals(versionId))
                .filter(step -> step.jobId() != null && jobs.rows.get(step.jobId()).state().equals("offered"))
                .map(step -> step.jobId()).toList();
        assertThat(offered).as("the offered step of %s", versionId).hasSize(1);
        return offered.getFirst();
    }

    private JsonNode claim(UUID jobId) throws Exception {
        HttpResponse<String> claimed = worker("/internal/v1/jobs/" + jobId + "/claims", null);
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(201);
        return json(claimed);
    }

    private UUID output(UUID jobId, JsonNode claim, byte[] content) throws Exception {
        HttpResponse<String> stored = worker("/internal/v1/jobs/" + jobId + "/claims/"
                + claim.path("claimId").asString() + "/outputs", content, "Content-Type", "application/octet-stream",
                "X-Job-Grant", claim.path("grant").asString());
        assertThat(stored.statusCode()).as(stored.body()).isEqualTo(201);
        return UUID.fromString(json(stored).path("fileId").asString());
    }

    private void report(UUID jobId, JsonNode claim, String result) throws Exception {
        HttpResponse<String> reported = worker("/internal/v1/jobs/" + jobId + "/claims/"
                + claim.path("claimId").asString() + "/outcome", ("{\"outcome\":\"done\",\"result\":" + result + "}")
                .getBytes(StandardCharsets.UTF_8), "Content-Type", "application/json", "X-Job-Grant",
                claim.path("grant").asString());
        assertThat(reported.statusCode()).as(reported.body()).isEqualTo(204);
    }

    private HttpResponse<String> worker(String path, byte[] body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + WORKER_CREDENTIAL)
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A tus upload: created with {@code metadata}, sent in one part and completed; its material and version. */
    private String[] upload(Gm gm, byte[] content, String metadata) throws Exception {
        HttpResponse<String> created = tus(gm, "POST", "/api/v1/uploads", null, "Upload-Length",
                Integer.toString(content.length), "Upload-Metadata", metadata);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String location = created.headers().firstValue("Location").orElseThrow();
        HttpResponse<String> patched = tus(gm, "PATCH", location, content, "Content-Type",
                "application/offset+octet-stream", "Upload-Offset", "0", "Upload-Checksum",
                "sha256 " + Base64.getEncoder().encodeToString(sha256(content)));
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(204);
        HttpResponse<String> completed = tus(gm, "POST", location + "/completion", ("{\"sha256\":\""
                + HexFormat.of().formatHex(sha256(content)) + "\"}").getBytes(StandardCharsets.UTF_8), "Content-Type",
                "application/json");
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(201);
        return new String[] {json(completed).path("materialId").asString(), json(completed).path("versionId")
                .asString()};
    }

    private HttpResponse<String> tus(Gm gm, String method, String path, byte[] body, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Session-Token", gm.session()).header("Tus-Resumable", TUS)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        request.headers(headers);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String metadata(String filename, String... pairs) {
        Base64.Encoder base64 = Base64.getEncoder();
        StringBuilder metadata = new StringBuilder("filename ")
                .append(base64.encodeToString(filename.getBytes(StandardCharsets.UTF_8)));
        for (int i = 0; i < pairs.length; i += 2) {
            metadata.append(',').append(pairs[i]).append(' ')
                    .append(base64.encodeToString(pairs[i + 1].getBytes(StandardCharsets.UTF_8)));
        }
        return metadata.toString();
    }

    private static byte[] pdf(int size) {
        byte[] content = new byte[size];
        byte[] head = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(head, 0, content, 0, head.length);
        for (int i = head.length; i < size; i++) {
            content[i] = (byte) (i * 7 + (i >>> 9));
        }
        return content;
    }

    private static byte[] webp(int size) {
        byte[] content = new byte[size];
        byte[] head = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
        System.arraycopy(head, 0, content, 0, head.length);
        return content;
    }

    private static byte[] sha256(byte[] content) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(content);
    }

    // --- helpers: accounts and requests

    private UUID createSystem(Gm gm, String name) throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/systems", gm.session(), Map.of("name", name));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(json(created).path("id").asString());
    }

    private Gm signUp() throws Exception {
        String sub = "rec-" + UUID.randomUUID();
        JsonNode started = json(send("POST", "/api/v1/sign-in/google", null, null));
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
        assertThat(json(send("POST", "/api/v1/sign-in/google/callback", null, callback)).path("outcome").asString())
                .isEqualTo("username_required");
        Map<String, Object> complete = new LinkedHashMap<>();
        complete.put("attemptToken", started.path("attemptToken").asString());
        complete.put("username", "r" + sub.substring(sub.length() - 12).replace("-", "_"));
        complete.put("termsAccepted", true);
        complete.put("language", "pt-BR");
        HttpResponse<String> response = send("POST", "/api/v1/sign-in/complete", null, complete);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        AccountRow row = accounts.findByGoogleIdFingerprint(vault.fingerprint("account", "google_id", sub)).orElseThrow();
        String session = json(response).path("sessionToken").asString();
        JsonNode current = ok(get("/api/v1/campaigns/current", session));
        return new Gm(row.id(), session, UUID.fromString(current.path("system").path("id").asString()),
                UUID.fromString(current.path("id").asString()));
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

    private HttpResponse<byte[]> bytes(String path, String session) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + CREDENTIAL)
                .header("Accept", "image/webp").GET();
        if (session != null) {
            request.header("X-Session-Token", session);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> send(String method, String path, String session, Map<String, Object> body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + CREDENTIAL);
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

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }
}
