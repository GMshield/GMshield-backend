package io.github.gmshield.scope.data;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The scope settings tables in memory (Story 3.5), for Spring tests that run without PostgreSQL. */
@TestConfiguration(proxyBeanMethods = false)
public class ScopeTestStores {

    @Bean
    @Primary
    InMemoryScopeStore inMemoryScopes() {
        return new InMemoryScopeStore();
    }
}
