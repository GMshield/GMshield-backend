package io.github.gmshield.knowledge.data;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The ontology's table in memory (Story 4.1), for Spring tests that run without PostgreSQL. */
@TestConfiguration(proxyBeanMethods = false)
public class KnowledgeTestStores {

    @Bean
    @Primary
    InMemoryKnowledgeStore inMemoryKnowledge() {
        return new InMemoryKnowledgeStore();
    }
}
