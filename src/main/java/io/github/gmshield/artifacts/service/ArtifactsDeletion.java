package io.github.gmshield.artifacts.service;

import io.github.gmshield.artifacts.data.ArtifactVersionStore;
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
 * or a conversation's deletion takes nothing here. It counts the artifacts going, which both confirmation dialogs name,
 * and cancels nothing (no job is an artifact's in part A), and its removal, in one transaction, is safe to repeat.
 */
@Service
class ArtifactsDeletion implements SystemDeletionHandler {

    /** The part this handler names, which both deletion dialogs show (Story 5.1). */
    static final String PART = SystemDeletion.ARTIFACTS;
    private static final Logger log = LoggerFactory.getLogger(ArtifactsDeletion.class);

    private final ArtifactRecords records;
    private final ArtifactVersionStore versions;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    ArtifactsDeletion(ArtifactRecords records, ArtifactVersionStore versions,
                      ObjectProvider<PlatformTransactionManager> transactions) {
        this.records = records;
        this.versions = versions;
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
        return Optional.empty();
    }

    @Override
    public void remove(Going going) {
        UUID owner = OwnerContext.require();
        if (going.ofOneItem() || going.campaignIds().isEmpty()) {
            return;
        }
        Integer removed = new TransactionTemplate(transactions.getObject()).execute(status -> {
            List<UUID> ids = records.idsOf(going.campaignIds());
            versions.deleteOf(ids);
            return records.delete(ids);
        });
        going.about(log.atInfo().addKeyValue("ownerId", owner)).addKeyValue("artifacts", removed)
                .log("artifacts.deletion_removed");
    }
}
