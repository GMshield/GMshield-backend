package io.github.gmshield.conversations.service;

import io.github.gmshield.conversations.data.ConversationRow;
import java.util.Arrays;
import java.util.Optional;

/**
 * The area of the sidebar a conversation belongs to (Story 3.10): {@code questions} (Consultas) or {@code rules}
 * (Criação de Regras). It is fixed when the conversation is made by its first question.
 */
public enum Area {
    QUESTIONS(ConversationRow.QUESTIONS),
    RULES(ConversationRow.RULES);

    private final String word;

    Area(String word) {
        this.word = word;
    }

    /** The word stored and sent for it. */
    public String word() {
        return word;
    }

    /** The area this word names; empty for anything else (case matters). */
    public static Optional<Area> of(String word) {
        return Arrays.stream(values()).filter(area -> area.word.equals(word)).findFirst();
    }
}
