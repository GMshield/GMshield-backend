package io.github.gmshield.shared.data;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * A transaction manager for the tests that run without a database: it begins, commits and rolls back nothing
 * itself, but runs Spring's transaction synchronization, through which the in-memory stores undo their writes
 * when a transaction rolls back.
 */
public class InMemoryTransactionManager extends AbstractPlatformTransactionManager {

    private static final long serialVersionUID = 1L;

    public final AtomicInteger commits = new AtomicInteger();
    public final AtomicInteger rollbacks = new AtomicInteger();

    @Override
    protected Object doGetTransaction() {
        return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        // nothing to begin: the stores are in memory
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        commits.incrementAndGet();
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        rollbacks.incrementAndGet();
    }
}
