package io.github.gmshield.conversations.service;

import io.github.gmshield.conversations.data.TurnRow;
import java.util.Arrays;
import java.util.Optional;

/**
 * What the GM asked for in a turn (Story 5.3 part A, public contract 1.46.0): a question, answered by a
 * {@link AnswerJobs} job, or a draft, written by a {@link DraftJobs} job from the same sources. A draft is asked only in
 * the {@link Area#QUESTIONS questions} area.
 */
public enum TurnKind {
    QUESTION(TurnRow.QUESTION, AnswerJobs.KIND),
    DRAFT(TurnRow.DRAFT, DraftJobs.KIND);

    private final String word;
    private final String jobKind;

    TurnKind(String word, String jobKind) {
        this.word = word;
        this.jobKind = jobKind;
    }

    /** Its word, as the API and {@code turn.kind} have it. */
    public String word() {
        return word;
    }

    /** The kind of the job that answers a turn of this kind. */
    String jobKind() {
        return jobKind;
    }

    /** The kind with this word; empty for any other. */
    public static Optional<TurnKind> of(String word) {
        return Arrays.stream(values()).filter(kind -> kind.word.equals(word)).findFirst();
    }
}
