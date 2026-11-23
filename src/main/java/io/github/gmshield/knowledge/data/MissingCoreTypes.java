package io.github.gmshield.knowledge.data;

import java.util.List;
import java.util.UUID;

/**
 * The ready systems that still lack one of the eight core record types (Story 4.1: systems from before V34, or a fill
 * that failed), across owners, for the fill at start, which then works as each owner. Ids only, no content.
 */
public interface MissingCoreTypes {

    /** A system and its owner. */
    record SystemRef(UUID ownerId, UUID systemId) {
    }

    /** At most {@code limit} such systems whose id is above {@code after} (all when null), in id order. */
    List<SystemRef> systems(UUID after, int cores, int limit);
}
