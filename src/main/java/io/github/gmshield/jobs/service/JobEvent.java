package io.github.gmshield.jobs.service;

import java.util.Set;
import java.util.UUID;

/**
 * One answer event the worker posts in a batch (Story 2.4, internal contract 1.10.0): a {@code delta} with its display
 * text, a {@code citation} of a passage id at a code-point offset of the answer's text, or (Story 3.7, contract 1.14.0)
 * a {@code tier} marker: a tier's version of the answer starts at that offset. The text is library-derived:
 * {@code toString} never names it.
 */
public record JobEvent(String type, String text, UUID passageId, Integer offset, String tier) {

    public static final String DELTA = "delta";
    public static final String CITATION = "citation";
    public static final String TIER = "tier";
    /** The tier words a {@code tier} event may carry, as the internal API writes a passage's tier. */
    public static final Set<String> TIERS = Set.of("campaign", "system", "other");

    public static JobEvent delta(String text) {
        return new JobEvent(DELTA, text, null, null, null);
    }

    public static JobEvent citation(UUID passageId, int offset) {
        return new JobEvent(CITATION, null, passageId, offset, null);
    }

    public static JobEvent tier(String tier, int offset) {
        return new JobEvent(TIER, null, null, offset, tier);
    }

    @Override
    public String toString() {
        return "JobEvent[type=" + type + "]";
    }
}
