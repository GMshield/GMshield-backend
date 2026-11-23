package io.github.gmshield.knowledge.service;

import io.github.gmshield.knowledge.data.KnowledgeStore;
import io.github.gmshield.library.service.SystemDeletionHandler;
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
 * The ontology's part of a system's deletion (Story 4.1, AD-22), as the owner of the work: when a system goes, its record
 * types, before the system's own row (the key on (owner, system) needs it). A campaign's deletion, a material's, a
 * version's or a conversation's takes nothing here. It counts nothing (no dialog names record types) and cancels
 * nothing (no job is the ontology's), and its removal, in one transaction, is safe to repeat.
 */
@Service
class SystemRecordTypesDeletion implements SystemDeletionHandler {

    /** The part this handler names; no dialog shows it, so it always counts 0. */
    static final String PART = "recordTypes";
    private static final Logger log = LoggerFactory.getLogger(SystemRecordTypesDeletion.class);

    private final KnowledgeStore store;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    SystemRecordTypesDeletion(KnowledgeStore store, ObjectProvider<PlatformTransactionManager> transactions) {
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
        if (going.systemId() == null) {
            return;
        }
        new TransactionTemplate(transactions.getObject())
                .executeWithoutResult(status -> store.deleteOfSystem(going.systemId()));
        going.about(log.atInfo().addKeyValue("ownerId", owner)).log("knowledge.deletion_removed");
    }
}
