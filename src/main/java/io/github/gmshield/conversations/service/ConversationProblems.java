package io.github.gmshield.conversations.service;

import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;

/** The answers of the conversation routes (Story 2.4). Params carry nothing. */
public final class ConversationProblems {

    private ConversationProblems() {
    }

    /** A question that is blank or longer than the limit: nothing recorded. */
    static ApiException invalid() {
        return new ApiException(ApiProblem.forStatus(400));
    }

    /** A campaign or a turn that is not the owner's, unknown or malformed: the same answer. */
    public static ApiException notFound() {
        return new ApiException(ApiProblem.forStatus(404));
    }
}
