package io.github.gmshield.materials.service;

import io.github.gmshield.jobs.service.IndexRecipeStated;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.materials.data.StaleIndexes;
import io.github.gmshield.materials.data.VersionRow;
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
 * Indexes again what was indexed under another recipe (Story 2.1, AD-10). When a claim states an index recipe other
 * than the last one stated ({@link IndexRecipeStated}: the first claim after a start always does), the reconcile runs
 * once, after that claim and off its request, one at a time in the order asked. It visits each owner with a
 * {@code ready} version recorded under another recipe, or none ({@link StaleIndexes}: ids only), as that owner, each in a
 * transaction of its own, and gives every such version that has no unended index change a new set of index changes
 * ({@link IndexChanges#start}), with the version locked, so a version is never given two sets at once. One owner's
 * failure never stops the others; it is logged by error type, and the next reconcile tries again.
 */
@Service
public class IndexReconcile {

    private static final Logger log = LoggerFactory.getLogger(IndexReconcile.class);

    private final StaleIndexes stale;
    private final MaterialStore materials;
    private final IndexChanges changes;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final ExecutorService runner = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("index-reconcile-", 0).factory());
    private CompletableFuture<Void> queued = CompletableFuture.completedFuture(null);

    IndexReconcile(StaleIndexes stale, MaterialStore materials, IndexChanges changes,
                   ObjectProvider<PlatformTransactionManager> transactions) {
        this.stale = stale;
        this.materials = materials;
        this.changes = changes;
        this.transactions = transactions;
    }

    @EventListener
    void stated(IndexRecipeStated event) {
        synchronized (this) {
            // Whatever the previous reconcile did, this one runs: a failed stage never ends the chain.
            queued = queued.handle((ignored, failure) -> null).thenRunAsync(() -> reconcile(event.recipe()), runner);
        }
    }

    /** Completes once every reconcile asked so far has run. */
    public synchronized CompletableFuture<Void> settled() {
        return queued;
    }

    /** One reconcile under {@code recipe}: how many versions got a new set of index changes. Never throws. */
    public int reconcile(String recipe) {
        AtomicInteger versions = new AtomicInteger();
        int owners = 0;
        try {
            for (UUID owner : stale.owners(recipe)) {
                owners++;
                reconcileOwner(owner, recipe, versions);
            }
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("errorType", failure.getClass().getName()).log("index.reconcile_failed");
        }
        log.atInfo().addKeyValue("recipe", recipe).addKeyValue("owners", owners).addKeyValue("versions", versions.get())
                .log("index.reconciled");
        return versions.get();
    }

    private void reconcileOwner(UUID owner, String recipe, AtomicInteger versions) {
        AtomicInteger started = new AtomicInteger();
        try {
            OwnerContext.runAs(owner, () -> new TransactionTemplate(transactions.getObject())
                    .executeWithoutResult(status -> {
                        for (VersionRow found : materials.readyOutside(recipe)) {
                            boolean stillStale = materials.lockVersion(found.id())
                                    .filter(version -> Materials.READY.equals(version.state())
                                            && !recipe.equals(version.indexRecipe())).isPresent();
                            if (stillStale && !changes.pending(found.id())) {
                                changes.start(found);
                                started.incrementAndGet();
                            }
                        }
                    }));
            versions.addAndGet(started.get());
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("ownerId", owner).addKeyValue("errorType", failure.getClass().getName())
                    .log("index.reconcile_owner_failed");
        }
    }

    @PreDestroy
    void stop() {
        runner.shutdownNow();
    }
}
