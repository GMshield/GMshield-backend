package io.github.gmshield.library.service;

import io.github.gmshield.library.data.LibraryStore;
import io.github.gmshield.vault.service.Vault;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A working library over a given store, for other modules' plain unit tests. The events it publishes (Story 4.1:
 * {@link SystemCreated}) go nowhere, or to {@code events} when given.
 */
public final class TestLibraries {

    private TestLibraries() {
    }

    public static Library library(LibraryStore store, Vault vault, ObjectProvider<PlatformTransactionManager> transactions,
                                  ObjectProvider<Clock> clock) {
        return library(store, vault, transactions, clock, event -> { });
    }

    public static Library library(LibraryStore store, Vault vault, ObjectProvider<PlatformTransactionManager> transactions,
                                  ObjectProvider<Clock> clock, ApplicationEventPublisher events) {
        return new Library(store, vault, transactions, clock, events);
    }
}
