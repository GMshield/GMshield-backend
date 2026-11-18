package io.github.gmshield.materials.service;

import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;

/**
 * The answers of the review actions (Story 1.20): accept, reprocess, retry and discard on a material's newest version.
 * Params carry nothing.
 */
public final class MaterialProblems {

    public static final String CATEGORY = "material";
    static final ApiProblem ACTION_UNAVAILABLE = ApiProblem.of(409, CATEGORY, "action_unavailable",
            "Action unavailable");
    static final ApiProblem TOO_MANY_STEPS = ApiProblem.of(409, "ingestion", "too_many_steps", "Too many steps");

    private MaterialProblems() {
    }

    /** The action is not one the version's state allows now. */
    static ApiException actionUnavailable() {
        return new ApiException(ACTION_UNAVAILABLE);
    }

    /** The steps the action would add take the version past its step cap; nothing changed. */
    static ApiException tooManySteps() {
        return new ApiException(TOO_MANY_STEPS);
    }
}
