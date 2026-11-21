package io.github.gmshield.library.data;

import io.github.gmshield.shared.data.InMemoryTransactionManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The library's stores in memory (from Story 3.1 part B, {@code deletion} too; from Story 3.2 the campaign fingerprint backfill) and a transaction manager, for Spring tests that run without PostgreSQL. */
@TestConfiguration(proxyBeanMethods = false)
public class LibraryTestStores {

    @Bean
    @Primary
    InMemoryLibraryStore inMemoryLibrary() {
        return new InMemoryLibraryStore();
    }

    @Bean
    @Primary
    InMemoryDeletionStore inMemoryDeletions() {
        return new InMemoryDeletionStore();
    }

    @Bean
    @Primary
    InMemoryFingerprintBackfill inMemoryFingerprintBackfill() {
        return new InMemoryFingerprintBackfill();
    }

    @Bean
    InMemoryTransactionManager inMemoryTransactions() {
        return new InMemoryTransactionManager();
    }
}
