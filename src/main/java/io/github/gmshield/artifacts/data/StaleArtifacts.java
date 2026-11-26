package io.github.gmshield.artifacts.data;

import java.util.List;
import java.util.UUID;

/**
 * The owners with an artifact not indexed under a recipe (Story 5.1 part B): as the materials' {@code StaleIndexes},
 * a read of library tables across owners, for the backfill a recipe stated at a claim starts, which then visits each
 * owner as that owner. It answers ids alone, never content, and changes nothing.
 */
public interface StaleArtifacts {

    /** The owners with a newest artifact version that no {@code artifact_index} row reports under {@code recipe}. */
    List<UUID> owners(String recipe);
}
