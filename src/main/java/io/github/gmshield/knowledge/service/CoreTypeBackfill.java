package io.github.gmshield.knowledge.service;

import io.github.gmshield.knowledge.data.MissingCoreTypes;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Gives the systems from before V34 their core record types (Story 4.1), once the application is ready: each ready
 * system that lacks one, in id order, as its owner, {@link Ontology#fillCore}. Idempotent: a system that has them all is
 * not listed, a fill adds only the missing keys, and a second backend filling at once is refused by the key on
 * (owner, system, core), so that system is filled at the next start. A system whose fill failed is passed over until the
 * next start. Never throws, so it never blocks the start. Switched off by
 * {@code gmshield.knowledge.core-backfill.enabled=false} ({@code application-unit.yaml}: the unit tests have no database
 * and call {@link #run} themselves). Logs carry ids and counts only.
 */
@Service
@ConditionalOnProperty(name = "gmshield.knowledge.core-backfill.enabled", havingValue = "true", matchIfMissing = true)
public class CoreTypeBackfill {

    static final int BATCH = 100;
    private static final Logger log = LoggerFactory.getLogger(CoreTypeBackfill.class);

    private final MissingCoreTypes missing;
    private final Ontology ontology;

    CoreTypeBackfill(MissingCoreTypes missing, Ontology ontology) {
        this.missing = missing;
        this.ontology = ontology;
    }

    @EventListener(ApplicationReadyEvent.class)
    void atStart() {
        run();
    }

    /**
     * One fill of every system still lacking a core type; answers how many systems it added core types to (a system
     * that went deleting or was filled meanwhile counts for nothing). Never throws.
     */
    public int run() {
        int filled = 0;
        int failed = 0;
        try {
            UUID after = null;
            while (true) {
                List<MissingCoreTypes.SystemRef> systems = missing.systems(after, CoreRecordTypes.KEYS.size(), BATCH);
                if (systems.isEmpty()) {
                    break;
                }
                for (MissingCoreTypes.SystemRef system : systems) {
                    int added = fill(system);
                    if (added > 0) {
                        filled++;
                    } else if (added < 0) {
                        failed++;
                    }
                }
                after = systems.getLast().systemId();
            }
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("errorType", failure.getClass().getName()).log("knowledge.core_types_deferred");
        }
        if (filled > 0 || failed > 0) {
            log.atInfo().addKeyValue("systems", filled).addKeyValue("failed", failed)
                    .log("knowledge.core_types_backfilled");
        }
        return filled;
    }

    /** How many core types the fill added to the system, or -1 when it failed. */
    private int fill(MissingCoreTypes.SystemRef system) {
        try {
            return OwnerContext.callAs(system.ownerId(), () -> ontology.fillCore(system.systemId()));
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("ownerId", system.ownerId()).addKeyValue("systemId", system.systemId())
                    .addKeyValue("errorType", failure.getClass().getName()).log("knowledge.core_types_failed");
            return -1;
        }
    }
}
