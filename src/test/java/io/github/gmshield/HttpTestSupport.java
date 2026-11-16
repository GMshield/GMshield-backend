package io.github.gmshield;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts the whole application on a random port, without Postgres or Redis (profile {@code unit}),
 * and talks to it over real HTTP, so that the server's own error handling is part of what is tested.
 * {@link #get} and {@link #send} carry the frontend's service credential, as the frontend does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("unit")
public abstract class HttpTestSupport {

    protected static final JsonMapper JSON = JsonMapper.builder().build();
    /** The service credential of application-unit.yaml (test mode's fixed one). */
    protected static final String CREDENTIAL = "gmshield-test-mode-frontend-credential-not-a-secret";

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    protected HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
        return getWithout(path, concat(headers, "Authorization", "Bearer " + CREDENTIAL));
    }

    /** A GET with exactly the given headers: no service credential unless one of them is. */
    protected HttpResponse<String> getWithout(String path, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (headers.length > 0) {
            request.headers(headers);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> send(String method, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + CREDENTIAL)
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String[] concat(String[] headers, String... more) {
        String[] all = java.util.Arrays.copyOf(headers, headers.length + more.length);
        System.arraycopy(more, 0, all, headers.length, more.length);
        return all;
    }

    /** Sends bytes as they are, for requests that no HTTP client would agree to send. */
    protected String raw(String request) throws IOException {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    protected static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }
}
