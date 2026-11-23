package io.github.gmshield.jobs.service;

import java.util.Set;
import java.util.UUID;

/**
 * One answer event the worker posts in a batch (Story 2.4, internal contract 1.10.0): a {@code delta} with its display
 * text, a {@code citation} of a passage id at a code-point offset of the answer's text, or (Story 3.7, contract 1.14.0)
 * a {@code tier} marker: a tier's version of the answer starts at that offset; from Story 3.8 (contract 1.15.0) the
 * tier word {@code model} marks a part from the model's own knowledge. The text is library-derived:
 * {@code toString} never names it.
 */
public record JobEvent(String type, String text, UUID passageId, Integer offset, String tier) {

    public static final String DELTA = "delta";
    public static final String CITATION = "citation";
    public static final String TIER = "tier";
    /** The tier word of a part from the model's own knowledge (Story 3.8): never a passage's tier. */
    public static final String MODEL = "model";
    /**
     * The tier words a {@code tier} event may carry: a passage's tier as the internal API writes it, or
     * {@value #MODEL}.
     */
    public static final Set<String> TIERS = Set.of("campaign", "system", "other", MODEL);

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
