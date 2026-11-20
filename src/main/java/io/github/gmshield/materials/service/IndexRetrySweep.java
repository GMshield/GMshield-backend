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
 * Retries the index changes whose job failed (indexing at worker start): every
 * {@code gmshield.materials.index-retry-every} ({@link IndexRetrySweepSchedule}), each owner with a failed
 * {@code index.change} job ({@link Jobs#ownersWithFailed}: ids only) is visited as that owner, in a transaction of its
 * own, and each of its failed runs still to retry gets a new job ({@link IndexChanges#retryFailed}): only the failed runs,
 * never a whole version. One owner's failure never stops the others; it is logged by error type and tried again next
 * time.
 */
@Service
public class IndexRetrySweep {

    private static final Logger log = LoggerFactory.getLogger(IndexRetrySweep.class);

    private final Jobs jobs;
    private final IndexChanges changes;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    IndexRetrySweep(Jobs jobs, IndexChanges changes, ObjectProvider<PlatformTransactionManager> transactions) {
        this.jobs = jobs;
        this.changes = changes;
        this.transactions = transactions;
    }

    /** One sweep: how many runs got a new job. Never throws. */
    public int sweep() {
        int retried = 0;
        int owners = 0;
        try {
            for (UUID owner : jobs.ownersWithFailed(IndexChanges.KIND)) {
                owners++;
                retried += retryOwner(owner);
            }
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("errorType", failure.getClass().getName()).log("index.retry_sweep_failed");
        }
        if (retried > 0) {
            log.atInfo().addKeyValue("owners", owners).addKeyValue("retried", retried).log("index.retry_swept");
        }
        return retried;
    }

    private int retryOwner(UUID owner) {
        try {
            AtomicInteger retried = new AtomicInteger();
            OwnerContext.runAs(owner, () -> new TransactionTemplate(transactions.getObject())
                    .executeWithoutResult(status -> retried.set(changes.retryFailed())));
            return retried.get();
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("ownerId", owner).addKeyValue("errorType", failure.getClass().getName())
                    .log("index.retry_owner_failed");
            return 0;
        }
    }
}
