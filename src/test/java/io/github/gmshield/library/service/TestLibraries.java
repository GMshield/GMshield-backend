package io.github.gmshield.library.service;

import io.github.gmshield.library.data.LibraryStore;
import io.github.gmshield.vault.service.Vault;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

/** A working library over a given store, for other modules' plain unit tests. */
public final class TestLibraries {

    private TestLibraries() {
    }

    public static Library library(LibraryStore store, Vault vault, ObjectProvider<PlatformTransactionManager> transactions,
                                  ObjectProvider<Clock> clock) {
        return new Library(store, vault, transactions, clock);
    }
}
