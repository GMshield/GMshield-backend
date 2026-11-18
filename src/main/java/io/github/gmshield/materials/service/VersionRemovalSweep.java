package io.github.gmshield.materials.service;

import io.github.gmshield.materials.data.PendingRemovals;
import io.github.gmshield.shared.service.OwnerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Finishes the removals of discarded versions (Story 1.20, AD-22): every
 * {@code gmshield.materials.version-removal-sweep-every} ({@link VersionRemovalSweepSchedule}), each version whose
 * removal is still pending (the backend stopped, or a part failed) is removed again by {@link VersionRemoval}, as its
 * owner ({@link OwnerContext#callAs}), in transactions of its own. The list of pending versions is read without an
 * owner ({@link PendingRemovals}): ids only. One failed version never stops the others; it is logged by error type and
 * tried again next time.
 */
@Service
public class VersionRemovalSweep {

    static final int BATCH = 100;
    private static final Logger log = LoggerFactory.getLogger(VersionRemovalSweep.class);

    private final PendingRemovals pending;
    private final VersionRemoval removal;

    VersionRemovalSweep(PendingRemovals pending, VersionRemoval removal) {
        this.pending = pending;
        this.removal = removal;
    }

    /** One sweep: how many versions it finished removing. Never throws. */
    public int sweep() {
        int removed = 0;
        try {
            for (PendingRemovals.Pending version : pending.list(BATCH)) {
                removed += finish(version) ? 1 : 0;
            }
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("errorType", failure.getClass().getName()).log("material.removal_sweep_failed");
        }
        return removed;
    }

    private boolean finish(PendingRemovals.Pending version) {
        try {
            return OwnerContext.callAs(version.ownerId(), () -> removal.remove(version.versionId()));
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("versionId", version.versionId())
                    .addKeyValue("errorType", failure.getClass().getName()).log("material.removal_failed");
            return false;
        }
    }
}
