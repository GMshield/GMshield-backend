package io.github.gmshield.accounts.service;

import io.github.gmshield.shared.service.ApiProblem;
import java.util.Map;

/** The problems of sign-in and sessions. None carries content: the params are the username's limits only. */
public final class AccountProblems {

    public static final String CATEGORY_SESSION = "session";

    /** No attempt, a wrong state, an expired or reused attempt: nothing is created. */
    public static final ApiProblem SIGN_IN_EXPIRED =
            ApiProblem.of(400, ApiProblem.CATEGORY_REQUEST, "sign_in_expired", "Sign-in attempt expired");
    /** Google answered with an error, the exchange failed, or the identity is not acceptable. */
    public static final ApiProblem SIGN_IN_REFUSED =
            ApiProblem.of(400, ApiProblem.CATEGORY_REQUEST, "sign_in_refused", "Sign-in refused");
    public static final ApiProblem USERNAME_TAKEN =
            ApiProblem.of(409, ApiProblem.CATEGORY_REQUEST, "username_taken", "Username taken");
    public static final ApiProblem USERNAME_INVALID =
            ApiProblem.of(400, ApiProblem.CATEGORY_REQUEST, "username_invalid", "Username invalid")
                    .withParams(Map.<String, Object>of("min", Usernames.MIN_LENGTH, "max", Usernames.MAX_LENGTH));
    public static final ApiProblem TERMS_REQUIRED =
            ApiProblem.of(400, ApiProblem.CATEGORY_REQUEST, "terms_required", "Terms not accepted");
    /** No session, an unknown or ended one, or an account that is no longer active. */
    public static final ApiProblem SESSION_REQUIRED =
            ApiProblem.of(401, CATEGORY_SESSION, "required", "Session required");

    private AccountProblems() {
    }
}
