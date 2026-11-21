package io.github.gmshield.library.data;

import java.util.List;
import java.util.UUID;

/**
 * {@code campaign_fingerprint_backfill} (V23, Story 3.2): the owners whose campaigns from before V23 may still lack a
 * name fingerprint. Not a library table: owner ids only, no content. {@link #owners} alone reads across owners, for the
 * fill at start, which then works as each owner; {@link #done} removes an owner once its campaigns are filled.
 */
public interface FingerprintBackfill {

    /** At most {@code limit} owners still listed, in id order. */
    List<UUID> owners(int limit);

    /** The owner's campaigns are filled: it leaves the list. */
    void done(UUID ownerId);
}
