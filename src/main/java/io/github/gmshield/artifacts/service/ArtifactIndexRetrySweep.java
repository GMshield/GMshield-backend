package io.github.gmshield.artifacts.service;

import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retries the artifacts' index jobs whose job failed (Story 5.1 part B), as the materials' {@code IndexRetrySweep} and
 * {@code IndexRemovalRetrySweep} do, on their schedule ({@link ArtifactIndexRetrySweepSchedule}): each owner with a
 * failed {@code index.artifact} or {@code index.artifact_removal} job ({@link Jobs#ownersWithFailed}: ids only) is
 * visited as that owner, in a transaction of its own; each failed index row still to retry gets a new job
 * ({@link ArtifactIndexing#retryFailed}), and each failed removal too ({@link ArtifactIndexRemovals#retryFailed}). One
 * owner's failure never stops the others; it is logged by error type and tried again next time.
 */
@Service
public class ArtifactIndexRetrySweep {

    private static final Logger log = LoggerFactory.getLogger(ArtifactIndexRetrySweep.class);

    private final Jobs jobs;
    private final ArtifactIndexing indexing;
    private final ArtifactIndexRemovals removals;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    ArtifactIndexRetrySweep(Jobs jobs, ArtifactIndexing indexing, ArtifactIndexRemovals removals,
                            ObjectProvider<PlatformTransactionManager> transactions) {
        this.jobs = jobs;
        this.indexing = indexing;
        this.removals = removals;
        this.transactions = transactions;
    }

    /** One sweep: how many jobs were retried. Never throws. */
    public int sweep() {
        int retried = 0;
        int owners = 0;
        try {
            Set<UUID> found = new LinkedHashSet<>(jobs.ownersWithFailed(ArtifactIndexing.KIND));
            found.addAll(jobs.ownersWithFailed(ArtifactIndexRemovals.KIND));
            for (UUID owner : found) {
                owners++;
                retried += retryOwner(owner);
            }
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("errorType", failure.getClass().getName())
                    .log("artifacts.index_retry_sweep_failed");
        }
        if (retried > 0) {
            log.atInfo().addKeyValue("owners", owners).addKeyValue("retried", retried)
                    .log("artifacts.index_retry_swept");
        }
        return retried;
    }

    private int retryOwner(UUID owner) {
        try {
            AtomicInteger retried = new AtomicInteger();
            OwnerContext.runAs(owner, () -> new TransactionTemplate(transactions.getObject())
                    .executeWithoutResult(status -> retried.set(indexing.retryFailed() + removals.retryFailed())));
            return retried.get();
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("ownerId", owner).addKeyValue("errorType", failure.getClass().getName())
                    .log("artifacts.index_retry_owner_failed");
            return 0;
        }
    }
}
