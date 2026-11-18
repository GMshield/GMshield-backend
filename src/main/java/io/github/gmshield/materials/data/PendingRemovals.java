package io.github.gmshield.materials.data;

import java.util.List;
import java.util.UUID;

/**
 * The material versions of every owner whose removal is pending (Story 1.20, AD-22): the one read of a library table
 * that has no owner, for the removal sweep, which visits each version as its owner. It answers ids alone, never
 * content, and changes nothing: every change is made through {@link MaterialStore} as the version's owner.
 */
public interface PendingRemovals {

    /** One version whose removal is pending, and its owner. */
    record Pending(UUID ownerId, UUID versionId) {
    }

    /** At most {@code limit} versions whose removal is pending, the longest unchanged first. */
    List<Pending> list(int limit);
}
