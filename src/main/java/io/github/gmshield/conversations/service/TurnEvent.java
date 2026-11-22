package io.github.gmshield.conversations.service;

import java.util.UUID;

/**
 * One event of a turn's answer as the GM's stream sends it (Story 2.4): its sequence (1, 2, … per turn) and its kind,
 * {@code delta} with its text, {@code citation} with a passage id and an offset, {@code tier} with a tier word and an
 * offset (Story 3.7: that tier's version of the answer starts there), {@code done} ({@code stopped} when the GM stopped
 * the answer, Story 2.6), or {@code error} with a code. {@code toString} never names the text.
 */
public record TurnEvent(long sequence, String type, String text, UUID passageId, Integer offset, String code,
                        boolean stopped, String tier) {

    public static final String DELTA = "delta";
    public static final String CITATION = "citation";
    public static final String TIER = "tier";
    public static final String DONE = "done";
    public static final String ERROR = "error";

    /** Whether the answer ends with this event. */
    public boolean last() {
        return DONE.equals(type) || ERROR.equals(type);
    }

    TurnEvent at(long newSequence) {
        return new TurnEvent(newSequence, type, text, passageId, offset, code, stopped, tier);
    }

    @Override
    public String toString() {
        return "TurnEvent[sequence=" + sequence + ", type=" + type + "]";
    }
}
