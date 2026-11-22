package io.github.gmshield.accounts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.JsonLineLogFormatter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** The real Google client against a local token endpoint: the request it sends and every ID token check. */
class GoogleOidcClientTest {

    private static final String CLIENT_ID = "client-1.apps.googleusercontent.com";
    private static final String CLIENT_SECRET = "client-secret-never-logged";
    private static final String REDIRECT = "http://localhost:3000/auth/google/callback";
    private static final String CODE = "4/0AQSTgQ-code-never-logged";
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String NONCE = "n-0S6_WzA2Mj";
    private static final String EMAIL = "Renata.Mestre@example.com";
    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestType = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "{}";
    private GoogleOidcClient client;

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void startTokenEndpoint() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            requestType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new GoogleOidcClient(CLIENT_ID, CLIENT_SECRET, REDIRECT, GoogleOidcClient.AUTHORIZATION_ENDPOINT,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token"), Clock.fixed(NOW, ZoneOffset.UTC));
        // Server and background threads log while a test reads the list: a copy-on-write list never throws
        // ConcurrentModificationException there (an ArrayList did, in a develop run of ConversationFlowTest).
        logged.list = new java.util.concurrent.CopyOnWriteArrayList<>();
        logged.start();
        root.addAppender(logged);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        root.detachAppender(logged);
        logged.stop();
    }

    @Test
    void authorizationUrlAsksGoogleForOpenidAndEmailWithPkceStateAndNonce() {
        URI url = URI.create(client.authorizationUrl("the-state", NONCE, "the-challenge"));

        assertThat(url.getScheme() + "://" + url.getHost() + url.getPath()).isEqualTo("https://accounts.google.com/o/oauth2/v2/auth");
        assertThat(query(url.getRawQuery())).containsExactly(
                Map.entry("response_type", "code"), Map.entry("client_id", CLIENT_ID), Map.entry("redirect_uri", REDIRECT),
                Map.entry("scope", "openid email"), Map.entry("state", "the-state"), Map.entry("nonce", NONCE),
                Map.entry("code_challenge", "the-challenge"), Map.entry("code_challenge_method", "S256"));
        assertThat(url.getRawQuery()).contains("scope=openid%20email").doesNotContain(CLIENT_SECRET);
    }

    @Test
    void aValidAnswerGivesTheIdentityAndTheRequestCarriesTheCodeTheSecretAndTheVerifier() {
        answer = tokenAnswer(claims());

        GoogleIdentity identity = client.exchange(CODE, VERIFIER, NONCE);

        assertThat(identity.subject()).isEqualTo("110169484474386276334");
        assertThat(identity.email()).isEqualTo(EMAIL);
        assertThat(identity.toString()).doesNotContain(EMAIL);
        assertThat(requestType.get()).startsWith("application/x-www-form-urlencoded");
        assertThat(query(requestBody.get())).containsExactly(
                Map.entry("grant_type", "authorization_code"), Map.entry("code", CODE), Map.entry("client_id", CLIENT_ID),
                Map.entry("client_secret", CLIENT_SECRET), Map.entry("redirect_uri", REDIRECT),
                Map.entry("code_verifier", VERIFIER));
        assertNothingSecretLogged();
    }

    @Test
    void theAcceptedVariantsOfIssuerAudienceAndEmailVerified() {
        Map<String, Object> claims = claims();
        claims.put("iss", "accounts.google.com");
        claims.put("aud", new String[] {CLIENT_ID});
        claims.put("email_verified", "true");
        answer = tokenAnswer(claims);
        assertThat(client.exchange(CODE, VERIFIER, NONCE).email()).isEqualTo(EMAIL);

        claims.put("aud", new String[] {"other-client", CLIENT_ID});
        claims.put("azp", CLIENT_ID);
        answer = tokenAnswer(claims);
        assertThat(client.exchange(CODE, VERIFIER, NONCE).email()).isEqualTo(EMAIL);

        claims.put("exp", NOW.getEpochSecond() - 30);   // within the leeway
        answer = tokenAnswer(claims);
        assertThat(client.exchange(CODE, VERIFIER, NONCE).email()).isEqualTo(EMAIL);
    }

    @Test
    void everyFailedClaimIsRefused() {
        Map<String, Map<String, Object>> cases = new LinkedHashMap<>();
        cases.put("iss", with("iss", "https://evil.example"));
        cases.put("iss missing", without("iss"));
        cases.put("aud", with("aud", "other-client"));
        Map<String, Object> noAzp = with("aud", new String[] {"other-client", CLIENT_ID});
        noAzp.remove("azp");
        cases.put("aud list without azp", noAzp);
        cases.put("aud list with another azp", with("aud", new String[] {"other-client", CLIENT_ID}));
        cases.get("aud list with another azp").put("azp", "other-client");
        cases.put("aud empty list", with("aud", new String[0]));
        cases.put("exp past", with("exp", NOW.getEpochSecond() - 61));
        cases.put("exp missing", without("exp"));
        cases.put("exp text", with("exp", "4102444800"));
        cases.put("nonce", with("nonce", "another-nonce"));
        cases.put("nonce missing", without("nonce"));
        cases.put("email_verified false", with("email_verified", false));
        cases.put("email_verified missing", without("email_verified"));
        cases.put("email_verified text", with("email_verified", "yes"));
        cases.put("sub missing", without("sub"));
        cases.put("sub blank", with("sub", " "));
        cases.put("email missing", without("email"));
        cases.put("email without @", with("email", "renata"));
        for (Map.Entry<String, Map<String, Object>> refused : cases.entrySet()) {
            answer = tokenAnswer(refused.getValue());

            assertThatThrownBy(() -> client.exchange(CODE, VERIFIER, NONCE)).as(refused.getKey())
                    .isInstanceOf(ApiException.class).hasMessage("request.sign_in_refused");
        }
        assertNothingSecretLogged();
    }

    @Test
    void aMalformedAnswerOrAnErrorStatusIsRefused() {
        for (String malformed : new String[] {"{}", "not json", "{\"id_token\":\"a.b\"}", "{\"id_token\":\"a.%%%.c\"}",
                "{\"id_token\":\"a." + b64("[1,2]") + ".c\"}", "{\"id_token\":7}"}) {
            answer = malformed;
            assertThatThrownBy(() -> client.exchange(CODE, VERIFIER, NONCE)).as(malformed)
                    .isInstanceOf(ApiException.class).hasMessage("request.sign_in_refused");
        }
        status = 400;
        answer = "{\"error\":\"invalid_grant\",\"error_description\":\"Bad code " + CODE + "\"}";
        assertThatThrownBy(() -> client.exchange(CODE, VERIFIER, NONCE)).hasMessage("request.sign_in_refused");
        assertThat(lines()).contains("google.exchange_failed", "\"googleStatus\":400");
        assertNothingSecretLogged();
    }

    @Test
    void anUnreachableTokenEndpointIsRefused() {
        server.stop(0);

        assertThatThrownBy(() -> client.exchange(CODE, VERIFIER, NONCE)).hasMessage("request.sign_in_refused");
        assertThat(lines()).contains("google.exchange_failed");
        assertNothingSecretLogged();
    }

    private void assertNothingSecretLogged() {
        assertThat(lines()).doesNotContain(CODE).doesNotContain(CLIENT_SECRET).doesNotContain(EMAIL)
                .doesNotContain(VERIFIER).doesNotContain("110169484474386276334");
    }

    private String lines() {
        JsonLineLogFormatter formatter = new JsonLineLogFormatter();
        return String.join("", logged.list.stream().map(formatter::format).toList());
    }

    private static Map<String, Object> claims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "https://accounts.google.com");
        claims.put("azp", CLIENT_ID);
        claims.put("aud", CLIENT_ID);
        claims.put("sub", "110169484474386276334");
        claims.put("email", EMAIL);
        claims.put("email_verified", true);
        claims.put("nonce", NONCE);
        claims.put("iat", NOW.getEpochSecond() - 5);
        claims.put("exp", NOW.getEpochSecond() + 3600);
        return claims;
    }

    private static Map<String, Object> with(String name, Object value) {
        Map<String, Object> claims = claims();
        claims.put(name, value);
        return claims;
    }

    private static Map<String, Object> without(String name) {
        Map<String, Object> claims = claims();
        claims.remove(name);
        return claims;
    }

    private static String tokenAnswer(Map<String, Object> claims) {
        String idToken = b64("{\"alg\":\"RS256\",\"kid\":\"k1\",\"typ\":\"JWT\"}") + "." + b64(JSON.writeValueAsString(claims))
                + ".c2lnbmF0dXJl";
        return JSON.writeValueAsString(Map.of("access_token", "ya29.access-token", "expires_in", 3599, "id_token", idToken,
                "scope", "openid https://www.googleapis.com/auth/userinfo.email", "token_type", "Bearer"));
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return values;
    }
}
