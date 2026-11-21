package io.github.gmshield.materials.service;

import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;

/**
 * The answers of the review actions (Story 1.20): accept, reprocess, retry and discard on a material's newest version;
 * from Story 3.3, those of a change of where a material belongs. Params carry nothing.
 */
public final class MaterialProblems {

    public static final String CATEGORY = "material";
    static final ApiProblem ACTION_UNAVAILABLE = ApiProblem.of(409, CATEGORY, "action_unavailable",
            "Action unavailable");
    static final ApiProblem TOO_MANY_STEPS = ApiProblem.of(409, "ingestion", "too_many_steps", "Too many steps");
    /**
     * Story 3.3: a change of where a material belongs that names both a campaign and systems, neither, no system, more
     * than {@value Uploads#MAX_SYSTEMS}, or a target that is not one of the owner's ready campaigns and systems.
     */
    static final ApiProblem BELONGING_INVALID = ApiProblem.of(400, CATEGORY, "belonging_invalid",
            "Material belonging invalid");
    /** Story 3.3: the change names a version the material has left, as the library's edits answer it. */
    static final ApiProblem STALE_VERSION = ApiProblem.of(409, ApiProblem.CATEGORY_REQUEST, "stale_version",
            "Stale version");

    private MaterialProblems() {
    }

    /** The action is not one the version's state allows now. */
    static ApiException actionUnavailable() {
        return new ApiException(ACTION_UNAVAILABLE);
    }

    /** Where the material should belong is not valid (Story 3.3); it never says why. */
    static ApiException belongingInvalid() {
        return new ApiException(BELONGING_INVALID);
    }

    /** The steps the action would add take the version past its step cap; nothing changed. */
    static ApiException tooManySteps() {
        return new ApiException(TOO_MANY_STEPS);
    }
}
