package io.github.gmshield;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.accounts.web.LibraryRoutes;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Story 3.13 part C: keeps the cross-owner audit complete. Every operation of the public OpenAPI document whose path
 * has a {@code {…}} parameter is a row of {@link CrossOwnerAuditFlowTest#ROUTES} or is in {@link #EXEMPT} with its
 * reason, and every row is still in the document; every path but those of {@link #NOT_OWNER_SCOPED} is under
 * {@code LibraryOwnerFilter.LIBRARY_ROUTES} and is refused without a session before any controller runs, and each
 * path of that list is in the document and outside the filter. A new route
 * in neither list fails with its method and path, which the fixtures here prove.
 */
class CrossOwnerRouteGuardTest extends HttpTestSupport {

    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options",
            "trace");

    /**
     * Operations with a path parameter that the audit does not walk, each with its reason. None today: the uploads are
     * audited too, through an image of an open set.
     */
    static final Map<String, String> EXEMPT = Map.of();

    /** Public paths that no library owner holds, each with its reason: the session filter does not cover them. */
    static final Map<String, String> NOT_OWNER_SCOPED = Map.of(
            "/api/v1/sign-in/google", "a sign-in starts before any session",
            "/api/v1/sign-in/google/callback", "a sign-in goes on before any session",
            "/api/v1/sign-in/complete", "a sign-in ends by making the session",
            "/api/v1/session", "the session itself, checked by its own controller",
            "/api/v1/session/renewal", "the session itself, checked by its own controller",
            "/api/v1/me", "the session's own account, checked by its own controller",
            "/api/v1/version", "the build's version, for anyone");

    @Test
    void everyOperationThatNamesAnIdIsAuditedOrExempt() throws Exception {
        assertThat(unaudited(publicDocument())).isEmpty();
    }

    @Test
    void everyOwnerScopedPathIsBehindTheSessionFilter() throws Exception {
        assertThat(outsideTheFilter(publicDocument(), LibraryRoutes.prefixes())).isEmpty();
    }

    /** The filter really answers each owner-scoped operation without a session, before its controller. */
    @Test
    void everyOwnerScopedOperationIsRefusedWithoutASession() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        List<String> open = new ArrayList<>();
        for (String operation : operations(publicDocument())) {
            String path = operation.substring(operation.indexOf(' ') + 1);
            if (NOT_OWNER_SCOPED.containsKey(path)) {
                continue;
            }
            String method = operation.substring(0, operation.indexOf(' '));
            String concrete = path.replaceAll("\\{[^}/]+}", UUID.randomUUID().toString());
            HttpResponse<String> answer = client.send(HttpRequest.newBuilder(uri(concrete))
                    .header("Authorization", "Bearer " + CREDENTIAL).header("Tus-Resumable", "1.0.0")
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            boolean refused = answer.statusCode() == 401
                    && (method.equals("HEAD") || json(answer).path("code").asString().equals("session.required"));
            if (!refused) {
                open.add(operation + ": answered " + answer.statusCode() + " " + answer.body() + " without a session");
            }
        }
        assertThat(open).isEmpty();
    }

    // --- the guard on a document changed by hand

    @Test
    void aNewRouteWithAnIdThatIsNotAuditedIsNamed() throws Exception {
        JsonNode document = publicDocument();
        ObjectNode paths = (ObjectNode) document.path("paths");
        paths.set("/api/v1/x/{id}", JSON.readTree("{\"get\":{\"operationId\":\"x\"}}"));
        paths.set("/api/v1/systems/{id}/copies", JSON.readTree("{\"post\":{\"operationId\":\"copy\"}}"));

        assertThat(unaudited(document)).containsExactly("GET /api/v1/x/{id}: not audited and not exempt",
                "POST /api/v1/systems/{id}/copies: not audited and not exempt");
        assertThat(outsideTheFilter(document, LibraryRoutes.prefixes()))
                .containsExactly("/api/v1/x/{id}: owner-scoped but outside LibraryOwnerFilter.LIBRARY_ROUTES");
    }

    @Test
    void aRowThatLeftTheDocumentIsNamed() throws Exception {
        JsonNode document = publicDocument();
        ((ObjectNode) document.path("paths")).remove("/api/v1/searches/{searchId}");

        assertThat(unaudited(document))
                .containsExactly("GET /api/v1/searches/{searchId}: audited but not in the public API");
    }

    @Test
    void aPathOutsideTheFilterIsNamed() throws Exception {
        List<String> narrower = LibraryRoutes.prefixes().stream().filter(route -> !route.endsWith("/searches"))
                .toList();

        assertThat(outsideTheFilter(publicDocument(), narrower)).containsExactly(
                "/api/v1/searches/{searchId}: owner-scoped but outside LibraryOwnerFilter.LIBRARY_ROUTES");
    }

    @Test
    void aPathListedAsNotOwnerScopedThatTheFilterCoversIsNamed() throws Exception {
        List<String> wider = new ArrayList<>(LibraryRoutes.prefixes());
        wider.add("/api/v1/me");

        assertThat(outsideTheFilter(publicDocument(), wider))
                .containsExactly("/api/v1/me: not owner-scoped but under LibraryOwnerFilter.LIBRARY_ROUTES");
    }

    @Test
    void aPathListedAsNotOwnerScopedThatLeftTheDocumentIsNamed() throws Exception {
        JsonNode document = publicDocument();
        ((ObjectNode) document.path("paths")).remove("/api/v1/version");

        assertThat(outsideTheFilter(document, LibraryRoutes.prefixes()))
                .containsExactly("/api/v1/version: not owner-scoped but not in the public API");
    }

    // --- the checks

    /** Each operation with a path parameter that is neither audited nor exempt, and each row or exemption gone. */
    static List<String> unaudited(JsonNode document) {
        Set<String> operations = operations(document);
        Set<String> audited = new TreeSet<>();
        CrossOwnerAuditFlowTest.ROUTES.forEach(route -> audited.add(route.key()));
        List<String> found = new ArrayList<>();
        for (String operation : operations) {
            if (operation.contains("{") && !audited.contains(operation) && !EXEMPT.containsKey(operation)) {
                found.add(operation + ": not audited and not exempt");
            }
        }
        for (String row : audited) {
            if (!operations.contains(row)) {
                found.add(row + ": audited but not in the public API");
            }
        }
        for (String exempt : new TreeSet<>(EXEMPT.keySet())) {
            if (!operations.contains(exempt)) {
                found.add(exempt + ": exempt but not in the public API");
            }
        }
        return found;
    }

    /** Each path of the document, but those that no owner holds, that no prefix of {@code routes} covers. */
    static List<String> outsideTheFilter(JsonNode document, List<String> routes) {
        List<String> found = new ArrayList<>();
        Set<String> paths = new TreeSet<>(document.path("paths").propertyNames());
        for (String path : paths) {
            if (!covered(path, routes) && !NOT_OWNER_SCOPED.containsKey(path)) {
                found.add(path + ": owner-scoped but outside LibraryOwnerFilter.LIBRARY_ROUTES");
            }
        }
        for (String path : new TreeSet<>(NOT_OWNER_SCOPED.keySet())) {
            if (!paths.contains(path)) {
                found.add(path + ": not owner-scoped but not in the public API");
            }
            if (covered(path, routes)) {
                found.add(path + ": not owner-scoped but under LibraryOwnerFilter.LIBRARY_ROUTES");
            }
        }
        return found;
    }

    private static boolean covered(String path, List<String> routes) {
        return routes.stream().anyMatch(route -> path.equals(route) || path.startsWith(route + "/"));
    }

    /** Every operation of the document as {@code METHOD path}, sorted. */
    static Set<String> operations(JsonNode document) {
        Set<String> operations = new TreeSet<>();
        JsonNode paths = document.path("paths");
        for (String path : paths.propertyNames()) {
            for (String method : paths.path(path).propertyNames()) {
                if (METHODS.contains(method)) {
                    operations.add(method.toUpperCase(java.util.Locale.ROOT) + " " + path);
                }
            }
        }
        return operations;
    }

    private JsonNode publicDocument() throws Exception {
        HttpResponse<String> response = get("/v3/api-docs/public");
        assertThat(response.statusCode()).isEqualTo(200);
        return json(response);
    }
}
