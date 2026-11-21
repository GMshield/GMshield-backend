package io.github.gmshield.library.service;

import io.github.gmshield.library.data.FingerprintBackfill;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Fills the name fingerprint of the campaigns from before V23 (Story 3.2), once the application is ready: for each owner
 * still listed in {@code campaign_fingerprint_backfill}, as that owner, {@link Library#fillCampaignFingerprints}, then
 * the owner leaves the list. Repeatable: a start with nobody listed does nothing, and an owner whose fill failed stays for
 * the next start. Never throws. Switched off by {@code gmshield.library.campaign-fingerprints.enabled=false}
 * ({@code application-unit.yaml}: the unit tests have no database and call {@link #run} themselves). Logs carry ids and
 * counts only.
 */
@Service
@ConditionalOnProperty(name = "gmshield.library.campaign-fingerprints.enabled", havingValue = "true", matchIfMissing = true)
public class CampaignFingerprints {

    static final int BATCH = 100;
    private static final Logger log = LoggerFactory.getLogger(CampaignFingerprints.class);

    private final FingerprintBackfill backfill;
    private final Library library;

    CampaignFingerprints(FingerprintBackfill backfill, Library library) {
        this.backfill = backfill;
        this.library = library;
    }

    @EventListener(ApplicationReadyEvent.class)
    void atStart() {
        run();
    }

    /** One fill of every owner still listed; answers how many owners it finished. Never throws. */
    public int run() {
        int finished = 0;
        Set<UUID> failed = new HashSet<>();
        try {
            while (true) {
                List<UUID> owners = backfill.owners(BATCH + failed.size()).stream()
                        .filter(owner -> !failed.contains(owner)).toList();
                if (owners.isEmpty()) {
                    break;
                }
                for (UUID owner : owners) {
                    if (fill(owner)) {
                        finished++;
                    } else {
                        failed.add(owner);
                    }
                }
            }
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("errorType", failure.getClass().getName()).log("library.campaign_fingerprints_deferred");
        }
        if (finished > 0 || !failed.isEmpty()) {
            log.atInfo().addKeyValue("owners", finished).addKeyValue("failed", failed.size())
                    .log("library.campaign_fingerprints_backfilled");
        }
        return finished;
    }

    private boolean fill(UUID owner) {
        try {
            OwnerContext.runAs(owner, library::fillCampaignFingerprints);
            backfill.done(owner);
            return true;
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("ownerId", owner).addKeyValue("errorType", failure.getClass().getName())
                    .log("library.campaign_fingerprints_failed");
            return false;
        }
    }
}
