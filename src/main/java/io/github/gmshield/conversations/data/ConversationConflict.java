package io.github.gmshield.conversations.data;

/**
 * A rename of a conversation (Story 3.10) that names a version the row has left: someone renamed it meanwhile. Carries
 * no content.
 */
public final class ConversationConflict extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConversationConflict() {
        super("conversations.stale_version", null, false, false);
    }
}
