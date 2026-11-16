package io.github.gmshield.accounts.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.gmshield.accounts.data.AccountStore;
import io.github.gmshield.accounts.data.InMemoryAccountStore;
import io.github.gmshield.accounts.data.InMemorySessionStore;
import io.github.gmshield.accounts.data.InMemorySignInAttemptStore;
import io.github.gmshield.accounts.data.SessionStore;
import io.github.gmshield.accounts.data.SignInAttemptStore;
import io.github.gmshield.accounts.web.SignInFlowTest.MovableClock;
import io.github.gmshield.audit.data.AuditRow;
import io.github.gmshield.audit.data.AuditStore;
import io.github.gmshield.audit.data.InMemoryAuditStore;
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
import java.time.Duration;
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
 * Sessions that rotate and end (Story 1.10): every row of the I/O matrix, with the default timings
 * (renew after 15 min, 60 s of overlap, 24 h idle, 7 days absolute) on a clock the tests move, a renewal
 * race, the audit row of a reuse, and what Redis, the audit log and the log lines never hold.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GMSHIELD_MODE=test")
@ActiveProfiles({"test-mode", "unit"})
class SessionRotationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration OVERLAP = Duration.ofSeconds(60);

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
        SessionStore inMemorySessions(MovableClock clock) {
            return new InMemorySessionStore(clock);
        }

        @Bean
        @Primary
        AuditStore inMemoryAudit() {
            return new InMemoryAuditStore();
        }

        @Bean
        MovableClock clock() {
            return new MovableClock();
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private InMemorySessionStore sessions;

    @Autowired
    private InMemoryAuditStore audit;

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
        sessions.clear();
        audit.rows.clear();
        audit.failing = false;
        logged.start();
        root.addAppender(logged);
    }

    @AfterEach
    void nothingSecretWasStoredOrLogged() {
        root.detachAppender(logged);
        logged.stop();
        JsonLineLogFormatter formatter = new JsonLineLogFormatter();
        String lines = String.join("", logged.list.stream().map(formatter::format).toList());
        String stored = String.join("\n", sessions.values.values());
        String audited = audit.rows.toString();
        for (String secret : secrets) {
            assertThat(lines).as("the log").doesNotContain(secret);
            assertThat(stored).as("Redis").doesNotContain(secret);
            assertThat(audited).as("the audit log").doesNotContain(secret);
        }
        // Redis holds only session ids, 64-hex hashes and account ids in its keys.
        assertThat(sessions.values.keySet()).allMatch(key -> key.matches(
                "gmshield:session:(token:[0-9a-f]{64}|[0-9a-f-]{36})"));
    }

    // --- Renew ---

    @Test
    void renewingAfterRenewAfterIssuesANewTokenAndKeepsTheOldOneAsPreviousForTheOverlap() throws Exception {
        String first = signUp("renew");
        clock.advance(Duration.ofMinutes(15));

        JsonNode grant = renew(first, 200);

        String second = grant.path("sessionToken").asString();
        secrets.add(second);
        assertThat(second).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(first);
        assertThat(grant.path("renewAfterSeconds").asLong()).isEqualTo(900);
        // What is left of the absolute timeout, not a fresh 7 days.
        assertThat(grant.path("expiresInSeconds").asLong()).isEqualTo(Duration.ofDays(7).minusMinutes(15).toSeconds());
        JsonNode record = recordOf(second);
        assertThat(record.path("currentHash").asString()).isEqualTo(hashOf(second));
        assertThat(record.path("previousHash").asString()).isEqualTo(hashOf(first));
        assertThat(record.path("currentUsed").asBoolean()).isFalse();
        assertThat(record.path("overlapUntil").asLong() - clock.instant().toEpochMilli()).isEqualTo(OVERLAP.toMillis());
        // Both pointers name the session; the retired one lives until the absolute end.
        assertThat(sessions.values.get("gmshield:session:token:" + hashOf(first))).isEqualTo(record.path("id").asString());
        assertThat(sessions.timesToLive.get("gmshield:session:token:" + hashOf(first)))
                .isEqualTo(Duration.ofDays(7).minusMinutes(15));
        assertThat(me(second, 200).path("username").asString()).startsWith("renew_");
        assertThat(recordOf(second).path("currentUsed").asBoolean()).isTrue();
    }

    @Test
    void anOrdinaryCallNeverIssuesATokenAndWritesLastSeenAtAtMostOnceAMinute() throws Exception {
        String token = signUp("calls");
        long version = recordOf(token).path("version").asLong();

        HttpResponse<String> response = get(SessionController.ME, token);
        assertThat(response.body()).doesNotContain("sessionToken");
        assertThat(recordOf(token).path("version").asLong()).isEqualTo(version + 1);   // marked used
        get(SessionController.ME, token);
        clock.advance(Duration.ofSeconds(59));
        get(SessionController.ME, token);
        assertThat(recordOf(token).path("version").asLong()).isEqualTo(version + 1);   // no write within the minute
        clock.advance(Duration.ofSeconds(1));
        get(SessionController.ME, token);
        assertThat(recordOf(token).path("version").asLong()).isEqualTo(version + 2);
        assertThat(recordOf(token).path("lastSeenAt").asLong()).isEqualTo(clock.instant().toEpochMilli());
    }

    // --- Overlap use and double renewal ---

    @Test
    void thePreviousTokenServesOrdinaryCallsWithinTheOverlapAndARenewalWithItIsAlreadyRenewed() throws Exception {
        String first = signUp("overlap");
        clock.advance(Duration.ofMinutes(16));
        String second = renewed(first);

        clock.advance(Duration.ofSeconds(59));
        assertThat(me(first, 200).has("username")).isTrue();

        HttpResponse<String> again = send("POST", SessionController.RENEWAL, first);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(json(again).path("code").asString()).isEqualTo("session.already_renewed");
        assertThat(again.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        // Nothing was issued: the session continues with the second token.
        assertThat(recordOf(second).path("currentHash").asString()).isEqualTo(hashOf(second));
        assertThat(me(second, 200).has("username")).isTrue();
        assertThat(audit.rows).isEmpty();
    }

    @Test
    void twoRenewalsWithTheSameTokenAtOnceGiveExactlyOneGrant() throws Exception {
        String token = signUp("race");
        clock.advance(Duration.ofMinutes(15));
        int racers = 6;
        CountDownLatch ready = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            List<Future<HttpResponse<String>>> results = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                Callable<HttpResponse<String>> call = () -> {
                    ready.await();
                    return send("POST", SessionController.RENEWAL, token);
                };
                results.add(pool.submit(call));
            }
            ready.countDown();
            List<Integer> statuses = new ArrayList<>();
            String issued = null;
            for (Future<HttpResponse<String>> result : results) {
                HttpResponse<String> response = result.get();
                statuses.add(response.statusCode());
                if (response.statusCode() == 200) {
                    issued = json(response).path("sessionToken").asString();
                    secrets.add(issued);
                } else {
                    assertThat(json(response).path("code").asString()).isEqualTo("session.already_renewed");
                }
            }
            assertThat(statuses.stream().filter(status -> status == 200)).hasSize(1);
            assertThat(statuses.stream().filter(status -> status == 409)).hasSize(racers - 1);
            assertThat(recordOf(issued).path("currentHash").asString()).isEqualTo(hashOf(issued));
        } finally {
            pool.shutdownNow();
        }
    }

    // --- Reuse and stale ---

    @Test
    void thePreviousTokenAfterTheOverlapOnceTheNewerOneWasUsedEndsTheSessionAndIsAudited() throws Exception {
        String first = signUp("reuse");
        UUID account = accountOf(first);
        clock.advance(Duration.ofMinutes(15));
        String second = renewed(first);
        String sessionId = recordOf(second).path("id").asString();
        me(second, 200);
        clock.advance(OVERLAP);

        assertRequired(get(SessionController.ME, first));

        // The session is gone for both tokens.
        assertThat(sessions.values.get("gmshield:session:" + sessionId)).isNull();
        assertThat(sessions.accountSets.get("gmshield:session:account:" + account)).doesNotContain(sessionId);
        assertRequired(get(SessionController.ME, second));
        AuditRow row = audit.rows.getFirst();
        assertThat(audit.rows).hasSize(1);
        assertThat(row.action()).isEqualTo("session.token_reused");
        assertThat(row.accountId()).isEqualTo(account);
        assertThat(row.traceId()).matches("[0-9a-f]{32}");
        assertThat(row.at()).isEqualTo(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(JSON.readTree(row.details())).isEqualTo(JSON.readTree(
                "{\"reusedToken\":\"previous\",\"sessionId\":\"" + sessionId + "\"}"));
        assertThat(row.details()).doesNotContain(hashOf(first)).doesNotContain(hashOf(second));
    }

    @Test
    void aTokenTwoRenewalsOldIsReuseWhateverTheTiming() throws Exception {
        String first = signUp("older");
        clock.advance(Duration.ofMinutes(15));
        String second = renewed(first);
        clock.advance(Duration.ofMinutes(15));
        String third = renewed(second);

        // Within the overlap of the second renewal, the first token is already two renewals old.
        assertRequired(send("POST", SessionController.RENEWAL, first));

        assertRequired(get(SessionController.ME, third));
        assertThat(audit.rows).singleElement().satisfies(row ->
                assertThat(row.details()).contains("\"reusedToken\":\"older\""));
    }

    @Test
    void renewingWithThePreviousTokenAfterTheOverlapIsReuseToo() throws Exception {
        String first = signUp("renewreuse");
        clock.advance(Duration.ofMinutes(15));
        String second = renewed(first);
        me(second, 200);
        clock.advance(OVERLAP.plusSeconds(1));

        assertRequired(send("POST", SessionController.RENEWAL, first));
        assertRequired(get(SessionController.ME, second));
        assertThat(audit.rows).hasSize(1);
    }

    @Test
    void thePreviousTokenAfterTheOverlapWhileTheNewerOneWasNeverUsedIsRefusedAndTheSessionKept() throws Exception {
        String first = signUp("stale");
        clock.advance(Duration.ofMinutes(15));
        String second = renewed(first);
        clock.advance(OVERLAP);

        assertRequired(get(SessionController.ME, first));
        assertRequired(send("POST", SessionController.RENEWAL, first));

        assertThat(audit.rows).isEmpty();
        assertThat(me(second, 200).has("username")).isTrue();
    }

    @Test
    void whenTheAuditAppendFailsTheReuseIsStillRefusedAndTheFailureLogged() throws Exception {
        String first = signUp("auditfail");
        clock.advance(Duration.ofMinutes(15));
        String second = renewed(first);
        me(second, 200);
        clock.advance(OVERLAP);
        audit.failing = true;

        assertRequired(get(SessionController.ME, first));

        assertRequired(get(SessionController.ME, second));
        assertThat(audit.rows).isEmpty();
        assertThat(logged.list).anySatisfy(event -> {
            assertThat(event.getMessage()).isEqualTo("audit.append_failed");
            assertThat(event.getKeyValuePairs()).extracting(pair -> pair.key)
                    .contains("accountId", "action", "sessionId", "reusedToken");
        });
    }

    @Test
    void aReuseEndsTheSessionEvenWhenAConcurrentWriteCameJustBeforeTheEnding() throws Exception {
        String first = signUp("reuserace");
        clock.advance(Duration.ofMinutes(15));
        String second = renewed(first);
        String sessionId = recordOf(second).path("id").asString();
        me(second, 200);
        clock.advance(OVERLAP);
        sessions.writesBeforeDelete = 1;

        assertRequired(get(SessionController.ME, first));

        assertThat(sessions.writesBeforeDelete).isZero();
        assertThat(sessions.values.get("gmshield:session:" + sessionId)).isNull();
        assertRequired(get(SessionController.ME, second));
        assertThat(audit.rows).singleElement().satisfies(row -> assertThat(row.action()).isEqualTo("session.token_reused"));
    }

    @Test
    void signingOutEndsTheSessionEvenWhenAConcurrentWriteCameJustBeforeIt() throws Exception {
        String token = signUp("outrace");
        sessions.writesBeforeDelete = 1;

        assertThat(send("DELETE", SessionController.SESSION, token).statusCode()).isEqualTo(204);

        assertThat(sessions.writesBeforeDelete).isZero();
        assertRequired(get(SessionController.ME, token));
        assertThat(sessions.values).isEmpty();
        assertThat(audit.rows).isEmpty();
    }

    // --- Idle and absolute ---

    @Test
    void aDayWithoutACallEndsTheSession() throws Exception {
        String token = signUp("idle");
        String sessionId = recordOf(token).path("id").asString();
        clock.advance(Duration.ofHours(23).plusMinutes(59));
        me(token, 200);
        // The idle time counts from the last call.
        clock.advance(Duration.ofHours(23).plusMinutes(59));
        me(token, 200);
        clock.advance(Duration.ofHours(24));

        assertRequired(get(SessionController.ME, token));
        assertThat(sessions.get("gmshield:session:" + sessionId)).isEmpty();
        assertRequired(send("POST", SessionController.RENEWAL, token));
    }

    @Test
    void sevenDaysAfterSignInTheSessionEndsHoweverActive() throws Exception {
        String token = signUp("absolute");
        Duration elapsed = Duration.ZERO;
        while (elapsed.compareTo(Duration.ofDays(7).minusHours(20)) < 0) {
            clock.advance(Duration.ofHours(20));
            elapsed = elapsed.plusHours(20);
            JsonNode grant = renew(token, 200);
            token = grant.path("sessionToken").asString();
            secrets.add(token);
            assertThat(grant.path("expiresInSeconds").asLong()).isEqualTo(Duration.ofDays(7).minus(elapsed).toSeconds());
            me(token, 200);
        }
        clock.advance(Duration.ofDays(7).minus(elapsed));

        assertRequired(get(SessionController.ME, token));
        assertRequired(send("POST", SessionController.RENEWAL, token));
    }

    @Test
    void everyKeyLivesAsLongAsTheShorterOfWhatIsLeftOfTheIdleAndTheAbsoluteTimeouts() throws Exception {
        String token = signUp("ttl");
        String sessionKey = "gmshield:session:" + recordOf(token).path("id").asString();
        String pointerKey = "gmshield:session:token:" + hashOf(token);
        assertThat(sessions.timesToLive.get(sessionKey)).isEqualTo(Duration.ofHours(24));

        for (int call = 0; call < 8; call++) {
            clock.advance(Duration.ofHours(20));
            me(token, 200);
        }

        // 6 days and 16 hours in: 8 hours are left of the 7 days, fewer than the 24 hours of idle time.
        assertThat(sessions.timesToLive.get(sessionKey)).isEqualTo(Duration.ofHours(8));
        assertThat(sessions.timesToLive.get(pointerKey)).isEqualTo(Duration.ofHours(8));
    }

    @Test
    void inTheLastRenewAfterWindowRenewalIsDueOnlyWhenTheSessionEnds() throws Exception {
        String token = signUp("lastwindow");
        for (int call = 0; call < 8; call++) {
            clock.advance(Duration.ofHours(20));
            me(token, 200);
        }
        // 6 days, 23 hours and 50 minutes in: 10 minutes are left, fewer than the 15 of renew-after.
        clock.advance(Duration.ofHours(7).plusMinutes(50));

        JsonNode grant = renew(token, 200);
        secrets.add(grant.path("sessionToken").asString());

        assertThat(grant.path("expiresInSeconds").asLong()).isEqualTo(600);
        assertThat(grant.path("renewAfterSeconds").asLong()).isEqualTo(600);
    }

    // --- Lost writes ---

    @Test
    void aLastSeenWriteLostOnceAndAgainOnTheRetryStillServesTheCall() throws Exception {
        String token = signUp("lostseen");
        // The first call with a fresh token marks it used, which writes; both attempts lose their write.
        sessions.replacesToLose = 2;

        assertThat(me(token, 200).has("username")).isTrue();

        assertThat(sessions.replacesToLose).isZero();
        assertThat(me(token, 200).has("username")).isTrue();
        assertThat(recordOf(token).path("currentUsed").asBoolean()).isTrue();
    }

    @Test
    void aRenewalLostTwiceIsRefusedAndTheSessionKept() throws Exception {
        String token = signUp("lostrenew");
        clock.advance(Duration.ofMinutes(15));
        sessions.replacesToLose = 2;

        assertRequired(send("POST", SessionController.RENEWAL, token));

        assertThat(audit.rows).isEmpty();
        assertThat(me(token, 200).has("username")).isTrue();
    }

    // --- Seal version ---

    @Test
    void aRisenSealVersionAsksForARenewalWhichIssuesATokenUnderTheNewVersion() throws Exception {
        String sub = "sealed-" + UUID.randomUUID();
        String first = signUpAs(sub, "old@example.test", "sealed_" + sub.substring(7, 13));
        assertThat(recordOf(first).path("sealVersion").asInt()).isEqualTo(1);

        // A returning sign-in with another email address raises the seal version to 2.
        String other = callbackSession(sub, "new@example.test");

        HttpResponse<String> refused = get(SessionController.ME, first);
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(json(refused).path("code").asString()).isEqualTo("session.renewal_required");
        assertThat(json(refused).path("category").asString()).isEqualTo("session");

        String renewedToken = renewed(first);
        assertThat(recordOf(renewedToken).path("sealVersion").asInt()).isEqualTo(2);
        assertThat(me(renewedToken, 200).has("username")).isTrue();
        assertThat(me(other, 200).has("username")).isTrue();
    }

    // --- Old formats, sign-out, malformed ---

    @Test
    void aStory19CookieOrRedisRecordIsNoSessionNeverAnError() throws Exception {
        String old = "D".repeat(43);
        secrets.add(old);
        // What Story 1.9 wrote: the record under the token's hash, no pointer.
        sessions.values.put("gmshield:session:" + hashOf(old),
                "{\"accountId\":\"" + UUID.randomUUID() + "\",\"sealVersion\":1,\"createdAt\":1800000000}");
        assertRequired(get(SessionController.ME, old));
        assertRequired(send("POST", SessionController.RENEWAL, old));
        // A pointer to something unreadable.
        sessions.values.put("gmshield:session:token:" + hashOf(old), "not-an-id");
        assertRequired(get(SessionController.ME, old));
        UUID id = UUID.randomUUID();
        sessions.values.put("gmshield:session:token:" + hashOf(old), id.toString());
        sessions.values.put("gmshield:session:" + id, "{damaged");
        assertRequired(send("POST", SessionController.RENEWAL, old));
        sessions.values.keySet().removeIf(key -> key.contains(hashOf(old)) || key.endsWith(id.toString()));
    }

    @Test
    void aRenewalWithoutAWellFormedTokenIsSessionRequired() throws Exception {
        for (String token : new String[] {null, "", "short", "E".repeat(43)}) {
            assertRequired(send("POST", SessionController.RENEWAL, token));
        }
    }

    @Test
    void signingOutWithThePreviousTokenWithinTheOverlapEndsTheSession() throws Exception {
        String first = signUp("outprev");
        clock.advance(Duration.ofMinutes(15));
        String second = renewed(first);

        assertThat(send("DELETE", SessionController.SESSION, first).statusCode()).isEqualTo(204);

        assertRequired(get(SessionController.ME, second));
        assertThat(sessions.values).isEmpty();
        assertThat(audit.rows).isEmpty();
    }

    // --- helpers ---

    private String signUp(String prefix) throws Exception {
        String sub = prefix + "-" + UUID.randomUUID();
        return signUpAs(sub, prefix + "@example.test", (prefix + "_" + sub.substring(sub.length() - 6)).substring(0,
                Math.min(24, prefix.length() + 7)));
    }

    private String signUpAs(String sub, String email, String username) throws Exception {
        JsonNode started = JSON.readTree(post(SignInController.START, null).body());
        String attempt = started.path("attemptToken").asString();
        String state = query(URI.create(started.path("authorizationUrl").asString()).getRawQuery()).get("state");
        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put("attemptToken", attempt);
        callback.put("state", state);
        callback.put("code", "stand-in." + sub + "." + email);
        assertThat(json(post(SignInController.CALLBACK, callback)).path("outcome").asString()).isEqualTo("username_required");
        HttpResponse<String> complete = post(SignInController.COMPLETE,
                Map.<String, Object>of("attemptToken", attempt, "username", username, "termsAccepted", true));
        assertThat(complete.statusCode()).as(complete.body()).isEqualTo(200);
        String session = json(complete).path("sessionToken").asString();
        secrets.addAll(List.of(attempt, sub, email, username, session));
        return session;
    }

    /** A returning sign-in of a known identity: its new session token. */
    private String callbackSession(String sub, String email) throws Exception {
        JsonNode started = JSON.readTree(post(SignInController.START, null).body());
        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put("attemptToken", started.path("attemptToken").asString());
        callback.put("state", query(URI.create(started.path("authorizationUrl").asString()).getRawQuery()).get("state"));
        callback.put("code", "stand-in." + sub + "." + email);
        JsonNode outcome = json(post(SignInController.CALLBACK, callback));
        assertThat(outcome.path("outcome").asString()).isEqualTo("signed_in");
        String session = outcome.path("session").path("sessionToken").asString();
        secrets.addAll(List.of(started.path("attemptToken").asString(), email, session));
        return session;
    }

    private JsonNode renew(String token, int expectedStatus) throws Exception {
        HttpResponse<String> response = send("POST", SessionController.RENEWAL, token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        return json(response);
    }

    private String renewed(String token) throws Exception {
        String next = renew(token, 200).path("sessionToken").asString();
        secrets.add(next);
        return next;
    }

    private JsonNode me(String token, int expectedStatus) throws Exception {
        HttpResponse<String> response = get(SessionController.ME, token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        return json(response);
    }

    private String hashOf(String token) {
        return HexFormat.of().formatHex(vault.tokenHash("session", token));
    }

    /** The stored record of the session a token's pointer names. */
    private JsonNode recordOf(String token) {
        String sessionId = sessions.get("gmshield:session:token:" + hashOf(token)).orElseThrow();
        return JSON.readTree(sessions.get("gmshield:session:" + sessionId).orElseThrow());
    }

    private UUID accountOf(String token) {
        return UUID.fromString(recordOf(token).path("accountId").asString());
    }

    private static void assertRequired(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        assertThat(json(response).path("code").asString()).isEqualTo("session.required");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private HttpResponse<String> post(String path, Map<String, Object> body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + SignInFlowTest.CREDENTIAL);
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
                .header("Authorization", "Bearer " + SignInFlowTest.CREDENTIAL)
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
