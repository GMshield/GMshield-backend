package io.github.gmshield.knowledge.data;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The ontology's tables in memory (Stories 4.1, 4.2, 4.4 and 5.5), for Spring tests that run without PostgreSQL. */
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
    InMemoryRecordRelationStore inMemoryRecordRelations() {
        return new InMemoryRecordRelationStore();
    }

    @Bean
    @Primary
    InMemoryOntologyStore inMemoryOntology() {
        return new InMemoryOntologyStore();
    }
}
