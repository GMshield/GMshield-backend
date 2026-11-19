package io.github.gmshield.jobs.web;

import io.github.gmshield.jobs.service.JobClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The worker states its index recipe outside a claim (contract 1.12.0, AD-10): at its start and at every check of the
 * backend, behind the worker's credential like all of {@code /internal/v1}, with no grant. The backend reacts exactly
 * as to a recipe stated at a claim ({@link JobClaims#stateRecipe}), so a deploy starts indexing within one check.
 */
@RestController
@Tag(name = "jobs")
class IndexRecipeController {

    private final JobClaims claims;

    IndexRecipeController(JobClaims claims) {
        this.claims = claims;
    }

    @Schema(name = "IndexRecipeStatement", requiredProperties = "indexRecipe",
            description = "The worker's index recipe, stated outside a claim (contract 1.12.0)")
    record IndexRecipeRequest(
            @Schema(description = "The worker's index recipe (AD-10): when it differs from the last one stated, the"
                    + " backend re-indexes every ready version recorded under another one", pattern = "^[A-Za-z0-9._-]{1,32}$",
                    maxLength = 32) String indexRecipe) {
    }

    @PutMapping(path = "/internal/v1/index-recipe", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "stateIndexRecipe", summary = "State the worker's index recipe, as a claim does (400"
            + " request.invalid for a recipe that is not one)")
    void state(@RequestBody IndexRecipeRequest body) {
        claims.stateRecipe(body.indexRecipe());
    }
}
