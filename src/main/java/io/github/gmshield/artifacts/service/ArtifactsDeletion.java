package io.github.gmshield.artifacts.service;

import io.github.gmshield.artifacts.data.ArtifactIndexStore;
import io.github.gmshield.artifacts.data.ArtifactVersionStore;
import io.github.gmshield.jobs.service.Jobs;
import io.github.gmshield.knowledge.service.ArtifactRecords;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.library.service.SystemDeletionHandler;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The artifacts' part of a campaign's or a system's deletion (Story 5.1, AD-22), as the owner of the work: when a
 * campaign goes, alone or with its system, its artifacts go too, their versions first and then their identities, before
 * {@code library} removes the campaign row (the artifact's key on (owner, campaign) needs it). A material's, a version's
 * or a conversation's deletion takes nothing here. It counts the artifacts going, which both confirmation dialogs name.
 *
 * <p>Story 5.1 part B: its prepare locks and cancels the artifacts' unended index jobs ({@link ArtifactIndexing#cancel})
 * and answers the latest lapse of their claims, so the removal waits for a worker still writing points. Its removal, in
 * one transaction, creates one {@code index.artifact_removal} job per artifact ({@link ArtifactIndexRemovals#start},
 * by owner and source), then deletes the artifacts' {@code artifact_index} rows, their versions and their identities.
 * It is safe to repeat: a repeated pass finds no artifact left, and creates no job.
 */
@Service
class ArtifactsDeletion implements SystemDeletionHandler {

    /** The part this handler names, which both deletion dialogs show (Story 5.1). */
    static final String PART = SystemDeletion.ARTIFACTS;
    private static final Logger log = LoggerFactory.getLogger(ArtifactsDeletion.class);

    private final ArtifactRecords records;
    private final ArtifactVersionStore versions;
    private final ArtifactIndexStore index;
    private final ArtifactIndexing indexing;
    private final ArtifactIndexRemovals removals;
    private final Jobs jobs;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    ArtifactsDeletion(ArtifactRecords records, ArtifactVersionStore versions, ArtifactIndexStore index,
                      ArtifactIndexing indexing, ArtifactIndexRemovals removals, Jobs jobs,
                      ObjectProvider<PlatformTransactionManager> transactions) {
        this.records = records;
        this.versions = versions;
        this.index = index;
        this.indexing = indexing;
        this.removals = removals;
        this.jobs = jobs;
        this.transactions = transactions;
    }

    @Override
    public String part() {
        return PART;
    }

    /** How many artifacts the campaigns going hold (none for a material, a version or a conversation). */
    @Override
    public long count(Going going) {
        OwnerContext.require();
        if (going.ofOneItem() || going.campaignIds().isEmpty()) {
            return 0;
        }
        return records.count(going.campaignIds());
    }

    @Override
    public Optional<Instant> prepare(Going going) {
        UUID owner = OwnerContext.require();
        if (going.ofOneItem() || going.campaignIds().isEmpty()) {
            return Optional.empty();
        }
        List<UUID> ids = records.idsOf(going.campaignIds());
        Optional<Instant> lapse = jobs.claimLapse(indexing.jobsOf(ids));
        int cancelled = indexing.cancel(ids);
        going.about(log.atInfo().addKeyValue("ownerId", owner)).addKeyValue("artifacts", ids.size())
                .addKeyValue("cancelled", cancelled).log("artifacts.deletion_prepared");
        return lapse;
    }

    @Override
    public void remove(Going going) {
        UUID owner = OwnerContext.require();
        if (going.ofOneItem() || going.campaignIds().isEmpty()) {
            return;
        }
        Integer removed = new TransactionTemplate(transactions.getObject()).execute(status -> {
            List<UUID> ids = records.idsOf(going.campaignIds());
            // Story 5.1 part B: one removal of each artifact's points, then its index rows before its versions.
            ids.forEach(removals::start);
            index.deleteOf(ids);
            versions.deleteOf(ids);
            return records.delete(ids);
        });
        going.about(log.atInfo().addKeyValue("ownerId", owner)).addKeyValue("artifacts", removed)
                .log("artifacts.deletion_removed");
    }
}
