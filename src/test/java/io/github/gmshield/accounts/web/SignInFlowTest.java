package io.github.gmshield.accounts.web;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.github.gmshield.shared.service.JsonLineLogFormatter;
import io.github.gmshield.vault.data.DataKeyStore;
import io.github.gmshield.vault.data.InMemoryDataKeyStore;
import io.github.gmshield.vault.service.Vault;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sign-in with the Google stand-in, as the frontend calls it in test mode: every row of Story 1.9's I/O
 * matrix, the seal check, a username race, and what the stores and the log never hold.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GMSHIELD_MODE=test")
@ActiveProfiles({"test-mode", "unit"})
class SignInFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A clock the tests move forward. */
    static final class MovableClock extends Clock {
        private volatile Instant now = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    @TestConfiguration
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
            return new InMemorySessionStore();
        }

        @Bean
        MovableClock clock() {
            return new MovableClock();
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private InMemoryAccountStore accounts;

    @Autowired
    private InMemorySignInAttemptStore attempts;

    @Autowired
    private InMemorySessionStore sessions;

    @Autowired
    private MovableClock clock;

    @Autowired
    private Vault vault;

    private final HttpClient client = HttpClient.newHttpClient();
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final List<String> secrets = new ArrayList<>();

    @BeforeEach
    void captureLog() {
        // Accounts stay between tests (each uses its own identities); attempts and sessions start empty.
        attempts.values.clear();
        sessions.values.clear();
        sessions.accountSets.clear();
        logged.start();
        root.addAppender(logged);
    }

    @AfterEach
    void nothingSecretWasLogged() {
        root.detachAppender(logged);
        logged.stop();
        JsonLineLogFormatter formatter = new JsonLineLogFormatter();
        String lines = String.join("", logged.list.stream().map(formatter::format).toList());
        for (String secret : secrets) {
            assertThat(lines).as("the log").doesNotContain(secret);
        }
    }

    /** A started attempt: its token and the state of its authorization URL. */
    record Attempt(String token, String state) {
    }

    @Test
    void startIssuesAnAttemptAndRedirectsToTheCallbackWithStateAndCode() throws Exception {
        HttpResponse<String> response = post(SignInController.START, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode body = JSON.readTree(response.body());
        String token = body.path("attemptToken").asString();
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(body.path("expiresInSeconds").asLong()).isEqualTo(600);
        URI url = URI.create(body.path("authorizationUrl").asString());
        assertThat(url.getPath()).isEqualTo("/auth/google/callback");
        Map<String, String> query = query(url.getRawQuery());
        assertThat(query.get("code")).isEqualTo("stand-in.1.gm@example.test");
        assertThat(query.get("state")).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(token);

        // Redis holds the attempt under the token's keyed hash, for 600 s, and nothing that completes it.
        assertThat(attempts.values).hasSize(1);
        String key = attempts.values.keySet().iterator().next();
        assertThat(key).matches("gmshield:sign-in:[0-9a-f]{64}").doesNotContain(token);
        assertThat(attempts.values.get(key)).doesNotContain(token).doesNotContain(query.get("state"));
        assertThat(attempts.timesToLive.get(key)).isEqualTo(Duration.ofSeconds(600));
        secrets.add(token);
    }

    @Test
    void aFirstSignInChoosesAUsernameThenAReturningOneReachesTheSameAccount() throws Exception {
        String sub = "first-" + UUID.randomUUID();
        Attempt attempt = start();

        JsonNode outcome = callback(attempt, code(sub, "nova@example.test"), 200);
        assertThat(outcome.path("outcome").asString()).isEqualTo("username_required");
        assertThat(outcome.has("session")).isFalse();
        assertThat(accounts.findByGoogleIdFingerprint(vault.fingerprint("account", "google_id", sub))).isEmpty();
        // The attempt now carries the identity, encrypted.
        assertThat(attempts.values.values()).singleElement().asString().contains("IDENTIFIED")
                .doesNotContain(sub).doesNotContain("nova@example.test");

        String username = "Nova_" + sub.substring(6, 12);
        JsonNode grant = complete(attempt.token(), username, true, 200);
        String session = grant.path("sessionToken").asString();
        assertThat(grant.path("expiresInSeconds").asLong()).isEqualTo(604800);
        assertThat(attempts.values).isEmpty();
        assertThat(me(session).path("username").asString()).isEqualTo(username);
        secrets.addAll(List.of(sub, "nova@example.test", username, session));

        AccountRow row = onlyAccountOf(sub);
        assertThat(row.tier()).isEqualTo("ordinary");
        assertThat(row.state()).isEqualTo("active");
        assertThat(row.confirmed()).isTrue();
        assertThat(row.sealVersion()).isEqualTo(1);
        assertThat(row.termsVersion()).isEqualTo("2026-11-16-draft");
        for (byte[] stored : new byte[][] {row.googleId(), row.email(), row.username()}) {
            assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(sub)
                    .doesNotContain("nova@example.test").doesNotContain(username);
        }
        // The session in Redis: the token's hash as the key, the account id and the seal version as the value.
        String sessionKey = sessions.values.keySet().stream().filter(k -> sessions.values.get(k).contains(row.id().toString()))
                .findFirst().orElseThrow();
        assertThat(sessionKey).matches("gmshield:session:[0-9a-f]{64}");
        assertThat(sessions.values.get(sessionKey)).doesNotContain(session).contains("\"sealVersion\":1");
        assertThat(sessions.timesToLive.get(sessionKey)).isEqualTo(Duration.ofDays(7));
        assertThat(sessions.accountSets.get("gmshield:session:account:" + row.id())).contains(sessionKey.substring(17));

        // Returning: the same Google id reaches the same account at once.
        JsonNode again = callback(start(), code(sub, "nova@example.test"), 200);
        assertThat(again.path("outcome").asString()).isEqualTo("signed_in");
        String second = again.path("session").path("sessionToken").asString();
        assertThat(second).isNotEqualTo(session);
        assertThat(me(second).path("username").asString()).isEqualTo(username);
        assertThat(accounts.rows.values().stream().filter(r -> r.id().equals(row.id()))).hasSize(1);
        secrets.add(second);
    }

    @Test
    void aReturningVisitorWhoseGoogleEmailChangedGetsItUpdatedAndResealed() throws Exception {
        String sub = "moved-" + UUID.randomUUID();
        signUp(sub, "old@example.test", "moved_" + sub.substring(6, 12));
        AccountRow before = onlyAccountOf(sub);

        JsonNode again = callback(start(), code(sub, "new@example.test"), 200);

        assertThat(again.path("outcome").asString()).isEqualTo("signed_in");
        AccountRow after = onlyAccountOf(sub);
        assertThat(after.sealVersion()).isEqualTo(2);
        assertThat(after.emailFingerprint()).isNotEqualTo(before.emailFingerprint());
        assertThat(after.seal()).isNotEqualTo(before.seal());
        assertThat(me(again.path("session").path("sessionToken").asString()).has("username")).isTrue();
    }

    @Test
    void aTakenUsernameWhateverItsCaseIsRefusedAndTheAttemptStays() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        signUp("taken-a-" + suffix, "a@example.test", "mestre." + suffix);
        Attempt attempt = start();
        callback(attempt, code("taken-b-" + suffix, "b@example.test"), 200);

        JsonNode problem = complete(attempt.token(), "MESTRE." + suffix.toUpperCase(), true, 409);

        assertThat(problem.path("code").asString()).isEqualTo("request.username_taken");
        assertThat(problem.path("params").isEmpty()).isTrue();
        assertThat(attempts.values).hasSize(1);
        // Another username, within the same attempt, works.
        String session = complete(attempt.token(), "outro-" + suffix, true, 200).path("sessionToken").asString();
        assertThat(me(session).path("username").asString()).isEqualTo("outro-" + suffix);
    }

    @Test
    void anInvalidUsernameOrUntickedTermsIsRefusedInline() throws Exception {
        Attempt attempt = start();
        callback(attempt, code("rules-" + UUID.randomUUID(), "rules@example.test"), 200);

        for (String bad : new String[] {"ab", "a".repeat(25), "with space", "ação", "emoji😀", "", "semi;colon"}) {
            JsonNode problem = complete(attempt.token(), bad, true, 400);
            assertThat(problem.path("code").asString()).as(bad).isEqualTo("request.username_invalid");
            assertThat(problem.path("params").path("min").asInt()).isEqualTo(3);
            assertThat(problem.path("params").path("max").asInt()).isEqualTo(24);
        }
        assertThat(complete(attempt.token(), "valid_name", false, 400).path("code").asString())
                .isEqualTo("request.terms_required");
        assertThat(post(SignInController.COMPLETE, Map.<String, Object>of("attemptToken", attempt.token(), "username", "valid_name"))
                .statusCode()).isEqualTo(400);
        assertThat(attempts.values).hasSize(1);
        assertThat(complete(attempt.token(), "Ab3", true, 200).path("sessionToken").asString()).isNotBlank();
    }

    @Test
    void aCallbackWithoutAMatchingLiveAttemptIsExpiredAndCreatesNothing() throws Exception {
        int accountsBefore = accounts.rows.size();
        int sessionsBefore = sessions.values.size();
        String code = code("nobody-" + UUID.randomUUID(), "nobody@example.test");

        // No cookie, a malformed token, an unknown token.
        assertExpired(callbackRaw(null, "state", code));
        assertExpired(callbackRaw("short", "state", code));
        assertExpired(callbackRaw("A".repeat(43), "state", code));
        // A wrong state: the attempt is used up as well.
        Attempt wrongState = start();
        assertExpired(callbackRaw(wrongState.token(), wrongState.state() + "x", code));
        assertExpired(callbackRaw(wrongState.token(), wrongState.state(), code));
        // Expired after 10 minutes.
        Attempt late = start();
        clock.advance(Duration.ofSeconds(600));
        assertExpired(callbackRaw(late.token(), late.state(), code));
        // Reused: the second callback of one attempt.
        Attempt reused = start();
        assertThat(callback(reused, code, 200).path("outcome").asString()).isEqualTo("username_required");
        assertExpired(callbackRaw(reused.token(), reused.state(), code));
        // And the username step of a dead or never-identified attempt.
        Attempt started = start();
        assertExpired(complete(started.token(), "someone", true, 400));
        assertExpired(complete("B".repeat(43), "someone", true, 400));

        assertThat(accounts.rows).hasSize(accountsBefore);
        assertThat(sessions.values).hasSize(sessionsBefore);
    }

    @Test
    void anIdentifiedAttemptExpiresTooBeforeTheUsernameStep() throws Exception {
        Attempt attempt = start();
        callback(attempt, code("slow-" + UUID.randomUUID(), "slow@example.test"), 200);
        clock.advance(Duration.ofSeconds(601));

        assertExpired(complete(attempt.token(), "slow_one", true, 400));
    }

    @Test
    void whenGoogleRefusesNothingIsCreated() throws Exception {
        int accountsBefore = accounts.rows.size();
        Attempt denied = start();
        HttpResponse<String> response = post(SignInController.CALLBACK,
                Map.<String, Object>of("attemptToken", denied.token(), "state", denied.state(), "error", "access_denied"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("code").asString()).isEqualTo("request.sign_in_refused");

        Attempt badCode = start();
        assertThat(callback(badCode, "4/not-a-stand-in-code", 400).path("code").asString()).isEqualTo("request.sign_in_refused");
        Attempt noCode = start();
        assertThat(json(callbackRaw(noCode.token(), noCode.state(), null)).path("code").asString())
                .isEqualTo("request.sign_in_refused");

        assertThat(accounts.rows).hasSize(accountsBefore);
        assertThat(attempts.values).isEmpty();
    }

    @Test
    void aTamperedSealedColumnFailsTheLoadAndGrantsNothing() throws Exception {
        String sub = "tamper-" + UUID.randomUUID();
        String session = signUp(sub, "tamper@example.test", "tamper_" + sub.substring(7, 13));
        AccountRow row = onlyAccountOf(sub);
        accounts.put(new AccountRow(row.id(), "allowlisted", row.state(), row.confirmed(), row.googleId(),
                row.googleIdFingerprint(), row.email(), row.emailFingerprint(), row.username(), row.usernameFingerprint(),
                row.termsVersion(), row.termsAcceptedAt(), row.sealVersion(), row.seal()));

        HttpResponse<String> meResponse = get(SessionController.ME, session);
        assertThat(meResponse.statusCode()).isEqualTo(500);
        assertThat(json(meResponse).path("code").asString()).isEqualTo("integrity.failed");
        assertThat(meResponse.body()).doesNotContain("tamper");

        int sessionsBefore = sessions.values.size();
        Attempt attempt = start();
        HttpResponse<String> signIn = callbackRaw(attempt.token(), attempt.state(), code(sub, "tamper@example.test"));
        assertThat(signIn.statusCode()).isEqualTo(500);
        assertThat(json(signIn).path("code").asString()).isEqualTo("integrity.failed");
        assertThat(sessions.values).hasSize(sessionsBefore);

        // A seal version raised by hand fails as well.
        accounts.put(new AccountRow(row.id(), row.tier(), row.state(), row.confirmed(), row.googleId(),
                row.googleIdFingerprint(), row.email(), row.emailFingerprint(), row.username(), row.usernameFingerprint(),
                row.termsVersion(), row.termsAcceptedAt(), row.sealVersion() + 1, row.seal()));
        assertThat(get(SessionController.ME, session).statusCode()).isEqualTo(500);
        accounts.put(row);
        assertThat(get(SessionController.ME, session).statusCode()).isEqualTo(200);
    }

    @Test
    void aBlockedAccountIsRefusedAndLeftUnchanged() throws Exception {
        String sub = "blocked-" + UUID.randomUUID();
        signUp(sub, "before@example.test", "blocked_" + sub.substring(8, 14));
        AccountRow row = onlyAccountOf(sub);
        // Blocked the way accounts would do it: the state changed and the row sealed again.
        HexFormat hex = HexFormat.of();
        AccountRow blocked = new AccountRow(row.id(), row.tier(), "blocked", row.confirmed(), row.googleId(),
                row.googleIdFingerprint(), row.email(), row.emailFingerprint(), row.username(), row.usernameFingerprint(),
                row.termsVersion(), row.termsAcceptedAt(), row.sealVersion(),
                vault.seal("account", row.id(), row.tier(), "blocked", hex.formatHex(row.googleIdFingerprint()),
                        hex.formatHex(row.usernameFingerprint()), hex.formatHex(row.emailFingerprint()),
                        Boolean.toString(row.confirmed()), Integer.toString(row.sealVersion())));
        accounts.put(blocked);
        int sessionsBefore = sessions.values.size();

        // Google now asserts another email: the account is refused before anything changes.
        JsonNode problem = callback(start(), code(sub, "after@example.test"), 400);

        assertThat(problem.path("code").asString()).isEqualTo("request.sign_in_refused");
        assertThat(onlyAccountOf(sub)).isEqualTo(blocked);
        assertThat(sessions.values).hasSize(sessionsBefore);
    }

    @Test
    void signOutEndsTheSessionAndMeThenAsksForOne() throws Exception {
        String sub = "out-" + UUID.randomUUID();
        String session = signUp(sub, "out@example.test", "out_" + sub.substring(4, 10));
        AccountRow row = onlyAccountOf(sub);
        assertThat(sessions.accountSets.get("gmshield:session:account:" + row.id())).hasSize(1);

        HttpResponse<String> out = send("DELETE", SessionController.SESSION, session);

        assertThat(out.statusCode()).isEqualTo(204);
        assertThat(sessions.values.values()).noneMatch(value -> value.contains(row.id().toString()));
        assertThat(sessions.accountSets.get("gmshield:session:account:" + row.id())).isEmpty();
        HttpResponse<String> after = get(SessionController.ME, session);
        assertThat(after.statusCode()).isEqualTo(401);
        assertThat(json(after).path("code").asString()).isEqualTo("session.required");
        assertThat(json(after).path("category").asString()).isEqualTo("session");
        // Signing out again, or without a token, changes nothing.
        assertThat(send("DELETE", SessionController.SESSION, session).statusCode()).isEqualTo(204);
        assertThat(send("DELETE", SessionController.SESSION, null).statusCode()).isEqualTo(204);
    }

    @Test
    void meWithoutAValidSessionIsSessionRequired() throws Exception {
        for (String token : new String[] {null, "", "short", "C".repeat(43)}) {
            HttpResponse<String> response = get(SessionController.ME, token);
            assertThat(response.statusCode()).as(String.valueOf(token)).isEqualTo(401);
            assertThat(json(response).path("code").asString()).isEqualTo("session.required");
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        }
    }

    @Test
    void twoVisitorsRacingForOneUsernameGetOneAccount() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Attempt first = start();
        Attempt second = start();
        callback(first, code("race-a-" + suffix, "a@example.test"), 200);
        callback(second, code("race-b-" + suffix, "b@example.test"), 200);
        CountDownLatch ready = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<HttpResponse<String>>> both = List.of(
                    () -> { ready.await(); return completeRaw(first.token(), "racer." + suffix, true); },
                    () -> { ready.await(); return completeRaw(second.token(), "RACER." + suffix, true); });
            List<Future<HttpResponse<String>>> results = new ArrayList<>();
            for (Callable<HttpResponse<String>> call : both) {
                results.add(pool.submit(call));
            }
            ready.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<HttpResponse<String>> result : results) {
                HttpResponse<String> response = result.get();
                statuses.add(response.statusCode());
                if (response.statusCode() == 409) {
                    assertThat(json(response).path("code").asString()).isEqualTo("request.username_taken");
                }
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
    }

    // --- helpers ---

    private String signUp(String sub, String email, String username) throws Exception {
        Attempt attempt = start();
        assertThat(callback(attempt, code(sub, email), 200).path("outcome").asString()).isEqualTo("username_required");
        String session = complete(attempt.token(), username, true, 200).path("sessionToken").asString();
        secrets.addAll(List.of(sub, email, username, session));
        return session;
    }

    private Attempt start() throws Exception {
        JsonNode body = JSON.readTree(post(SignInController.START, null).body());
        String token = body.path("attemptToken").asString();
        String state = query(URI.create(body.path("authorizationUrl").asString()).getRawQuery()).get("state");
        secrets.add(token);
        return new Attempt(token, state);
    }

    private static String code(String sub, String email) {
        return "stand-in." + sub + "." + email;
    }

    private JsonNode callback(Attempt attempt, String code, int expectedStatus) throws Exception {
        HttpResponse<String> response = callbackRaw(attempt.token(), attempt.state(), code);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode body = json(response);
        if (body.path("session").has("sessionToken")) {
            secrets.add(body.path("session").path("sessionToken").asString());
        }
        return body;
    }

    private HttpResponse<String> callbackRaw(String token, String state, String code) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attemptToken", token);
        body.put("state", state);
        body.put("code", code);
        return post(SignInController.CALLBACK, body);
    }

    private JsonNode complete(String token, String username, boolean terms, int expectedStatus) throws Exception {
        HttpResponse<String> response = completeRaw(token, username, terms);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        return json(response);
    }

    private HttpResponse<String> completeRaw(String token, String username, boolean terms) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attemptToken", token);
        body.put("username", username);
        body.put("termsAccepted", terms);
        return post(SignInController.COMPLETE, body);
    }

    private JsonNode me(String session) throws Exception {
        HttpResponse<String> response = get(SessionController.ME, session);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        return json(response);
    }

    private AccountRow onlyAccountOf(String sub) {
        return accounts.findByGoogleIdFingerprint(vault.fingerprint("account", "google_id", sub))
                .orElseThrow(() -> new AssertionError("no account for " + sub));
    }

    private static void assertExpired(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertExpired(json(response));
    }

    private static void assertExpired(JsonNode problem) {
        assertThat(problem.path("code").asString()).isEqualTo("request.sign_in_expired");
    }

    private HttpResponse<String> post(String path, Map<String, Object> body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (body == null) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String session) throws Exception {
        return send("GET", path, session);
    }

    private HttpResponse<String> send(String method, String path, String session) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (session != null && !session.isEmpty()) {
            request.header("X-Session-Token", session);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return values;
    }
}
