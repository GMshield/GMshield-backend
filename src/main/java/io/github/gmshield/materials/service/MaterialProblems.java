package io.github.gmshield.materials.service;

import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;

/**
 * The answers of the review actions (Story 1.20): accept, reprocess, retry and discard on a material's current version;
 * from Story 3.3, those of a change of where a material belongs; from Story 3.4, those of a new version (a replacement
 * upload or a reprocess of an accepted version); from Story 3.4 part B, the removal of a version that is not
 * superseded. Params carry nothing.
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

    /** Story 3.4: a new version is refused while another version of the material is still on its way. */
    static final ApiProblem VERSION_IN_PROGRESS = ApiProblem.of(409, CATEGORY, "version_in_progress",
            "Material version in progress");
    /** Story 3.4: a new version is refused when the material has no ready version (every version discarded). */
    static final ApiProblem NOT_READY = ApiProblem.of(409, CATEGORY, "not_ready", "Material not ready");

    /** Story 3.4 part B: only a superseded version is removed; a ready, in-progress or discarded one is not. */
    static final ApiProblem VERSION_NOT_REMOVABLE = ApiProblem.of(409, CATEGORY, "version_not_removable",
            "Material version not removable");

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

    /** Another version of the material is uploading, waiting, processing, needs review or failed (Story 3.4). */
    static ApiException versionInProgress() {
        return new ApiException(VERSION_IN_PROGRESS);
    }

    /** The material has no ready version to replace or read again (Story 3.4). */
    static ApiException notReady() {
        return new ApiException(NOT_READY);
    }

    /** The version is not superseded, and its removal is not recorded (Story 3.4 part B). */
    static ApiException versionNotRemovable() {
        return new ApiException(VERSION_NOT_REMOVABLE);
    }

    /** The steps the action would add take the version past its step cap; nothing changed. */
    static ApiException tooManySteps() {
        return new ApiException(TOO_MANY_STEPS);
    }
}
