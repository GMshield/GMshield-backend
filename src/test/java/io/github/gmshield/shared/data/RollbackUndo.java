package io.github.gmshield.shared.data;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** For the in-memory stores: undoes a write when the transaction it was made in rolls back. */
public final class RollbackUndo {

    private RollbackUndo() {
    }

    public static void onRollback(Runnable undo) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    undo.run();
                }
            }
        });
    }
}
