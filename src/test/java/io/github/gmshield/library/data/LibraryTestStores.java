package io.github.gmshield.library.data;

import io.github.gmshield.artifacts.data.ArtifactTestStores;
import io.github.gmshield.knowledge.data.KnowledgeTestStores;
import io.github.gmshield.scope.data.ScopeTestStores;
import io.github.gmshield.shared.data.InMemoryTransactionManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** The library's stores in memory (from Story 3.1 part B, {@code deletion} too; from Story 3.2 the campaign fingerprint backfill; from Story 3.5 the scope settings, which a campaign's deletion removes; from Story 4.1 the record types, which a new system seeds; from Story 5.1 the artifacts' versions, which a campaign's deletion removes) and a transaction manager, for Spring tests that run without PostgreSQL. */
@TestConfiguration(proxyBeanMethods = false)
@Import({ScopeTestStores.class, KnowledgeTestStores.class, ArtifactTestStores.class})
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
