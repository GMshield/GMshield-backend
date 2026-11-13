package io.github.gmshield.shared.service;

/**
 * Thrown by any module to answer a request with a known problem. Its message is the code only,
 * so that logging or printing it can never reveal content.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ApiProblem problem;

    public ApiException(ApiProblem problem) {
        super(problem.code());
        this.problem = problem;
    }

    public ApiProblem problem() {
        return problem;
    }
}
