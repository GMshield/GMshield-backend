package io.github.gmshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.CrossOwnerAuditStores.Step;
import io.github.gmshield.accounts.data.AccountStore;
import io.github.gmshield.accounts.data.InMemoryAccountStore;
import io.github.gmshield.accounts.data.InMemorySessionStore;
import io.github.gmshield.accounts.data.InMemorySignInAttemptStore;
import io.github.gmshield.accounts.data.SessionStore;
import io.github.gmshield.accounts.data.SignInAttemptStore;
import io.github.gmshield.artifacts.data.InMemoryArtifactIndexStore;
import io.github.gmshield.artifacts.data.InMemoryArtifactVersionStore;
import io.github.gmshield.audit.data.AuditStore;
import io.github.gmshield.audit.data.InMemoryAuditStore;
import io.github.gmshield.conversations.data.ConversationTestStores;
import io.github.gmshield.conversations.data.InMemoryConversationStore;
import io.github.gmshield.conversations.data.InMemorySearchStore;
import io.github.gmshield.conversations.data.InMemoryTurnReferenceStore;
import io.github.gmshield.jobs.data.InMemoryJobStorage;
import io.github.gmshield.jobs.data.InMemoryJobStream;
import io.github.gmshield.jobs.data.JobMemoryStores;
import io.github.gmshield.jobs.data.MutableClock;
import io.github.gmshield.knowledge.data.InMemoryKnowledgeStore;
import io.github.gmshield.knowledge.data.InMemoryOntologyStore;
import io.github.gmshield.knowledge.data.InMemoryRecordStore;
import io.github.gmshield.library.data.InMemoryDeletionStore;
import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.data.LibraryTestStores;
import io.github.gmshield.materials.data.InMemoryIndexStore;
import io.github.gmshield.materials.data.InMemoryMaterialStore;
import io.github.gmshield.materials.data.InMemoryUploadStorage;
import io.github.gmshield.materials.data.MaterialTestStores;
import io.github.gmshield.scope.data.InMemoryScopeStore;
import io.github.gmshield.vault.data.DataKeyStore;
import io.github.gmshield.vault.data.InMemoryDataKeyStore;
import io.github.gmshield.vault.service.Vault;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.assertj.core.api.SoftAssertions;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * Story 3.13 part C: the cross-owner audit. Account A owns one item of every library kind of Epic 3 and before: a
 * system (from Story 4.1 with its core record types, and from Story 4.3 the spell record of its accepted version) and
 * a campaign with a changed scope; a material uploaded, ingested and accepted, then replaced by a second version that
 * is accepted too (the first superseded), with its pages, pictures and passages; an image set with its
 * open uploads; a conversation with a done, bookmarked turn whose reference opens a page (from Story 4.3 part C it
 * also cites the record, which becomes a reference of its own), a done, dismissed turn, a
 * search, and a turn still waiting in a second conversation; from Story 5.1 an artifact of the campaign (from part B
 * indexed, and cited by the bookmarked turn too). Account B then walks {@link #ROUTES}, every public route
 * that names a library item by id, with A's ids: each answer is {@code request.not_found} (404) with the same body as
 * a random id on that route (the trace id aside), and a route with a container and an item ({@code own}) answers the
 * same with B's own container. B's body and header fields that name an item ({@link #probes}) answer exactly what a
 * random id gets. Afterwards the compared tables ({@link CrossOwnerAuditStores#tables}) are as they were (no job, no
 * event, no row, no version, bookmark, dismissal or last question changed), A reads back each item unchanged, and B's
 * own lists hold none of A's ids.
 *
 * <p>Here the audit runs on the in-memory twins of the stores ({@link InMemoryCrossOwnerAuditStores}).
 * {@code CrossOwnerAuditIntegrationTest} runs the same audit, its routes, probes and worlds, on PostgreSQL and Redis
 * through {@link #against}: whatever the audit reads beside HTTP goes through {@link CrossOwnerAuditStores}.
 *
 * <p>{@code CrossOwnerRouteGuardTest} keeps {@link #ROUTES} complete against the public OpenAPI document.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"GMSHIELD_MODE=test",
    "gmshield.conversations.keep-alive=400ms", "gmshield.conversations.poll=100ms"})
@ActiveProfiles({"test-mode", "unit"})
class CrossOwnerAuditFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CREDENTIAL = "gmshield-test-mode-frontend-credential-not-a-secret";
    private static final String WORKER_CREDENTIAL = "gmshield-test-mode-worker-credential-not-a-secret";
    private static final Instant START = Instant.parse("2026-11-22T12:00:00Z");
    private static final String TUS = "1.0.0";
    private static final int MIB = 1024 * 1024;
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final int TIMEOUT_SECONDS = 15;
    /** A row added to the job or the search table, as {@link #differences} writes it. */
    private static final Pattern ADDED_SEARCH_OR_JOB = Pattern.compile("(job|search) [0-9a-f-]{36} added");
    private static final byte[] PART = jpeg(9000);
    private static final String QUESTION = "Quanto dano causa Fire Bolt?";
    private static final String SCOPE_CHANGE = "{\"version\":{scopeVersion},\"campaignTier\":false,\"systemTier\":false,"
            + "\"otherTier\":true,\"modelKnowledge\":true,\"materialsOff\":[]}";
    /** A new artifact (Story 5.1): valid, so only the campaign's owner decides the answer. */
    private static final String NEW_ARTIFACT = "{\"recordType\":\"item\",\"name\":\"Tomado\","
            + "\"fields\":[{\"name\":\"Nota\",\"value\":\"Tomado\"}],\"content\":\"Tomado\"}";
    /** A record's correction (Story 4.5): valid, so only the record's owner and scope decide the answer. */
    private static final String RECORD_CORRECTION = "{\"version\":{recordVersion},\"name\":\"Tomado\","
            + "\"recordType\":\"spell\",\"fields\":[{\"name\":\"Nota\",\"value\":\"Tomado\"}]}";

    /**
     * One public route that names a library item by id, as the public OpenAPI document writes it. {@code params} are
     * pairs of a path parameter and what it holds: an item of the world ({@code campaign}, {@code turn}...) or a
     * number. {@code json} is the body, its {@code {key}} placeholders filled from the world (a version read before);
     * {@code part} sends a tus part instead. {@code own} names the parameter that another variant fills with B's own
     * item of the same kind.
     */
    record Route(String method, String template, List<String> params, String query, String json, boolean part,
                 List<String> headers, String own) {

        String key() {
            return method + " " + template;
        }

        Route query(String text) {
            return new Route(method, template, params, text, json, part, headers, own);
        }

        Route json(String body) {
            return new Route(method, template, params, query, body, part, headers, own);
        }

        Route tusPart() {
            return new Route(method, template, params, query, json, true, headers, own);
        }

        Route headers(String... more) {
            return new Route(method, template, params, query, json, part, List.of(more), own);
        }

        Route own(String param) {
            return new Route(method, template, params, query, json, part, headers, param);
        }

        /** The items of the world this route's path names. */
        List<String> items() {
            List<String> items = new ArrayList<>();
            for (int i = 1; i < params.size(); i += 2) {
                if (!DIGITS.matcher(params.get(i)).matches()) {
                    items.add(params.get(i));
                }
            }
            return items;
        }
    }

    private static Route route(String method, String template, String... params) {
        return new Route(method, template, List.of(params), null, null, false, List.of(), null);
    }

    private static final String[] TUS_HEADER = {"Tus-Resumable", TUS};

    /** Every public route that names a library item in its path: one row per method and path. */
    static final List<Route> ROUTES = List.of(
            // systems
            route("GET", "/api/v1/systems/{id}", "id", "system"),
            route("PATCH", "/api/v1/systems/{id}", "id", "system")
                    .json("{\"version\":{systemVersion},\"name\":\"Tomado\"}"),
            route("GET", "/api/v1/systems/{id}/deletion", "id", "system"),
            route("DELETE", "/api/v1/systems/{id}", "id", "system"),
            // a system's record types (Story 4.1)
            route("GET", "/api/v1/systems/{id}/record-types", "id", "system"),
            route("GET", "/api/v1/record-types/{id}", "id", "recordType"),
            route("PATCH", "/api/v1/record-types/{id}", "id", "recordType")
                    .json("{\"version\":{recordTypeVersion},\"name\":\"Tomado\"}"),
            route("DELETE", "/api/v1/record-types/{id}", "id", "recordType"),
            // a record type's records, and a record as its system shows it with its version's pages (Story 4.3)
            route("GET", "/api/v1/record-types/{id}/records", "id", "recordType"),
            route("GET", "/api/v1/systems/{systemId}/records/{recordId}", "systemId", "system", "recordId", "record")
                    .own("systemId"),
            // a record corrected as its system shows it (Story 4.5)
            route("PATCH", "/api/v1/systems/{systemId}/records/{recordId}", "systemId", "system", "recordId", "record")
                    .json(RECORD_CORRECTION).own("systemId"),
            // a record deleted as its system shows it (Story 4.5 part B)
            route("DELETE", "/api/v1/systems/{systemId}/records/{recordId}", "systemId", "system", "recordId",
                    "record").own("systemId"),
            route("GET", "/api/v1/systems/{systemId}/records/{recordId}/pages/{page}", "systemId", "system",
                    "recordId", "record", "page", "1").own("systemId"),
            route("GET", "/api/v1/systems/{systemId}/records/{recordId}/pages/{page}/picture", "systemId", "system",
                    "recordId", "record", "page", "1").headers("Accept", "image/webp").own("systemId"),
            // a system's and a material's ontology proposals, the decisions on one, and a system's terms (Story 4.4)
            route("GET", "/api/v1/systems/{id}/proposals", "id", "system"),
            route("GET", "/api/v1/systems/{id}/terms", "id", "system"),
            route("GET", "/api/v1/materials/{id}/proposals", "id", "material"),
            route("POST", "/api/v1/proposals/{id}/approval", "id", "proposal")
                    .json("{\"version\":{proposalVersion},\"name\":\"Tomado\"}"),
            route("POST", "/api/v1/proposals/{id}/merge", "id", "proposal")
                    .json("{\"version\":{proposalVersion},\"targetId\":\"{recordType}\"}"),
            route("POST", "/api/v1/proposals/{id}/rejection", "id", "proposal")
                    .json("{\"version\":{proposalVersion}}"),
            // campaigns
            route("GET", "/api/v1/campaigns/{id}", "id", "campaign"),
            route("PATCH", "/api/v1/campaigns/{id}", "id", "campaign")
                    .json("{\"version\":{campaignVersion},\"name\":\"Tomada\",\"archived\":true}"),
            route("GET", "/api/v1/campaigns/{id}/deletion", "id", "campaign"),
            route("DELETE", "/api/v1/campaigns/{id}", "id", "campaign"),
            // scope settings
            route("GET", "/api/v1/campaigns/{id}/scope", "id", "campaign"),
            route("PUT", "/api/v1/campaigns/{id}/scope", "id", "campaign").json(SCOPE_CHANGE),
            // a campaign's conversations, questions, searches and sources
            route("GET", "/api/v1/campaigns/{campaignId}/conversation", "campaignId", "campaign"),
            route("GET", "/api/v1/campaigns/{campaignId}/conversations", "campaignId", "campaign")
                    .query("area=questions"),
            route("POST", "/api/v1/campaigns/{campaignId}/turns", "campaignId", "campaign")
                    .json("{\"question\":\"Quem leva a coroa?\",\"conversationId\":\"{conversation}\"}"),
            route("POST", "/api/v1/campaigns/{campaignId}/searches", "campaignId", "campaign")
                    .json("{\"query\":\"Fire Bolt\"}"),
            // a record as a campaign shows it, for a search's record result (Story 4.3 part B)
            route("GET", "/api/v1/campaigns/{campaignId}/records/{recordId}", "campaignId", "campaign", "recordId",
                    "record").own("campaignId"),
            // a record corrected as a campaign shows it (Story 4.5)
            route("PATCH", "/api/v1/campaigns/{campaignId}/records/{recordId}", "campaignId", "campaign", "recordId",
                    "record").json(RECORD_CORRECTION).own("campaignId"),
            // a record deleted as a campaign shows it (Story 4.5 part B)
            route("DELETE", "/api/v1/campaigns/{campaignId}/records/{recordId}", "campaignId", "campaign",
                    "recordId", "record").own("campaignId"),
            // a campaign's artifacts and one of them (Story 5.1)
            route("GET", "/api/v1/campaigns/{campaignId}/artifacts", "campaignId", "campaign"),
            route("POST", "/api/v1/campaigns/{campaignId}/artifacts", "campaignId", "campaign").json(NEW_ARTIFACT),
            route("GET", "/api/v1/campaigns/{campaignId}/artifacts/{artifactId}", "campaignId", "campaign",
                    "artifactId", "artifact").own("campaignId"),
            route("GET", "/api/v1/campaigns/{campaignId}/sources/{versionId}/pages/{page}", "campaignId", "campaign",
                    "versionId", "version", "page", "1").own("campaignId"),
            route("GET", "/api/v1/campaigns/{campaignId}/sources/{versionId}/pages/{page}/picture", "campaignId",
                    "campaign", "versionId", "version", "page", "1").headers("Accept", "image/webp").own("campaignId"),
            // conversations
            route("GET", "/api/v1/conversations/{conversationId}", "conversationId", "conversation"),
            route("PATCH", "/api/v1/conversations/{conversationId}", "conversationId", "conversation")
                    .json("{\"version\":{conversationVersion},\"name\":\"Tomada\"}"),
            route("GET", "/api/v1/conversations/{conversationId}/deletion", "conversationId", "conversation"),
            route("DELETE", "/api/v1/conversations/{conversationId}", "conversationId", "conversation"),
            route("GET", "/api/v1/conversations/{conversationId}/turns", "conversationId", "conversation"),
            // image sets
            route("POST", "/api/v1/image-sets/{id}/images/{position}", "id", "imageSet", "position", "2")
                    .json("{\"name\":\"b.png\",\"length\":9000}"),
            route("DELETE", "/api/v1/image-sets/{id}/images/{position}", "id", "imageSet", "position", "2"),
            // materials, their versions, pages and pictures
            route("GET", "/api/v1/materials/{id}", "id", "material"),
            route("PATCH", "/api/v1/materials/{id}", "id", "material")
                    .json("{\"version\":{materialVersion},\"belonging\":{\"systems\":[\"{system}\"]}}"),
            route("DELETE", "/api/v1/materials/{id}", "id", "material"),
            route("POST", "/api/v1/materials/{id}/accept", "id", "material"),
            route("POST", "/api/v1/materials/{id}/discard", "id", "material"),
            route("POST", "/api/v1/materials/{id}/reprocess", "id", "material"),
            route("POST", "/api/v1/materials/{id}/retry", "id", "material"),
            route("GET", "/api/v1/materials/{id}/deletion", "id", "material"),
            route("GET", "/api/v1/materials/{id}/report", "id", "material"),
            route("GET", "/api/v1/materials/{id}/pages/{page}", "id", "material", "page", "1").query("ground=review"),
            route("GET", "/api/v1/materials/{id}/pages/{page}/picture", "id", "material", "page", "1")
                    .query("ground=review").headers("Accept", "image/webp"),
            route("DELETE", "/api/v1/materials/{id}/versions/{versionId}", "id", "material", "versionId",
                    "superseded").own("id"),
            // searches
            route("GET", "/api/v1/searches/{searchId}", "searchId", "search"),
            // turns: bookmark, dismissal, events, stop, references and their pages and pictures
            route("PUT", "/api/v1/turns/{turnId}/bookmark", "turnId", "dismissedTurn"),
            route("DELETE", "/api/v1/turns/{turnId}/bookmark", "turnId", "turn"),
            route("PUT", "/api/v1/turns/{turnId}/dismissal", "turnId", "turn"),
            route("DELETE", "/api/v1/turns/{turnId}/dismissal", "turnId", "dismissedTurn"),
            route("GET", "/api/v1/turns/{turnId}/events", "turnId", "turn").headers("Accept", "text/event-stream"),
            route("POST", "/api/v1/turns/{turnId}/stop", "turnId", "waitingTurn"),
            route("GET", "/api/v1/turns/{turnId}/references/{number}/pages/{page}", "turnId", "turn", "number", "1",
                    "page", "1"),
            route("GET", "/api/v1/turns/{turnId}/references/{number}/pages/{page}/picture", "turnId", "turn",
                    "number", "1", "page", "1").headers("Accept", "image/webp"),
            // uploads (an image of the set, still open)
            route("HEAD", "/api/v1/uploads/{id}", "id", "upload").headers(TUS_HEADER),
            route("PATCH", "/api/v1/uploads/{id}", "id", "upload").tusPart().headers(TUS_HEADER),
            route("POST", "/api/v1/uploads/{id}/completion", "id", "upload")
                    .json("{\"sha256\":\"" + HexFormat.of().formatHex(sha256(PART)) + "\"}").headers(TUS_HEADER));

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
    private InMemoryJobStorage jobs;
    @Autowired
    private InMemoryJobStream stream;
    @Autowired
    private InMemoryLibraryStore library;
    @Autowired
    private InMemoryDeletionStore deletions;
    @Autowired
    private InMemoryScopeStore scopes;
    @Autowired
    private InMemoryKnowledgeStore knowledge;
    @Autowired
    private InMemoryRecordStore records;
    @Autowired
    private InMemoryOntologyStore ontology;
    @Autowired
    private InMemoryArtifactVersionStore artifactVersions;
    @Autowired
    private InMemoryArtifactIndexStore artifactIndex;
    @Autowired
    private InMemoryMaterialStore materials;
    @Autowired
    private InMemoryUploadStorage uploads;
    @Autowired
    private InMemoryIndexStore index;
    @Autowired
    private InMemoryConversationStore conversations;
    @Autowired
    private InMemoryTurnReferenceStore references;
    @Autowired
    private InMemorySearchStore searches;
    @Autowired
    private MutableClock clock;
    @Autowired
    private Vault vault;
    /** What the audit reads beside HTTP: the in-memory twins here, unless {@link #against} gave others. */
    private CrossOwnerAuditStores stores;

    private final HttpClient client = HttpClient.newHttpClient();

    /**
     * The audit against the application at {@code port}, reading {@code stores}: the integration audit's way in. Its
     * caller runs {@link #start} before each test.
     */
    static CrossOwnerAuditFlowTest against(int port, CrossOwnerAuditStores stores) {
        CrossOwnerAuditFlowTest audit = new CrossOwnerAuditFlowTest();
        audit.port = port;
        audit.stores = stores;
        return audit;
    }

    record Gm(UUID id, String session, UUID campaign) {
    }

    /**
     * An account and its items by name: ids, and the versions a change names. {@code hidden}: ids that no answer to
     * another account may name either, though no route takes them (the account's own id, its jobs' ids).
     */
    record World(Gm gm, Map<String, String> items, Set<String> hidden) {

        String get(String item) {
            return items.get(item);
        }

        /** Every id of the world (the versions aside), the hidden ones included. */
        Set<String> ids() {
            Set<String> ids = items.values().stream().filter(value -> UUID_TEXT.matcher(value).matches())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            ids.addAll(hidden);
            return ids;
        }
    }

    /** A request as it is sent with a session: its body none when null. */
    record Request(String method, String path, HttpRequest.BodyPublisher body, List<String> headers) {
    }

    /** An answer: its status, its content type, its body as text and its tus offset headers (HEAD has no body). */
    record Answer(int status, String contentType, String body, String tus) {
    }

    /** A completed upload: its material and version. */
    record Uploaded(String materialId, UUID versionId) {
    }

    @BeforeEach
    void start() {
        if (stores == null) {
            stores = new InMemoryCrossOwnerAuditStores(new InMemoryCrossOwnerAuditStores.Twins(accounts, jobs, stream,
                    library, deletions, scopes, knowledge, records, ontology, artifactVersions, artifactIndex, materials,
                    uploads, index, conversations, references, searches, clock, vault), START);
        }
        stores.start();
    }

    // --- route ids

    @Test
    void everyRouteThatNamesAnotherAccountsItemAnswersNotFoundAsARandomIdDoesAndChangesNothing() throws Exception {
        World a = theirs();
        World b = mine();
        Map<String, String> readBefore = reads(a);
        Map<String, Map<Object, Object>> before = stores.tables(owners(a, b));
        Map<String, List<UUID>> offeredBefore = stores.offered();
        assertHeld(before, a);

        SoftAssertions softly = new SoftAssertions();
        for (Route route : ROUTES) {
            Answer answer = send(b.gm(), request(route, a.items(), a.items()), route.key());
            Answer random = send(b.gm(), request(route, randomFor(route, a.items()), a.items()), route.key());
            softly.assertThat(answer.status()).as("%s: another account's id answered %d %s", route.key(),
                    answer.status(), answer.body()).isEqualTo(404);
            if (!route.method().equals("HEAD")) {
                softly.assertThat(code(answer)).as("%s: another account's id answered %s", route.key(), answer.body())
                        .isEqualTo("request.not_found");
            }
            softly.assertThat(normal(answer, Set.of())).as("%s: another account's id is told apart from a random one",
                    route.key()).isEqualTo(normal(random, Set.of()));
            softly.assertThat(leaks(answer, a, Set.of())).as("%s: the answer names A's items", route.key()).isEmpty();
            if (route.own() != null) {
                Map<String, String> mixed = new HashMap<>(a.items());
                String item = route.params().get(route.params().indexOf(route.own()) + 1);
                mixed.put(item, b.get(item));
                Answer own = send(b.gm(), request(route, mixed, a.items()), route.key());
                softly.assertThat(own.status()).as("%s: B's own %s with A's item answered %d %s", route.key(), item,
                        own.status(), own.body()).isEqualTo(404);
                softly.assertThat(code(own)).as("%s: B's own %s with A's item answered %s", route.key(), item,
                        own.body()).isEqualTo("request.not_found");
                softly.assertThat(leaks(own, a, Set.of())).as("%s: B's own %s with A's item names A's items",
                        route.key(), item).isEmpty();
                softly.assertThat(normal(own, Set.of())).as("%s: B's own %s with A's item is told apart", route.key(),
                        item).isEqualTo(normal(random, Set.of()));
            }
        }
        // Nothing was written: no row, job, version, bookmark, dismissal or last question changed, nothing offered.
        softly.assertThat(differences(before, stores.tables(owners(a, b)))).as("tables changed by B's requests")
                .isEmpty();
        softly.assertThat(stores.offered()).as("work offered for B's requests").isEqualTo(offeredBefore);
        softly.assertAll();
        assertUnchanged(a, readBefore);
        assertNoneOfTheirs(b, a);
    }

    // --- body and header ids

    /**
     * A field of B's request that names an item: {@code item} is the kind it names, {@code request} B's request with
     * that id, and {@code status} and {@code code} the answer that A's id and a random id both get. {@code own} is B's
     * item of that kind, which the same request accepts with {@code ok}: the field is really examined. {@code search}:
     * the answer is a search, read back as well.
     */
    record Probe(String field, String item, int status, String code, String own, int ok, boolean search,
                 Function<String, Request> request) {
    }

    private static Probe probe(String field, String item, int status, String code, int ok,
                               Function<String, Request> request) {
        return new Probe(field, item, status, code, item, ok, false, request);
    }

    /** B's requests that name an item in a field; each reads B's versions when it is made. */
    private List<Probe> probes(World b) {
        String campaign = b.get("campaign");
        String material = b.get("material");
        return List.of(
                probe("campaignId in PUT /campaigns/current", "campaign", 404, "request.not_found", 200,
                        id -> json("PUT", "/api/v1/campaigns/current", "{\"campaignId\":\"" + id + "\"}")),
                probe("systemId in POST /campaigns", "system", 404, "request.not_found", 201,
                        id -> json("POST", "/api/v1/campaigns", "{\"systemId\":\"" + id + "\",\"name\":\"Coroa\"}")),
                probe("conversationId in POST /campaigns/{campaignId}/turns", "conversation", 404, "request.not_found",
                        201, id -> json("POST", "/api/v1/campaigns/" + campaign + "/turns",
                                "{\"question\":\"Quem leva a coroa?\",\"conversationId\":\"" + id + "\"}")),
                new Probe("materialId in POST /campaigns/{campaignId}/searches", "material", 202, null, "material", 202,
                        true, id -> json("POST", "/api/v1/campaigns/" + campaign + "/searches",
                                "{\"query\":\"Fire Bolt\",\"materialId\":\"" + id + "\"}")),
                probe("materialsOff in PUT /campaigns/{id}/scope", "material", 400, "scope.material_unknown", 200,
                        id -> json("PUT", "/api/v1/campaigns/" + campaign + "/scope", "{\"version\":"
                                + b.get("scopeVersion") + ",\"campaignTier\":true,\"systemTier\":true,"
                                + "\"otherTier\":true,\"modelKnowledge\":false,\"materialsOff\":[\"" + id + "\"]}")),
                probe("belonging.campaign in PATCH /materials/{id}", "campaign", 400, "material.belonging_invalid", 200,
                        id -> json("PATCH", "/api/v1/materials/" + material, "{\"version\":"
                                + b.get("materialVersion") + ",\"belonging\":{\"campaign\":\"" + id + "\"}}")),
                probe("belonging.systems in PATCH /materials/{id}", "system", 400, "material.belonging_invalid", 200,
                        id -> json("PATCH", "/api/v1/materials/" + material, "{\"version\":"
                                + b.get("materialVersion") + ",\"belonging\":{\"systems\":[\"" + id + "\"]}}")),
                probe("upload metadata campaign", "campaign", 400, "upload.metadata_invalid", 201,
                        id -> uploadCreation(metadata("Notas.pdf", "campaign", id))),
                probe("upload metadata system", "system", 400, "upload.metadata_invalid", 201,
                        id -> uploadCreation(metadata("Notas.pdf", "system", id))),
                probe("upload metadata systems", "system", 400, "upload.metadata_invalid", 201,
                        id -> uploadCreation(metadata("Notas.pdf", "systems", b.get("system2") + "," + id))),
                probe("upload metadata material", "material", 404, "request.not_found", 201,
                        id -> uploadCreation(metadata("Notas.pdf", "material", id))),
                probe("image-set campaign", "campaign", 400, "upload.metadata_invalid", 201,
                        id -> imageSet("\"kind\":\"other\",\"campaign\":\"" + id + "\"")),
                probe("image-set system", "system", 400, "upload.metadata_invalid", 201,
                        id -> imageSet("\"kind\":\"other\",\"system\":\"" + id + "\"")),
                probe("image-set systems", "system", 400, "upload.metadata_invalid", 201,
                        id -> imageSet("\"kind\":\"other\",\"systems\":[\"" + b.get("system2") + "\",\"" + id + "\"]")),
                // The control replaces B's other material: the first is already being replaced by then.
                new Probe("image-set material", "material", 404, "request.not_found", "otherMaterial", 201, false,
                        id -> imageSet("\"material\":\"" + id + "\"")),
                probe("?system= in GET /campaigns", "system", 404, "request.not_found", 200,
                        id -> new Request("GET", "/api/v1/campaigns?system=" + id, null, List.of())),
                probe("?system= in GET /materials", "system", 404, "request.not_found", 200,
                        id -> new Request("GET", "/api/v1/materials?system=" + id, null, List.of())),
                // Story 4.4: a merge into another account's record type is refused as an unknown target is.
                probe("targetId in POST /proposals/{id}/merge", "recordType", 400, "ontology.merge_target_invalid", 200,
                        id -> json("POST", "/api/v1/proposals/" + b.get("proposal") + "/merge", "{\"version\":"
                                + b.get("proposalVersion") + ",\"targetId\":\"" + id + "\"}")));
    }

    @Test
    void everyFieldThatNamesAnotherAccountsItemAnswersExactlyAsARandomIdDoes() throws Exception {
        World a = theirs();
        World b = mine();
        Map<String, String> readBefore = reads(a);
        Map<String, String> mineBefore = reads(b);
        Map<String, Map<Object, Object>> before = stores.tables(owners(a, b));

        SoftAssertions softly = new SoftAssertions();
        int searchesMade = 0;
        for (Probe probe : probes(b)) {
            String theirs = a.get(probe.item());
            String random = UUID.randomUUID().toString();
            Answer answer = send(b.gm(), probe.request().apply(theirs));
            Answer unknown = send(b.gm(), probe.request().apply(random));
            softly.assertThat(answer.status()).as("%s: another account's id answered %d %s", probe.field(),
                    answer.status(), answer.body()).isEqualTo(unknown.status()).isEqualTo(probe.status());
            if (probe.code() != null) {
                softly.assertThat(code(answer)).as("%s: another account's id answered %s", probe.field(),
                        answer.body()).isEqualTo(code(unknown)).isEqualTo(probe.code());
            }
            softly.assertThat(normal(answer, Set.of(theirs))).as("%s: another account's id is told apart from a random"
                    + " one", probe.field()).isEqualTo(normal(unknown, Set.of(random)));
            softly.assertThat(leaks(answer, a, Set.of(theirs))).as("%s: the answer names A's items", probe.field())
                    .isEmpty();
            if (probe.search()) {
                searchesMade += 2;
                Answer read = send(b.gm(), new Request("GET", "/api/v1/searches/" + json(answer).path("id").asString(),
                        null, List.of()));
                Answer readUnknown = send(b.gm(), new Request("GET", "/api/v1/searches/"
                        + json(unknown).path("id").asString(), null, List.of()));
                softly.assertThat(read.status()).as("%s: the search read back", probe.field()).isEqualTo(200);
                softly.assertThat(normal(read, Set.of(theirs))).as("%s: the search read back is told apart",
                        probe.field()).isEqualTo(normal(readUnknown, Set.of(random)));
                softly.assertThat(leaks(read, a, Set.of(theirs))).as("%s: the search names A's items", probe.field())
                        .isEmpty();
            }
        }
        softly.assertAll();

        // Only B's searches were recorded, each with its job; nothing else was added, changed or removed.
        List<String> changed = differences(before, stores.tables(owners(a, b)));
        assertThat(changed).as("tables changed by B's requests")
                .allMatch(line -> ADDED_SEARCH_OR_JOB.matcher(line).matches());
        List<CrossOwnerAuditStores.Job> made = stores.jobs(owners(a, b)).stream()
                .filter(row -> !before.get("job").containsKey(row.id())).toList();
        assertThat(made).hasSize(searchesMade).allSatisfy(job -> {
            assertThat(job.ownerId()).isEqualTo(b.gm().id());
            assertThat(job.kind()).isEqualTo("library.search");
        });
        List<CrossOwnerAuditStores.Search> recorded = stores.searches(owners(a, b)).stream()
                .filter(row -> !before.get("search").containsKey(row.id())).toList();
        assertThat(recorded).hasSize(searchesMade).allSatisfy(search -> {
            assertThat(search.ownerId()).isEqualTo(b.gm().id());
            assertThat(search.campaignId()).isEqualTo(b.gm().campaign());
        });
        assertUnchanged(a, readBefore);
        assertThat(reads(b)).as("B's items after its requests").isEqualTo(mineBefore);

        // Each field is really examined: B's own item of the same kind is accepted.
        SoftAssertions controls = new SoftAssertions();
        for (Probe probe : probes(b)) {
            Answer own = send(b.gm(), probe.request().apply(b.get(probe.own())), probe.field() + " with B's own "
                    + probe.own());
            controls.assertThat(own.status()).as("%s: B's own %s answered %d %s", probe.field(), probe.own(),
                    own.status(), own.body()).isEqualTo(probe.ok());
            refresh(b);
        }
        controls.assertAll();
        assertNoneOfTheirs(b, a);
    }

    /**
     * Story 5.1 part B: another account's artifact is never a source of B's answer. B's answer job reads A's artifact id
     * exactly as it reads a random id (refused, naming nothing) while it reads B's own, B's question input lists only
     * B's own artifact, and A's index job's input is read under A's claim only: B's claim gets what a random job gets.
     */
    @Test
    void anotherAccountsArtifactIsReadByNoAnswerAndItsIndexInputOnlyUnderItsOwnClaim() throws Exception {
        Gm a = signUp();
        Gm b = signUp();
        String theirs = artifact(a, "Espada de Kael");
        String own = artifact(b, "Arco de Lia");
        UUID theirJob = stores.jobs(List.of(a.id())).stream().filter(job -> job.ownerId().equals(a.id())
                && job.kind().equals("index.artifact")).map(CrossOwnerAuditStores.Job::id).findFirst().orElseThrow();
        stores.endIngestion();

        HttpResponse<String> asked = ask(b, "{\"question\":\"Espada de Kael?\"}");
        UUID jobId = stores.turnJob(UUID.fromString(json(asked).path("id").asString()));
        HttpResponse<String> claimed = worker("POST", "/internal/v1/jobs/" + jobId + "/claims",
                "{\"providers\":[\"azure-openai\"]}".getBytes(StandardCharsets.UTF_8), "Content-Type",
                "application/json");
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(201);
        JsonNode claim = json(claimed);
        assertThat(claim.path("input").path("tiers").get(0).path("sources").toString()).contains(own)
                .doesNotContain(theirs);

        String random = UUID.randomUUID().toString();
        HttpResponse<String> read = claimed(jobId, claim, "records", "{\"ids\":[\"" + theirs + "\"]}");
        HttpResponse<String> unknown = claimed(jobId, claim, "records", "{\"ids\":[\"" + random + "\"]}");
        assertThat(read.statusCode()).as(read.body()).isEqualTo(unknown.statusCode()).isEqualTo(403);
        assertThat(code(answerOf(read))).isEqualTo("scope.passage_refused");
        assertThat(normal(answerOf(read), Set.of(theirs))).isEqualTo(normal(answerOf(unknown), Set.of(random)));
        assertThat(read.body()).doesNotContain("Kael");
        HttpResponse<String> mineRead = claimed(jobId, claim, "records", "{\"ids\":[\"" + own + "\"]}");
        assertThat(mineRead.statusCode()).as(mineRead.body()).isEqualTo(200);
        assertThat(json(mineRead).path("records").get(0).path("tier").asString()).isEqualTo("campaign");

        String path = "/claims/" + claim.path("claimId").asString() + "/inputs/records";
        HttpResponse<String> input = worker("GET", "/internal/v1/jobs/" + theirJob + path, null, "X-Job-Grant",
                claim.path("grant").asString());
        HttpResponse<String> randomInput = worker("GET", "/internal/v1/jobs/" + UUID.randomUUID() + path, null,
                "X-Job-Grant", claim.path("grant").asString());
        assertThat(input.statusCode()).as(input.body()).isEqualTo(randomInput.statusCode()).isIn(403, 404);
        assertThat(input.body()).doesNotContain("Kael");
    }

    /** A worker call's response as the audit compares answers. */
    private static Answer answerOf(HttpResponse<String> response) {
        return new Answer(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                response.body(), null);
    }

    /** A new artifact of the account's campaign, as its id. */
    private String artifact(Gm gm, String name) throws Exception {
        HttpResponse<String> created = send(gm, "POST", "/api/v1/campaigns/" + gm.campaign() + "/artifacts",
                "{\"recordType\":\"item\",\"name\":\"" + name + "\",\"fields\":[],\"content\":\"Forjada.\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return json(created).path("id").asString();
    }

    // --- what A owns and what B owns

    /**
     * Account A and one item of every kind, as the class says, with the versions its changes name.
     */
    private World theirs() throws Exception {
        Gm gm = signUp();
        Map<String, String> items = new LinkedHashMap<>();
        String system = json(get(gm, "/api/v1/campaigns/current")).path("system").path("id").asString();
        items.put("system", system);
        items.put("campaign", gm.campaign().toString());
        // A core record type of the system (Story 4.1): another account's rename or removal is "not found", not the
        // core refusal that its owner gets.
        JsonNode spell = json(get(gm, "/api/v1/systems/" + system + "/record-types")).path("items").get(1);
        assertThat(spell.path("core").asString()).isEqualTo("spell");
        items.put("recordType", spell.path("id").asString());

        Uploaded first = ingested(gm, metadata("Livro.pdf", "campaign", gm.campaign().toString()));
        Uploaded second = ingested(gm, metadata("Livro 2a impressao.pdf", "material", first.materialId()));
        assertThat(second.materialId()).isEqualTo(first.materialId());
        assertThat(stores.versionState(first.versionId())).isEqualTo("superseded");
        items.put("material", first.materialId());
        items.put("superseded", first.versionId().toString());
        items.put("version", second.versionId().toString());
        UUID passage = stores.passage(second.versionId());
        items.put("passage", passage.toString());
        // Story 4.3: the spell record of the ready version, as A's records list names it (the superseded version's
        // record is not listed).
        JsonNode records = json(get(gm, "/api/v1/record-types/" + items.get("recordType") + "/records"));
        assertThat(records.path("total").asLong()).as(records.toString()).isEqualTo(1);
        assertThat(records.path("items").get(0).path("name").asString()).isEqualTo("Fire Bolt");
        items.put("record", records.path("items").get(0).path("id").asString());
        // Story 4.4: the material's two proposals in the system, a record type kept pending and a term approved.
        items.put("proposal", proposal(gm, system, "record_type").path("id").asString());
        JsonNode term = proposal(gm, system, "term");
        String termProposal = term.path("id").asString();
        items.put("termProposal", termProposal);
        // The second version proposed both again: they moved to it, their versions raised.
        HttpResponse<String> approved = send(gm, "POST", "/api/v1/proposals/" + termProposal + "/approval",
                "{\"version\":" + term.path("version").asLong() + "}");
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        items.put("term", json(approved).path("resultId").asString());

        // Story 5.1: an artifact of the campaign, written by hand.
        HttpResponse<String> artifact = send(gm, "POST", "/api/v1/campaigns/" + gm.campaign() + "/artifacts",
                "{\"recordType\":\"character_sheet\",\"name\":\"Kael\",\"fields\":[{\"name\":\"Força\","
                        + "\"value\":\"16\"}],\"content\":\"Guerreiro do norte.\"}");
        assertThat(artifact.statusCode()).as(artifact.body()).isEqualTo(201);
        items.put("artifact", json(artifact).path("id").asString());
        // Story 5.1 part B: its index job, claimed and done as the worker would, so the ingestion lane is free again;
        // its input is the artifact's one records line, read under that claim only.
        UUID indexJob = stores.jobs(List.of(gm.id())).stream().filter(job -> job.ownerId().equals(gm.id())
                && job.kind().equals("index.artifact")).map(CrossOwnerAuditStores.Job::id).findFirst().orElseThrow();
        JsonNode indexClaim = claim(indexJob);
        assertThat(indexClaim.path("input").path("source").asString()).isEqualTo(items.get("artifact"));
        HttpResponse<String> line = worker("GET", "/internal/v1/jobs/" + indexJob + "/claims/"
                + indexClaim.path("claimId").asString() + "/inputs/records", null, "X-Job-Grant",
                indexClaim.path("grant").asString());
        assertThat(line.statusCode()).as(line.body()).isEqualTo(200);
        assertThat(line.body()).contains(items.get("artifact"));
        HttpResponse<String> indexed = claimed(indexJob, indexClaim, "outcome",
                "{\"outcome\":\"done\",\"result\":{\"indexRecipe\":\"2\",\"points\":1}}");
        assertThat(indexed.statusCode()).as(indexed.body()).isEqualTo(204);

        HttpResponse<String> scoped = send(gm, "PUT", "/api/v1/campaigns/" + gm.campaign() + "/scope", "{\"version\":0,"
                + "\"campaignTier\":true,\"systemTier\":true,\"otherTier\":true,\"modelKnowledge\":false,"
                + "\"materialsOff\":[]}");
        assertThat(scoped.statusCode()).as(scoped.body()).isEqualTo(200);

        HttpResponse<String> set = send(gm, "POST", "/api/v1/image-sets", "{\"name\":\"Ficha\",\"kind\":\"other\","
                + "\"language\":\"detect\",\"campaign\":\"" + gm.campaign() + "\",\"images\":[{\"name\":\"a.jpg\","
                + "\"length\":9000},{\"name\":\"b.png\",\"length\":9000}]}");
        assertThat(set.statusCode()).as(set.body()).isEqualTo(201);
        items.put("imageSet", json(set).path("id").asString());
        items.put("imageSetMaterial", json(set).path("materialId").asString());
        items.put("upload", json(set).path("images").get(0).path("uploadId").asString());

        HttpResponse<String> asked = ask(gm, "{\"question\":\"" + QUESTION + "\"}");
        // Story 4.3 part C: the turn also cites the spell record, so it has a record reference too.
        UUID turn = answered(asked, passage, items.get("record"), items.get("artifact"));
        items.put("conversation", json(asked).path("conversationId").asString());
        items.put("turn", turn.toString());
        assertThat(send(gm, "PUT", "/api/v1/turns/" + turn + "/bookmark", "").statusCode()).isEqualTo(204);
        UUID dismissed = answered(ask(gm, "{\"question\":\"E Ray of Frost?\"}"), passage, null);
        items.put("dismissedTurn", dismissed.toString());
        assertThat(send(gm, "PUT", "/api/v1/turns/" + dismissed + "/dismissal", "").statusCode()).isEqualTo(204);

        HttpResponse<String> searched = send(gm, "POST", "/api/v1/campaigns/" + gm.campaign() + "/searches",
                "{\"query\":\"Fire Bolt\"}");
        assertThat(searched.statusCode()).as(searched.body()).isEqualTo(202);
        items.put("search", json(searched).path("id").asString());
        HttpResponse<String> waiting = ask(gm, "{\"question\":\"Quem leva a coroa?\",\"area\":\"rules\"}");
        items.put("waitingTurn", json(waiting).path("id").asString());
        items.put("rulesConversation", json(waiting).path("conversationId").asString());

        items.put("systemVersion", version(gm, "/api/v1/systems/" + system));
        items.put("recordTypeVersion", version(gm, "/api/v1/record-types/" + items.get("recordType")));
        items.put("recordVersion", version(gm, "/api/v1/systems/" + system + "/records/" + items.get("record")));
        items.put("proposalVersion", proposal(gm, system, "record_type").path("version").asString());
        items.put("campaignVersion", version(gm, "/api/v1/campaigns/" + gm.campaign()));
        items.put("scopeVersion", version(gm, "/api/v1/campaigns/" + gm.campaign() + "/scope"));
        items.put("materialVersion", version(gm, "/api/v1/materials/" + first.materialId()));
        items.put("conversationVersion", version(gm, "/api/v1/conversations/" + items.get("conversation")));
        Set<String> hidden = new LinkedHashSet<>();
        hidden.add(gm.id().toString());
        stores.jobIds(gm.id()).forEach(job -> hidden.add(job.toString()));
        World world = new World(gm, Map.copyOf(items), Set.copyOf(hidden));

        // Each id is live: A's own reads of the audited routes answer 200.
        SoftAssertions softly = new SoftAssertions();
        for (Route route : ROUTES) {
            if (route.method().equals("GET") || route.method().equals("HEAD")) {
                Answer read = send(gm, request(route, world.items(), world.items()));
                softly.assertThat(read.status()).as("%s for its owner: %s", route.key(), read.body()).isEqualTo(200);
            }
        }
        softly.assertAll();
        return world;
    }

    /**
     * Account B, with its own items for the probes' controls: two systems, a campaign, two materials of the campaign
     * (ingested and accepted), a conversation, and the versions its changes name. Its items stay writable: a control
     * that changes a version writes the new one ({@link #refresh}).
     */
    private World mine() throws Exception {
        Gm gm = signUp();
        Map<String, String> items = new LinkedHashMap<>();
        items.put("system", json(get(gm, "/api/v1/campaigns/current")).path("system").path("id").asString());
        items.put("campaign", gm.campaign().toString());
        HttpResponse<String> system = send(gm, "POST", "/api/v1/systems", "{\"name\":\"Outro sistema\"}");
        assertThat(system.statusCode()).as(system.body()).isEqualTo(201);
        items.put("system2", json(system).path("id").asString());
        items.put("material", ingested(gm, metadata("Notas.pdf", "campaign", gm.campaign().toString())).materialId());
        items.put("otherMaterial", ingested(gm, metadata("Mapas.pdf", "campaign", gm.campaign().toString()))
                .materialId());
        items.put("conversation", json(ask(gm, "{\"question\":\"Quem leva a coroa?\"}")).path("conversationId")
                .asString());
        // Story 4.4: a pending record type proposal of B's system, and B's spell type, for the merge's control.
        items.put("proposal", proposal(gm, UUID.fromString(items.get("system")), "record_type").path("id").asString());
        items.put("recordType", json(get(gm, "/api/v1/systems/" + items.get("system") + "/record-types"))
                .path("items").get(1).path("id").asString());
        World world = new World(gm, items, Set.of());
        refresh(world);
        return world;
    }

    /** The first pending proposal of this kind in the system, as its proposals list names it (Story 4.4). */
    private JsonNode proposal(Gm gm, Object system, String kind) throws Exception {
        for (JsonNode item : json(get(gm, "/api/v1/systems/" + system + "/proposals?limit=50")).path("items")) {
            if (kind.equals(item.path("kind").asString())) {
                return item;
            }
        }
        throw new AssertionError("no pending " + kind + " proposal in " + system);
    }

    /** B's versions read again: its material's and its campaign's scope settings'. */
    private void refresh(World b) throws Exception {
        b.items().put("materialVersion", version(b.gm(), "/api/v1/materials/" + b.get("material")));
        b.items().put("scopeVersion", version(b.gm(), "/api/v1/campaigns/" + b.gm().campaign() + "/scope"));
        b.items().put("proposalVersion", proposal(b.gm(), b.get("system"), "record_type").path("version").asString());
    }

    // --- checks

    /** A's reads of each item: every audited GET and HEAD route, and A's lists. */
    private Map<String, String> reads(World world) throws Exception {
        Map<String, String> read = new LinkedHashMap<>();
        List<String> paths = new ArrayList<>(List.of("/api/v1/systems", "/api/v1/campaigns",
                "/api/v1/campaigns?archived=true", "/api/v1/materials", "/api/v1/saved-answers",
                "/api/v1/campaigns/current", "/api/v1/conversations/current",
                "/api/v1/campaigns/" + world.gm().campaign() + "/scope",
                "/api/v1/campaigns/" + world.gm().campaign() + "/conversations?area=questions",
                "/api/v1/campaigns/" + world.gm().campaign() + "/conversations?area=rules",
                "/api/v1/materials/" + world.get("material"), "/api/v1/systems/" + world.get("system")));
        if (world.get("rulesConversation") != null) {
            paths.add("/api/v1/conversations/" + world.get("rulesConversation") + "/turns");
            paths.add("/api/v1/materials/" + world.get("imageSetMaterial"));
        }
        for (String path : paths) {
            read.put("GET " + path, send(world.gm(), new Request("GET", path, null, List.of())).toString());
        }
        if (world.get("turn") != null) {
            for (Route route : ROUTES) {
                if (route.method().equals("GET") || route.method().equals("HEAD")) {
                    Request request = request(route, world.items(), world.items());
                    read.put(route.method() + " " + request.path(), send(world.gm(), request).toString());
                }
            }
        }
        return read;
    }

    private void assertUnchanged(World world, Map<String, String> before) throws Exception {
        Map<String, String> after = reads(world);
        SoftAssertions softly = new SoftAssertions();
        before.forEach((read, answer) -> softly.assertThat(after.get(read)).as("A's %s after B's requests", read)
                .isEqualTo(answer));
        softly.assertAll();
    }

    /** B's own lists and current items hold none of A's ids. */
    private void assertNoneOfTheirs(World b, World a) throws Exception {
        SoftAssertions softly = new SoftAssertions();
        for (String path : List.of("/api/v1/systems", "/api/v1/campaigns", "/api/v1/campaigns?archived=true",
                "/api/v1/materials", "/api/v1/saved-answers", "/api/v1/campaigns/current",
                "/api/v1/conversations/current", "/api/v1/campaigns/" + b.gm().campaign() + "/scope",
                "/api/v1/campaigns/" + b.gm().campaign() + "/conversations?area=questions",
                "/api/v1/campaigns/" + b.gm().campaign() + "/conversations?area=rules",
                "/api/v1/campaigns/" + b.gm().campaign() + "/artifacts",
                "/api/v1/systems/" + b.get("system") + "/record-types",
                "/api/v1/systems/" + b.get("system") + "/proposals", "/api/v1/systems/" + b.get("system") + "/terms",
                "/api/v1/materials/" + b.get("material") + "/proposals")) {
            Answer list = send(b.gm(), new Request("GET", path, null, List.of()));
            softly.assertThat(list.status()).as("B's %s: %s", path, list.body()).isEqualTo(200);
            softly.assertThat(leaks(list, a, Set.of())).as("B's %s names A's items", path).isEmpty();
        }
        softly.assertAll();
    }

    /** A's ids that the answer names, but {@code allowed}. */
    private static List<String> leaks(Answer answer, World a, Set<String> allowed) {
        return a.ids().stream().filter(id -> !allowed.contains(id))
                .filter(id -> answer.body().toLowerCase(java.util.Locale.ROOT).contains(id)).toList();
    }

    /**
     * The answer as it can be compared with another: status, content type and body, without the trace id, with
     * {@code probed} written {@code <probed>} and every other id {@code <id>}.
     */
    private static String normal(Answer answer, Set<String> probed) {
        String body = answer.body();
        if (!body.isBlank() && answer.contentType().contains("json")) {
            JsonNode tree = JSON.readTree(body);
            if (tree instanceof ObjectNode object) {
                object.remove("traceId");
            }
            body = tree.toString();
        }
        for (String id : probed) {
            body = body.replace(id, "<probed>");
        }
        body = UUID_TEXT.matcher(body).replaceAll("<id>");
        return answer.status() + " " + answer.contentType().replace(";charset=UTF-8", "") + " " + body + " "
                + answer.tus();
    }

    private static String code(Answer answer) {
        if (!answer.contentType().contains("json") || answer.body().isBlank()) {
            return null;
        }
        return JSON.readTree(answer.body()).path("code").asString();
    }

    /** The audit's two accounts: the rows of the stores that it compares are theirs. */
    private static List<UUID> owners(World a, World b) {
        return List.of(a.gm().id(), b.gm().id());
    }

    /** The tables compared hold A's items: they are the application's own stores. */
    private void assertHeld(Map<String, Map<Object, Object>> tables, World a) {
        stores.held().forEach((table, items) -> items.forEach(item -> assertThat(tables.get(table))
                .as("the %s table holds A's %s", table, item).containsKey(UUID.fromString(a.get(item)))));
    }

    /** Each row added, removed or changed since {@code before}, as {@code <table> <key> <what>}. */
    static List<String> differences(Map<String, Map<Object, Object>> before,
                                            Map<String, Map<Object, Object>> after) {
        List<String> found = new ArrayList<>();
        before.forEach((table, rows) -> {
            Map<Object, Object> now = after.get(table);
            rows.forEach((key, row) -> {
                if (!now.containsKey(key)) {
                    found.add(table + " " + key + " removed");
                } else if (!now.get(key).equals(row)) {
                    found.add(table + " " + key + " changed");
                }
            });
            now.keySet().stream().filter(key -> !rows.containsKey(key))
                    .forEach(key -> found.add(table + " " + key + " added"));
        });
        return found;
    }

    // --- requests

    /**
     * The route's request with the path's items taken from {@code items} and the body's placeholders from
     * {@code values}.
     */
    private static Request request(Route route, Map<String, String> items, Map<String, String> values) {
        String path = route.template();
        for (int i = 0; i < route.params().size(); i += 2) {
            String held = route.params().get(i + 1);
            path = path.replace("{" + route.params().get(i) + "}", DIGITS.matcher(held).matches() ? held
                    : items.get(held));
        }
        if (route.query() != null) {
            path += "?" + route.query();
        }
        List<String> headers = new ArrayList<>(route.headers());
        HttpRequest.BodyPublisher body = null;
        if (route.part()) {
            body = HttpRequest.BodyPublishers.ofByteArray(PART);
            headers.addAll(List.of("Content-Type", "application/offset+octet-stream", "Upload-Offset", "0",
                    "Upload-Checksum", "sha256 " + Base64.getEncoder().encodeToString(sha256(PART))));
        } else if (route.json() != null) {
            String json = route.json();
            for (Map.Entry<String, String> value : values.entrySet()) {
                json = json.replace("{" + value.getKey() + "}", value.getValue());
            }
            body = HttpRequest.BodyPublishers.ofString(json);
            headers.addAll(List.of("Content-Type", "application/json"));
        }
        return new Request(route.method(), path, body, List.copyOf(headers));
    }

    /** The items of the world, with each item the route's path names replaced by a random id. */
    private static Map<String, String> randomFor(Route route, Map<String, String> items) {
        Map<String, String> random = new HashMap<>(items);
        route.items().forEach(item -> random.put(item, UUID.randomUUID().toString()));
        return random;
    }

    private static Request json(String method, String path, String body) {
        return new Request(method, path, HttpRequest.BodyPublishers.ofString(body), List.of("Content-Type",
                "application/json"));
    }

    private static Request uploadCreation(String metadata) {
        return new Request("POST", "/api/v1/uploads", null, List.of("Tus-Resumable", TUS, "Upload-Length", "1000",
                "Upload-Metadata", metadata));
    }

    private static Request imageSet(String fields) {
        return json("POST", "/api/v1/image-sets", "{\"name\":\"Ficha\"," + fields + ",\"images\":[{\"name\":\"a.jpg\","
                + "\"length\":9000}]}");
    }

    /** tus metadata: the file's name, then pairs of a key and its value. */
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

    private Answer send(Gm gm, Request request) throws Exception {
        return send(gm, request, request.method() + " " + request.path());
    }

    /** The request sent as {@code gm}; {@code name} names it if it gets no whole answer in time. */
    private Answer send(Gm gm, Request request, String name) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(request.path()))
                .header("Authorization", "Bearer " + CREDENTIAL).header("X-Session-Token", gm.session())
                .method(request.method(), request.body() == null ? HttpRequest.BodyPublishers.noBody()
                        : request.body());
        if (!request.headers().isEmpty()) {
            builder.headers(request.headers().toArray(String[]::new));
        }
        HttpResponse<String> response = exchange(builder, name);
        return new Answer(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                response.body(), response.headers().firstValue("Upload-Offset").map(offset -> offset + " of "
                        + response.headers().firstValue("Upload-Length").orElse("")).orElse(""));
    }

    /**
     * The request's whole answer, or a failure naming {@code name} after {@value #TIMEOUT_SECONDS} s (a stream that
     * never ends fails the test instead of hanging the build).
     */
    private HttpResponse<String> exchange(HttpRequest.Builder request, String name) throws Exception {
        CompletableFuture<HttpResponse<String>> answer = client.sendAsync(
                request.timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build(), HttpResponse.BodyHandlers.ofString());
        try {
            return answer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException late) {
            answer.cancel(true);
            throw new AssertionError(name + ": no whole answer within " + TIMEOUT_SECONDS + " s", late);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof HttpTimeoutException late) {
                throw new AssertionError(name + ": no answer within " + TIMEOUT_SECONDS + " s", late);
            }
            throw failed;
        }
    }

    private static JsonNode json(Answer answer) {
        return JSON.readTree(answer.body());
    }

    // --- A's items through the API and the worker's routes

    private String version(Gm gm, String path) throws Exception {
        HttpResponse<String> read = get(gm, path);
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        return Long.toString(json(read).path("version").asLong());
    }

    private HttpResponse<String> ask(Gm gm, String body) throws Exception {
        HttpResponse<String> asked = send(gm, "POST", "/api/v1/campaigns/" + gm.campaign() + "/turns", body);
        assertThat(asked.statusCode()).as(asked.body()).isEqualTo(201);
        return asked;
    }

    /**
     * The asked turn answered: claimed, one batch with a citation of {@code passage} and (Story 4.3 part C) one of
     * {@code record} when given, done.
     */
    private UUID answered(HttpResponse<String> asked, UUID passage, String record) throws Exception {
        return answered(asked, passage, record, null);
    }

    /** {@link #answered(HttpResponse, UUID, String)}, citing the campaign's {@code artifact} too when given (Story 5.1). */
    private UUID answered(HttpResponse<String> asked, UUID passage, String record, String artifact) throws Exception {
        UUID turnId = UUID.fromString(json(asked).path("id").asString());
        UUID jobId = stores.turnJob(turnId);
        HttpResponse<String> claimed = worker("POST", "/internal/v1/jobs/" + jobId + "/claims",
                "{\"providers\":[\"azure-openai\"]}".getBytes(StandardCharsets.UTF_8), "Content-Type",
                "application/json");
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(201);
        JsonNode claim = json(claimed);
        HttpResponse<String> events = claimed(jobId, claim, "events", "{\"batch\":1,\"events\":[{\"type\":\"delta\","
                + "\"text\":\"Fire Bolt causa 1d10 de dano de fogo.\"},{\"type\":\"citation\",\"passageId\":\""
                + passage + "\",\"offset\":0}" + (record == null ? "" : ",{\"type\":\"citation\",\"recordId\":\""
                + record + "\",\"offset\":0}") + (artifact == null ? "" : ",{\"type\":\"citation\",\"recordId\":\""
                + artifact + "\",\"offset\":0}") + "],\"usage\":[]}");
        assertThat(events.statusCode()).as(events.body()).isEqualTo(200);
        HttpResponse<String> done = claimed(jobId, claim, "outcome", "{\"outcome\":\"done\"}");
        assertThat(done.statusCode()).as(done.body()).isEqualTo(204);
        return turnId;
    }

    /**
     * A material uploaded with {@code metadata}, then cleaned (one page, one batch), triaged as text, read directly
     * with a picture and a line, cut into one passage, its records read (Story 4.2: one spell record, from that
     * passage), and accepted; its index changes are then ended so the next ingestion runs.
     */
    private Uploaded ingested(Gm gm, String metadata) throws Exception {
        Uploaded uploaded = upload(gm, pdf(MIB), metadata);
        Step clean = offeredStep(uploaded.versionId());
        JsonNode claim = claim(clean.jobId());
        UUID original = output(clean.jobId(), claim, pdf(4000));
        UUID batch = output(clean.jobId(), claim, pdf(3000));
        report(clean, claim, "{\"pipelineVersion\":\"2\",\"pageTotal\":1,\"original\":{\"fileId\":\"" + original
                + "\"},\"batches\":[{\"fileId\":\"" + batch + "\",\"firstPage\":1,\"lastPage\":1}],\"next\":[{\"stage\""
                + ":\"triage\",\"firstPage\":1,\"lastPage\":1,\"input\":\"" + batch + "\"},{\"stage\":\"passages\","
                + "\"firstPage\":1,\"lastPage\":1,\"input\":\"texts\"}]}");

        Step triage = offeredStep(uploaded.versionId());
        report(triage, claim(triage.jobId()), "{\"pipelineVersion\":\"2\",\"layers\":[{\"firstPage\":1,\"lastPage\""
                + ":1,\"layer\":\"text\"}],\"next\":[{\"stage\":\"layout\",\"firstPage\":1,\"lastPage\":1,\"input\":\""
                + triage.inputFileId() + "\"}]}");

        Step layout = offeredStep(uploaded.versionId());
        JsonNode layoutClaim = claim(layout.jobId());
        UUID text = output(layout.jobId(), layoutClaim, "{\"pages\":[]}".getBytes(StandardCharsets.UTF_8));
        UUID picture = output(layout.jobId(), layoutClaim, webp(1000));
        report(layout, layoutClaim, "{\"pipelineVersion\":\"2\",\"pages\":[{\"firstPage\":1,\"lastPage\":1,"
                + "\"outcome\":\"read_directly\"}],\"texts\":[{\"fileId\":\"" + text + "\",\"firstPage\":1,"
                + "\"lastPage\":1}],\"pageViews\":[{\"page\":1,\"picture\":{\"fileId\":\"" + picture + "\",\"width\""
                + ":1545,\"height\":2000},\"lines\":[{\"text\":\"Fire Bolt\",\"box\":[0.1,0.2,0.9,0.25]}]}],"
                + "\"next\":[]}");

        Step passages = offeredStep(uploaded.versionId());
        assertThat(passages.stage()).isEqualTo("passages");
        report(passages, claim(passages.jobId()), "{\"pipelineVersion\":\"2\",\"passages\":[{\"page\":1,"
                + "\"sectionPath\":[\"Magias\",\"Fire Bolt\"],\"text\":\"Fire Bolt deals 1d10 Fire damage\","
                + "\"boxes\":[[0.1,0.2,0.9,0.3]]}],\"outline\":[{\"level\":1,\"title\":\"Magias\",\"page\":1}],"
                + "\"next\":[{\"stage\":\"records\",\"firstPage\":1,\"lastPage\":1,\"input\":\"passages\"}]}");

        Step records = offeredStep(uploaded.versionId());
        assertThat(records.stage()).isEqualTo("records");
        HttpResponse<String> claimed = worker("POST", "/internal/v1/jobs/" + records.jobId() + "/claims",
                "{\"providers\":[\"azure-openai\"]}".getBytes(StandardCharsets.UTF_8), "Content-Type",
                "application/json");
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(201);
        UUID passage = stores.passage(uploaded.versionId());
        // Story 4.4: and two proposals quoted from that passage, a record type and a term.
        report(records, json(claimed), "{\"pipelineVersion\":\"2\",\"records\":[{\"type\":\"spell\",\"name\":"
                + "\"Fire Bolt\",\"fields\":[{\"name\":\"Damage\",\"value\":\"1d10 Fire\"}],\"passageIds\":[\""
                + passage + "\"]}],\"proposals\":[{\"kind\":\"record_type\",\"name\":\"Bolt\",\"definition\":\"\","
                + "\"examples\":[{\"passageId\":\"" + passage + "\",\"quote\":\"Fire Bolt deals\"}]},{\"kind\":\"term\","
                + "\"name\":\"Fire damage\",\"definition\":\"deals 1d10 Fire damage\",\"examples\":[{\"passageId\":\""
                + passage + "\",\"quote\":\"1d10 Fire damage\"}]}],\"next\":[]}");
        HttpResponse<String> accepted = send(gm, "POST", "/api/v1/materials/" + uploaded.materialId() + "/accept", "");
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(json(accepted).path("state").asString()).isEqualTo("ready");
        // Its index changes would hold the ingestion lane.
        stores.endIngestion();
        return uploaded;
    }

    /** A tus upload of {@code content}: created with {@code metadata}, sent in one part and completed. */
    private Uploaded upload(Gm gm, byte[] content, String metadata) throws Exception {
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
        return new Uploaded(json(completed).path("materialId").asString(),
                UUID.fromString(json(completed).path("versionId").asString()));
    }

    private HttpResponse<String> tus(Gm gm, String method, String path, byte[] body, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Session-Token", gm.session()).header("Tus-Resumable", TUS)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        request.headers(headers);
        return exchange(request, method + " " + path);
    }

    /** The version's one step whose job is offered. */
    private Step offeredStep(UUID versionId) {
        List<Step> offered = stores.offeredSteps(versionId);
        assertThat(offered).as("the offered step of %s", versionId).hasSize(1);
        return offered.getFirst();
    }

    private JsonNode claim(UUID jobId) throws Exception {
        HttpResponse<String> claimed = worker("POST", "/internal/v1/jobs/" + jobId + "/claims", null);
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(201);
        return json(claimed);
    }

    private UUID output(UUID jobId, JsonNode claim, byte[] content) throws Exception {
        HttpResponse<String> stored = worker("POST", "/internal/v1/jobs/" + jobId + "/claims/"
                + claim.path("claimId").asString() + "/outputs", content, "Content-Type", "application/octet-stream",
                "X-Job-Grant", claim.path("grant").asString());
        assertThat(stored.statusCode()).as(stored.body()).isEqualTo(201);
        return UUID.fromString(json(stored).path("fileId").asString());
    }

    private void report(Step step, JsonNode claim, String result) throws Exception {
        HttpResponse<String> reported = claimed(step.jobId(), claim, "outcome",
                "{\"outcome\":\"done\",\"result\":" + result + "}");
        assertThat(reported.statusCode()).as(reported.body()).isEqualTo(204);
    }

    /** A JSON request on the claim's own path ({@code events}, {@code outcome}). */
    private HttpResponse<String> claimed(UUID jobId, JsonNode claim, String action, String body) throws Exception {
        return worker("POST", "/internal/v1/jobs/" + jobId + "/claims/" + claim.path("claimId").asString() + "/"
                + action, body.getBytes(StandardCharsets.UTF_8), "Content-Type", "application/json",
                "X-Job-Grant", claim.path("grant").asString());
    }

    private HttpResponse<String> worker(String method, String path, byte[] body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + WORKER_CREDENTIAL)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return exchange(request, method + " " + path);
    }

    private HttpResponse<String> get(Gm gm, String path) throws Exception {
        return exchange(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Session-Token", gm.session()).GET(), "GET " + path);
    }

    private HttpResponse<String> send(Gm gm, String method, String path, String json) throws Exception {
        return exchange(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + CREDENTIAL)
                .header("X-Session-Token", gm.session()).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json)), method + " " + path);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    /** A new account through the Google stand-in, with its session and its first campaign. */
    Gm signUp() throws Exception {
        String sub = "audit-" + UUID.randomUUID();
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
        String session = json(response).path("sessionToken").asString();
        HttpResponse<String> current = exchange(HttpRequest.newBuilder(uri("/api/v1/campaigns/current"))
                .header("Authorization", "Bearer " + CREDENTIAL).header("X-Session-Token", session).GET(),
                "GET /api/v1/campaigns/current");
        UUID campaign = UUID.fromString(json(current).path("id").asString());
        return new Gm(stores.account(sub), session, campaign);
    }

    private HttpResponse<String> post(String path, Map<String, Object> body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + CREDENTIAL);
        if (body == null) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        }
        return exchange(request, "POST " + path);
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
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

    private static byte[] jpeg(int size) {
        byte[] content = new byte[size];
        content[0] = (byte) 0xff;
        content[1] = (byte) 0xd8;
        content[2] = (byte) 0xff;
        content[size - 1] = 7;
        return content;
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (java.security.NoSuchAlgorithmException absent) {
            throw new IllegalStateException(absent);
        }
    }
}
