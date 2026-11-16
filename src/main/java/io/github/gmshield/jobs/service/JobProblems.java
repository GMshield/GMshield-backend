package io.github.gmshield.jobs.service;

import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;

/** The internal API's answers about jobs. */
public final class JobProblems {

    public static final String CATEGORY = "job";
    static final ApiProblem CLAIMED = ApiProblem.of(409, CATEGORY, "claimed", "Job already claimed");
    static final ApiProblem NOT_OFFERED = ApiProblem.of(409, CATEGORY, "not_offered", "Job not offered");
    static final ApiProblem GRANT_REFUSED = ApiProblem.of(403, CATEGORY, "grant_refused", "Job grant refused");
    /** The code of a job whose attempts all lapsed. */
    public static final String ATTEMPTS_EXHAUSTED = "job.attempts_exhausted";
    /** The code of a failed outcome that named none. */
    public static final String FAILED = "job.failed";
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

    static ApiException notFound() {
        return new ApiException(ApiProblem.forStatus(404));
    }

    static ApiException invalid() {
        return new ApiException(ApiProblem.forStatus(400));
    }
}
