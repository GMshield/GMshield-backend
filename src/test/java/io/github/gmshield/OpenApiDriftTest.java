package io.github.gmshield;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fails the build when a generated OpenAPI document differs from its committed file, byte for byte.
 * After an intended change run {@code ./gradlew test -PupdateOpenApi}, raise the version in
 * {@code ContractVersions}, and commit the rewritten file.
 */
class OpenApiDriftTest extends HttpTestSupport {

    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    @ParameterizedTest
    @ValueSource(strings = {"public", "internal"})
    void generatedDocumentEqualsTheCommittedOne(String api) throws Exception {
        HttpResponse<String> response = get("/v3/api-docs/" + api);
        assertThat(response.statusCode()).isEqualTo(200);
        String generated = canonical(response.body());

        Path produced = Path.of("build", "openapi", api + ".json");
        Files.createDirectories(produced.getParent());
        Files.writeString(produced, generated, StandardCharsets.UTF_8);

        Path committed = Path.of("openapi", api + ".json");
        if (Boolean.getBoolean("gmshield.openapi.update")) {
            Files.createDirectories(committed.getParent());
            Files.writeString(committed, generated, StandardCharsets.UTF_8);
            return;
        }
        assertThat(committed).as("%s is missing: run ./gradlew test -PupdateOpenApi", committed).exists();
        assertThat(Files.readString(committed, StandardCharsets.UTF_8))
                .as("%s differs from the generated document (%s): run ./gradlew test -PupdateOpenApi and commit it",
                        committed, produced)
                .isEqualTo(generated);
    }

    /** Sorted keys, fixed indentation, Unix line ends, one final line end. */
    private static String canonical(String json) {
        Object tree = CANONICAL.readValue(json, Object.class);
        return CANONICAL.writeValueAsString(tree).replace("\r\n", "\n") + "\n";
    }
}
