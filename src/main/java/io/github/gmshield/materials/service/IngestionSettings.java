package io.github.gmshield.materials.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The ingestion chain's limit (Story 1.15), a Spring property that the environment overrides
 * ({@code application.yaml}): a version may have at most {@code steps-per-page} x its page total + 1 steps
 * ({@code GMSHIELD_INGESTION_STEPS_PER_PAGE}, default 4). A start with a value outside 1 to 100 is refused, naming the
 * setting only.
 */
@Component
public class IngestionSettings {

    static final int MAX_STEPS_PER_PAGE = 100;

    private final int stepsPerPage;

    IngestionSettings(@Value("${gmshield.ingestion.steps-per-page}") int stepsPerPage) {
        if (stepsPerPage < 1 || stepsPerPage > MAX_STEPS_PER_PAGE) {
            throw new IllegalStateException("setting.invalid: GMSHIELD_INGESTION_STEPS_PER_PAGE");
        }
        this.stepsPerPage = stepsPerPage;
    }

    public int stepsPerPage() {
        return stepsPerPage;
    }

    /** The most steps a version of {@code pageTotal} pages may have: steps-per-page x pages + 1. */
    public long stepCap(int pageTotal) {
        return (long) stepsPerPage * pageTotal + 1;
    }
}
