package io.github.gmshield.conversations.service;

import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import java.util.Map;

/** The answers of the conversation routes (Story 2.4). Params carry nothing but a field's limits. */
public final class ConversationProblems {

    /** A conversation's name outside its limits (Story 3.10): the form shows the text under the field. */
    static final ApiProblem NAME_INVALID =
            ApiProblem.of(400, ApiProblem.CATEGORY_REQUEST, "conversation_name_invalid", "Conversation name invalid")
                    .withParams(Map.<String, Object>of("min", 1, "max", Conversations.NAME_MAX));
    /** The rename names a version the conversation has left (Story 3.10): someone renamed it elsewhere. */
    static final ApiProblem STALE_VERSION =
            ApiProblem.of(409, ApiProblem.CATEGORY_REQUEST, "stale_version", "Stale version");

    /** A dismissal of a turn whose answer is still being written (Story 3.11): only an ended turn is dismissed. */
    static final ApiProblem TURN_RUNNING =
            ApiProblem.of(409, "conversation", "turn_running", "Turn running");

    /** A bookmark of a turn whose answer is not done (Story 3.12): running, stopped, failed or silent. */
    static final ApiProblem TURN_NOT_DONE =
            ApiProblem.of(409, "conversation", "turn_not_done", "Turn not done");

    private ConversationProblems() {
    }

    /** A question or a search that is blank, longer than the limit or malformed: nothing recorded. */
    public static ApiException invalid() {
        return new ApiException(ApiProblem.forStatus(400));
    }

    /** A campaign, a turn or a search that is not the owner's, unknown or malformed: the same answer. */
    public static ApiException notFound() {
        return new ApiException(ApiProblem.forStatus(404));
    }

    /** A conversation's name outside 1 to {@value Conversations#NAME_MAX} code points after trimming (Story 3.10). */
    public static ApiException nameInvalid() {
        return new ApiException(NAME_INVALID);
    }

    /** A dismissal of a turn that has not ended (Story 3.11). */
    public static ApiException turnRunning() {
        return new ApiException(TURN_RUNNING);
    }

    /** A bookmark of a turn whose answer is not done (Story 3.12). */
    public static ApiException turnNotDone() {
        return new ApiException(TURN_NOT_DONE);
    }

    /** A rename on a version the conversation has left (Story 3.10). */
    public static ApiException staleVersion() {
        return new ApiException(STALE_VERSION);
    }
}
