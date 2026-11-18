package io.github.gmshield.materials.data;

import java.util.List;
import java.util.UUID;

/**
 * The owners whose {@code ready} versions were not all indexed under a recipe (Story 2.1, AD-10): with the removal
 * sweep's list ({@link PendingRemovals}), the other read of a library table that has no owner, for the reconcile that
 * a recipe stated at a claim starts, which then visits each owner as that owner. It answers ids alone, never content,
 * and changes nothing.
 */
public interface StaleIndexes {

    /** The owners with a {@code ready} version whose index recipe is not {@code recipe} (null counts as not). */
    List<UUID> owners(String recipe);
}
