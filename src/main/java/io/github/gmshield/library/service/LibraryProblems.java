package io.github.gmshield.library.service;

import io.github.gmshield.shared.service.ApiProblem;
import java.util.Map;

/**
 * The problems of the GM's edits of the library (Stories 3.1, 3.2 and 6.1). None carries content: the params are a field's limits
 * only. Each invalid field has its own code, so the form shows the text under that field.
 */
final class LibraryProblems {

    static final ApiProblem SYSTEM_NAME_INVALID =
            ApiProblem.of(400, ApiProblem.CATEGORY_REQUEST, "system_name_invalid", "System name invalid")
                    .withParams(Map.<String, Object>of("min", 1, "max", Library.NAME_MAX));
    static final ApiProblem SYSTEM_EDITION_INVALID =
            ApiProblem.of(400, ApiProblem.CATEGORY_REQUEST, "system_edition_invalid", "System edition invalid")
                    .withParams(Map.<String, Object>of("max", Library.EDITION_MAX));
    static final ApiProblem SYSTEM_DESCRIPTION_INVALID =
            ApiProblem.of(400, ApiProblem.CATEGORY_REQUEST, "system_description_invalid", "System description invalid")
                    .withParams(Map.<String, Object>of("max", Library.DESCRIPTION_MAX));
    /** Another system of the owner has the same name and edition (their unique forms). */
    static final ApiProblem SYSTEM_NAME_TAKEN =
            ApiProblem.of(409, ApiProblem.CATEGORY_REQUEST, "system_name_taken", "System name taken");
    /** A campaign's name outside its limits (Story 3.2). */
    static final ApiProblem CAMPAIGN_NAME_INVALID =
            ApiProblem.of(400, ApiProblem.CATEGORY_REQUEST, "campaign_name_invalid", "Campaign name invalid")
                    .withParams(Map.<String, Object>of("min", 1, "max", Library.NAME_MAX));
    /** Another campaign of the system has the same name (its unique form, Story 3.2). */
    static final ApiProblem CAMPAIGN_NAME_TAKEN =
            ApiProblem.of(409, ApiProblem.CATEGORY_REQUEST, "campaign_name_taken", "Campaign name taken");
    /** A Shield's name outside its limits (Story 6.1). */
    static final ApiProblem SHIELD_NAME_INVALID =
            ApiProblem.of(400, ApiProblem.CATEGORY_REQUEST, "shield_name_invalid", "Shield name invalid")
                    .withParams(Map.<String, Object>of("min", 1, "max", Library.NAME_MAX));
    /** Another Shield of the campaign has the same name (its unique form, Story 6.1). */
    static final ApiProblem SHIELD_NAME_TAKEN =
            ApiProblem.of(409, ApiProblem.CATEGORY_REQUEST, "shield_name_taken", "Shield name taken");
    /** The Shield is its campaign's only one, which cannot be deleted (Story 6.1 part B). */
    static final ApiProblem LAST_SHIELD =
            ApiProblem.of(409, ApiProblem.CATEGORY_REQUEST, "last_shield", "Last shield");
    /** The edit names a version the item has left: someone changed it elsewhere. */
    static final ApiProblem STALE_VERSION =
            ApiProblem.of(409, ApiProblem.CATEGORY_REQUEST, "stale_version", "Stale version");

    private LibraryProblems() {
    }
}
