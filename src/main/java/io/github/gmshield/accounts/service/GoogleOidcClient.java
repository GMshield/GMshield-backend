package io.github.gmshield.accounts.service;

import io.github.gmshield.shared.service.ApiException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Google's OIDC code flow, run by the backend alone (AD-8): the browser is sent to Google's authorization
 * endpoint with {@code openid email}, PKCE S256, a state and a nonce; the code that comes back is
 * exchanged at Google's token endpoint over TLS together with the client secret and the PKCE verifier.
 *
 * <p>The ID token comes straight from Google's token endpoint over TLS, so its signature is not checked
 * (OIDC Core 3.1.3.7, item 6). Its claims are: {@code iss} is Google, {@code aud} is this client (and
 * {@code azp} too when there are several audiences), {@code exp} is not past, {@code nonce} is this
 * attempt's, and {@code email_verified} is true. Anything else is refused.
 */
final class GoogleOidcClient implements GoogleSignIn {

    static final URI AUTHORIZATION_ENDPOINT = URI.create("https://accounts.google.com/o/oauth2/v2/auth");
    static final URI TOKEN_ENDPOINT = URI.create("https://oauth2.googleapis.com/token");
    static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");
    static final String SCOPE = "openid email";
    /** Clock skew allowed on {@code exp}. */
    static final Duration LEEWAY = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_SUBJECT = 255;
    private static final int MAX_EMAIL = 320;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(GoogleOidcClient.class);

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final URI authorizationEndpoint;
    private final URI tokenEndpoint;
    private final Clock clock;
    private final RestClient http;

    GoogleOidcClient(String clientId, String clientSecret, String redirectUri) {
        this(clientId, clientSecret, redirectUri, AUTHORIZATION_ENDPOINT, TOKEN_ENDPOINT, Clock.systemUTC());
    }

    /** Other endpoints and another clock: tests only. */
    GoogleOidcClient(String clientId, String clientSecret, String redirectUri, URI authorizationEndpoint,
                     URI tokenEndpoint, Clock clock) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.authorizationEndpoint = authorizationEndpoint;
        this.tokenEndpoint = tokenEndpoint;
        this.clock = clock;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(client);
        requests.setReadTimeout(READ_TIMEOUT);
        this.http = RestClient.builder().requestFactory(requests).build();
    }

    @Override
    public String authorizationUrl(String state, String nonce, String codeChallenge) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", clientId);
        query.put("redirect_uri", redirectUri);
        query.put("scope", SCOPE);
        query.put("state", state);
        query.put("nonce", nonce);
        query.put("code_challenge", codeChallenge);
        query.put("code_challenge_method", "S256");
        return authorizationEndpoint + "?" + form(query);
    }

    @Override
    public GoogleIdentity exchange(String code, String codeVerifier, String nonce) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        body.put("client_id", clientId);
        body.put("client_secret", clientSecret);
        body.put("redirect_uri", redirectUri);
        body.put("code_verifier", codeVerifier);
        ResponseEntity<String> response;
        try {
            response = http.post()
                    .uri(tokenEndpoint)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form(body))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, failed) -> {
                        // Read as an answer below: its status is logged, its body (which may echo the request) never.
                    })
                    .toEntity(String.class);
        } catch (RestClientException unreachable) {
            log.atWarn().addKeyValue("errorType", unreachable.getClass().getSimpleName()).log("google.exchange_failed");
            throw refused("unreachable");
        }
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            log.atWarn().addKeyValue("googleStatus", response.getStatusCode().value()).log("google.exchange_failed");
            throw refused("token_endpoint");
        }
        return identityOf(response.getBody(), nonce);
    }

    /** The identity in the token endpoint's answer, after every check on its ID token's claims. */
    GoogleIdentity identityOf(String tokenResponse, String nonce) {
        JsonNode claims;
        try {
            String idToken = JSON.readTree(tokenResponse).path("id_token").asString();
            String[] parts = idToken.split("\\.", -1);
            if (parts.length != 3) {
                throw refused("id_token_form");
            }
            claims = JSON.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        } catch (ApiException refused) {
            throw refused;
        } catch (RuntimeException unreadable) {
            throw refused("id_token_form");
        }
        if (!claims.isObject()) {
            throw refused("id_token_form");
        }
        if (!claims.path("iss").isString() || !ISSUERS.contains(claims.path("iss").asString())) {
            throw refused("iss");
        }
        if (!audienceIsThisClient(claims)) {
            throw refused("aud");
        }
        JsonNode exp = claims.path("exp");
        if (!exp.isNumber() || clock.instant().minus(LEEWAY).getEpochSecond() >= exp.asLong()) {
            throw refused("exp");
        }
        if (!claims.path("nonce").isString() || !Tokens.sameText(nonce, claims.path("nonce").asString())) {
            throw refused("nonce");
        }
        JsonNode verified = claims.path("email_verified");
        if (!(verified.isBoolean() && verified.asBoolean()) && !(verified.isString() && "true".equals(verified.asString()))) {
            throw refused("email_verified");
        }
        String subject = claims.path("sub").isString() ? claims.path("sub").asString() : "";
        String email = claims.path("email").isString() ? claims.path("email").asString() : "";
        if (subject.isBlank() || subject.length() > MAX_SUBJECT || email.isBlank() || email.length() > MAX_EMAIL
                || email.indexOf('@') < 1) {
            throw refused("identity");
        }
        return new GoogleIdentity(subject, email);
    }

    private boolean audienceIsThisClient(JsonNode claims) {
        JsonNode aud = claims.path("aud");
        if (aud.isString()) {
            return clientId.equals(aud.asString());
        }
        if (!aud.isArray() || aud.isEmpty()) {
            return false;
        }
        boolean listed = aud.valueStream().anyMatch(entry -> entry.isString() && clientId.equals(entry.asString()));
        boolean single = aud.size() == 1;
        return listed && (single || (claims.path("azp").isString() && clientId.equals(claims.path("azp").asString())));
    }

    /** The refusal; the log line names the failed check only. */
    private static ApiException refused(String check) {
        log.atWarn().addKeyValue("check", check).log("google.identity_refused");
        return new ApiException(AccountProblems.SIGN_IN_REFUSED);
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override
    public String toString() {
        return "GoogleOidcClient[hidden]";
    }
}
