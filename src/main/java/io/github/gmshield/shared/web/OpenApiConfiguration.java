package io.github.gmshield.shared.web;

import io.github.gmshield.shared.service.ContractVersions;
import io.github.gmshield.shared.service.ProblemBody;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two contract documents: {@code /v3/api-docs/public} for {@code /api/v1} and
 * {@code /v3/api-docs/internal} for {@code /internal/v1}. Each is committed under
 * {@code openapi/}, and the build fails when the generated one differs.
 */
@Configuration
public class OpenApiConfiguration {

    static final String PROBLEM_SCHEMA = "Problem";

    /** A fixed server entry: without it the document would name the host of whoever asked. */
    @Bean
    OpenAPI baseOpenApi() {
        return new OpenAPI().servers(List.of(new Server().url("/")));
    }

    @Bean
    GroupedOpenApi publicApi() {
        return group("public", "/api/v1/**", "GMshield public API", ContractVersions.PUBLIC_API);
    }

    @Bean
    GroupedOpenApi internalApi() {
        return group("internal", "/internal/v1/**", "GMshield internal API", ContractVersions.INTERNAL_API);
    }

    private static GroupedOpenApi group(String name, String paths, String title, String version) {
        OpenApiCustomizer contract = api -> {
            api.info(new Info().title(title).version(version));
            api.schema(PROBLEM_SCHEMA, problemSchema());
            if (api.getPaths() != null) {
                api.getPaths().values().forEach(path -> path.readOperations().forEach(operation ->
                        operation.getResponses().addApiResponse("default", problemResponse())));
            }
        };
        return GroupedOpenApi.builder().group(name).pathsToMatch(paths).addOpenApiCustomizer(contract).build();
    }

    private static Schema<Object> problemSchema() {
        Schema<Object> problem = new ObjectSchema();
        problem.description("Every error of this API (RFC 9457 problem detail).");
        problem.addProperty("type", new StringSchema().description("urn:gmshield:problem: followed by the code"));
        problem.addProperty("title", new StringSchema());
        problem.addProperty("status", new IntegerSchema());
        problem.addProperty("category", new StringSchema());
        problem.addProperty("code", new StringSchema().description("category.reason"));
        problem.addProperty("params", new ObjectSchema().additionalProperties(true));
        problem.addProperty("traceId", new StringSchema());
        problem.required(List.of("type", "title", "status", "category", "code", "params", "traceId"));
        return problem;
    }

    private static ApiResponse problemResponse() {
        return new ApiResponse()
                .description("Any error")
                .content(new Content().addMediaType(ProblemBody.MEDIA_TYPE,
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA))));
    }
}
