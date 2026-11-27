package io.github.gmshield.scope.service;

import io.github.gmshield.library.service.SystemDeletionHandler;
import io.github.gmshield.scope.data.ScopeStore;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The scope's part of a deletion (Story 3.5, AD-22), as the owner of the work: when a system or a campaign goes, the
 * stored settings and material switches of the campaigns going; when a material goes, its switch rows in every
 * campaign. A version's removal, (Story 3.10 part B) a conversation's deletion or (Story 5.2 part B) an artifact's
 * takes nothing here. It counts nothing and cancels nothing (no job is the scope's), and its removal, in one
 * transaction, is safe to repeat.
 */
@Service
class ScopeDeletion implements SystemDeletionHandler {

    /** The part this handler names; no dialog shows it, so it always counts 0. */
    static final String PART = "scope";
    private static final Logger log = LoggerFactory.getLogger(ScopeDeletion.class);

    private final ScopeStore store;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    ScopeDeletion(ScopeStore store, ObjectProvider<PlatformTransactionManager> transactions) {
        this.store = store;
        this.transactions = transactions;
    }

    @Override
    public String part() {
        return PART;
    }

    @Override
    public long count(Going going) {
        return 0;
    }

    @Override
    public Optional<Instant> prepare(Going going) {
        return Optional.empty();
    }

    @Override
    public void remove(Going going) {
        UUID owner = OwnerContext.require();
        if (going.versionId() != null || going.ofOneConversation() || going.ofOneArtifact()
                || going.ofOneShield()) {
            return;
        }
        new TransactionTemplate(transactions.getObject()).executeWithoutResult(status -> {
            if (going.materialId() != null) {
                store.deleteOfMaterial(going.materialId());
            } else {
                store.deleteOfCampaigns(going.campaignIds());
            }
        });
        going.about(log.atInfo().addKeyValue("ownerId", owner)).log("scope.deletion_removed");
    }
}
