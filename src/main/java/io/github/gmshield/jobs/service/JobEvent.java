package io.github.gmshield.jobs.service;

import java.util.UUID;

/**
 * One answer event the worker posts in a batch (Story 2.4, internal contract 1.10.0): a {@code delta} with its display
 * text, or a {@code citation} of a passage id at a code-point offset of the answer's text. The text is library-derived:
 * {@code toString} never names it.
 */
public record JobEvent(String type, String text, UUID passageId, Integer offset) {

    public static final String DELTA = "delta";
    public static final String CITATION = "citation";

    public static JobEvent delta(String text) {
        return new JobEvent(DELTA, text, null, null);
    }

    public static JobEvent citation(UUID passageId, int offset) {
        return new JobEvent(CITATION, null, passageId, offset);
    }

    @Override
    public String toString() {
        return "JobEvent[type=" + type + "]";
    }
}
