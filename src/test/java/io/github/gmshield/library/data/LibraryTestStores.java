package io.github.gmshield.library.data;

import io.github.gmshield.shared.data.InMemoryTransactionManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The library's store in memory and a transaction manager, for Spring tests that run without PostgreSQL. */
@TestConfiguration(proxyBeanMethods = false)
public class LibraryTestStores {

    @Bean
    @Primary
    InMemoryLibraryStore inMemoryLibrary() {
        return new InMemoryLibraryStore();
    }

    @Bean
    InMemoryTransactionManager inMemoryTransactions() {
        return new InMemoryTransactionManager();
    }
}
