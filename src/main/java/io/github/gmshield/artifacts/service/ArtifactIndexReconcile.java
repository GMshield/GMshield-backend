package io.github.gmshield.artifacts.service;

import io.github.gmshield.artifacts.data.StaleArtifacts;
import io.github.gmshield.jobs.service.IndexRecipeStated;
import io.github.gmshield.shared.service.OwnerContext;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Indexes the artifacts not indexed under the worker's recipe (Story 5.1 part B), as the materials'
 * {@code IndexReconcile} does for versions, beside it: when a claim states an index recipe other than the last one stated
 * ({@link IndexRecipeStated}: the first claim after a start always does), the backfill runs once, after that claim and
 * off its request, one at a time in the order asked. It visits each owner with a newest artifact version that no row
 * reports under that recipe ({@link StaleArtifacts}: ids only), as that owner, in a transaction of its own, and gives
 * each such version with no unended job a new one ({@link ArtifactIndexing#reconcile}), with the version locked. So the
 * artifacts saved before this part are indexed the first time the worker states its recipe. One owner's failure never
 * stops the others; it is logged by error type, and the next statement tries again.
 */
@Service
public class ArtifactIndexReconcile {

    private static final Logger log = LoggerFactory.getLogger(ArtifactIndexReconcile.class);

    private final StaleArtifacts stale;
    private final ArtifactIndexing indexing;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final ExecutorService runner = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("artifact-index-reconcile-", 0).factory());
    private CompletableFuture<Void> queued = CompletableFuture.completedFuture(null);

    ArtifactIndexReconcile(StaleArtifacts stale, ArtifactIndexing indexing,
                           ObjectProvider<PlatformTransactionManager> transactions) {
        this.stale = stale;
        this.indexing = indexing;
        this.transactions = transactions;
    }

    @EventListener
    void stated(IndexRecipeStated event) {
        synchronized (this) {
            // Whatever the previous backfill did, this one runs: a failed stage never ends the chain.
            queued = queued.handle((ignored, failure) -> null).thenRunAsync(() -> reconcile(event.recipe()), runner);
        }
    }

    /** Completes once every backfill asked so far has run. */
    public synchronized CompletableFuture<Void> settled() {
        return queued;
    }

    /** One backfill under {@code recipe}: how many artifact versions got a new index job. Never throws. */
    public int reconcile(String recipe) {
        AtomicInteger started = new AtomicInteger();
        int owners = 0;
        try {
            for (UUID owner : stale.owners(recipe)) {
                owners++;
                reconcileOwner(owner, recipe, started);
            }
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("errorType", failure.getClass().getName()).log("artifacts.reconcile_failed");
        }
        log.atInfo().addKeyValue("recipe", recipe).addKeyValue("owners", owners).addKeyValue("versions", started.get())
                .log("artifacts.reconciled");
        return started.get();
    }

    private void reconcileOwner(UUID owner, String recipe, AtomicInteger started) {
        try {
            AtomicInteger ofOwner = new AtomicInteger();
            OwnerContext.runAs(owner, () -> new TransactionTemplate(transactions.getObject())
                    .executeWithoutResult(status -> ofOwner.set(indexing.reconcile(recipe))));
            started.addAndGet(ofOwner.get());
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("ownerId", owner).addKeyValue("errorType", failure.getClass().getName())
                    .log("artifacts.reconcile_owner_failed");
        }
    }

    @PreDestroy
    void stop() {
        runner.shutdownNow();
    }
}
