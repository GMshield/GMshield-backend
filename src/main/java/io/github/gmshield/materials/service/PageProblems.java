package io.github.gmshield.materials.service;

import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;

/** The answers of the page routes (Story 1.19). Params carry nothing: a ground is a word the frontend sent. */
public final class PageProblems {

    public static final String CATEGORY = "page";
    static final ApiProblem GROUND_UNKNOWN = ApiProblem.of(400, CATEGORY, "ground_unknown", "Page ground unknown");

    private PageProblems() {
    }

    /** A missing ground, or one this release does not serve. */
    public static ApiException groundUnknown() {
        return new ApiException(GROUND_UNKNOWN);
    }
}
