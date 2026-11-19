package io.github.gmshield.jobs.service;

import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;

/** The internal API's answers about jobs. */
public final class JobProblems {

    public static final String CATEGORY = "job";
    static final ApiProblem CLAIMED = ApiProblem.of(409, CATEGORY, "claimed", "Job already claimed");
    static final ApiProblem NOT_OFFERED = ApiProblem.of(409, CATEGORY, "not_offered", "Job not offered");
    static final ApiProblem GRANT_REFUSED = ApiProblem.of(403, CATEGORY, "grant_refused", "Job grant refused");
    static final ApiProblem OUTPUT_TOO_LARGE = ApiProblem.of(413, CATEGORY, "output_too_large", "Job output too large");
    static final ApiProblem RESULT_INVALID = ApiProblem.of(400, CATEGORY, "result_invalid", "Job result invalid");
    static final ApiProblem PASSAGES_INVALID = ApiProblem.of(400, CATEGORY, "passages_invalid",
            "Job passages request invalid");
    static final ApiProblem PASSAGES_REFUSED = ApiProblem.of(403, CATEGORY, "passages_refused",
            "Job passages refused");
    /** The code of a job whose attempts all lapsed. */
    public static final String ATTEMPTS_EXHAUSTED = "job.attempts_exhausted";
    /** The code of a failed outcome that named none. */
    public static final String FAILED = "job.failed";
    /** The code of a job whose handler could not give its input (it failed, or it was not a JSON object). */
    public static final String INPUT_FAILED = "job.input_failed";
    /**
     * The code of a job whose kind calls a model through a provider the claiming worker holds no key for (Story 2.10):
     * no attempt is used, and no other worker would hold another key.
     */
    public static final String PROVIDER_UNAVAILABLE = "job.provider_unavailable";
    /** The code of a job whose kind no handler of this backend knows. */
    public static final String UNKNOWN_KIND = "job.unknown_kind";

    private JobProblems() {
    }

    static ApiException claimed() {
        return new ApiException(CLAIMED);
    }

    static ApiException notOffered() {
        return new ApiException(NOT_OFFERED);
    }

    static ApiException grantRefused() {
        return new ApiException(GRANT_REFUSED);
    }

    static ApiException outputTooLarge(long limit) {
        return new ApiException(OUTPUT_TOO_LARGE.withParams(java.util.Map.of("limit", limit)));
    }

    static ApiException resultInvalid() {
        return new ApiException(RESULT_INVALID);
    }

    static ApiException passagesInvalid() {
        return new ApiException(PASSAGES_INVALID);
    }

    static ApiException passagesRefused() {
        return new ApiException(PASSAGES_REFUSED);
    }

    static ApiException notFound() {
        return new ApiException(ApiProblem.forStatus(404));
    }

    static ApiException invalid() {
        return new ApiException(ApiProblem.forStatus(400));
    }
}
