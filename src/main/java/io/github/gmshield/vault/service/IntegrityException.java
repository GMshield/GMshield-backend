package io.github.gmshield.vault.service;

import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;

/**
 * A stored value failed its check: changed, truncated, moved to another binding, or written under
 * an unknown version or key. Answered as the problem {@code integrity.failed} with no params, so
 * nothing about the value leaves the server. Its message is the code only.
 */
public class IntegrityException extends ApiException {

    private static final long serialVersionUID = 1L;

    public static final ApiProblem PROBLEM = ApiProblem.of(500, "integrity", "failed", "Integrity check failed");

    IntegrityException() {
        super(PROBLEM);
    }
}
