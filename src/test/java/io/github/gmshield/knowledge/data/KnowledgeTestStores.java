package io.github.gmshield.knowledge.data;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The ontology's tables in memory (Stories 4.1, 4.2 and 4.4), for Spring tests that run without PostgreSQL. */
@TestConfiguration(proxyBeanMethods = false)
public class KnowledgeTestStores {

    @Bean
    @Primary
    InMemoryKnowledgeStore inMemoryKnowledge() {
        return new InMemoryKnowledgeStore();
    }

    @Bean
    @Primary
    InMemoryRecordStore inMemoryRecords() {
        return new InMemoryRecordStore();
    }

    @Bean
    @Primary
    InMemoryOntologyStore inMemoryOntology() {
        return new InMemoryOntologyStore();
    }
}
