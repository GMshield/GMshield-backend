package io.github.gmshield.materials.service;

import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Starts again the index removals whose job failed after its attempts (Story 3.4 part B), as {@link IndexRetrySweep}
 * does for index changes: at the start and every {@code gmshield.materials.index-retry-every}
 * ({@link IndexRemovalRetrySweepSchedule}), each owner with a failed {@code index.removal} job
 * ({@link Jobs#ownersWithFailed}: ids only) is visited as that owner, in a transaction of its own, and each failed job
 * that still has its {@code index_removal} row gets a new job with the same input, the row moving to it
 * ({@link IndexRemovals#retryFailed}). One owner's failure never stops the others; it is logged by error type and tried
 * again next time.
 */
@Service
public class IndexRemovalRetrySweep {

    private static final Logger log = LoggerFactory.getLogger(IndexRemovalRetrySweep.class);

    private final Jobs jobs;
    private final IndexRemovals removals;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    IndexRemovalRetrySweep(Jobs jobs, IndexRemovals removals, ObjectProvider<PlatformTransactionManager> transactions) {
        this.jobs = jobs;
        this.removals = removals;
        this.transactions = transactions;
    }

    /** One sweep: how many removals got a new job. Never throws. */
    public int sweep() {
        int retried = 0;
        int owners = 0;
        try {
            for (UUID owner : jobs.ownersWithFailed(IndexRemovals.KIND)) {
                owners++;
                retried += retryOwner(owner);
            }
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("errorType", failure.getClass().getName()).log("index.removal_retry_sweep_failed");
        }
        if (retried > 0) {
            log.atInfo().addKeyValue("owners", owners).addKeyValue("retried", retried).log("index.removal_retry_swept");
        }
        return retried;
    }

    private int retryOwner(UUID owner) {
        try {
            AtomicInteger retried = new AtomicInteger();
            OwnerContext.runAs(owner, () -> new TransactionTemplate(transactions.getObject())
                    .executeWithoutResult(status -> retried.set(removals.retryFailed())));
            return retried.get();
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("ownerId", owner).addKeyValue("errorType", failure.getClass().getName())
                    .log("index.removal_retry_owner_failed");
            return 0;
        }
    }
}
