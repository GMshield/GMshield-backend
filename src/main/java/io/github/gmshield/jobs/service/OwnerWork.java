package io.github.gmshield.jobs.service;

import io.github.gmshield.shared.service.OwnerContext;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a piece of job work as the job's owner, in one transaction of the application's transaction manager: the
 * owner is bound first ({@link OwnerContext#callAs}, AD-11), so library reads and writes of a handler work, and the
 * job's own change joins the same transaction. An exception rolls everything back.
 */
@Component
class OwnerWork {

    private final ObjectProvider<PlatformTransactionManager> transactions;

    OwnerWork(ObjectProvider<PlatformTransactionManager> transactions) {
        this.transactions = transactions;
    }

    <T> T call(UUID owner, Supplier<T> work) {
        return OwnerContext.callAs(owner, () -> new TransactionTemplate(transactions.getObject())
                .execute(status -> work.get()));
    }
}
