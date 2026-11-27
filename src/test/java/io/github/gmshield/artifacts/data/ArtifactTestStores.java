package io.github.gmshield.artifacts.data;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The artifacts' tables in memory (Story 5.1), for Spring tests that run without PostgreSQL. */
@TestConfiguration(proxyBeanMethods = false)
public class ArtifactTestStores {

    @Bean
    @Primary
    InMemoryArtifactVersionStore inMemoryArtifactVersions() {
        return new InMemoryArtifactVersionStore();
    }

    @Bean
    @Primary
    InMemoryArtifactReferenceStore inMemoryArtifactReferences() {
        return new InMemoryArtifactReferenceStore();
    }

    @Bean
    @Primary
    InMemoryArtifactShareStore inMemoryArtifactShares() {
        return new InMemoryArtifactShareStore();
    }

    @Bean
    @Primary
    InMemoryArtifactIndexStore inMemoryArtifactIndex(InMemoryArtifactVersionStore versions) {
        return new InMemoryArtifactIndexStore(versions);
    }

    @Bean
    @Primary
    InMemoryStaleArtifacts inMemoryStaleArtifacts(InMemoryArtifactIndexStore index) {
        return new InMemoryStaleArtifacts(index);
    }
}
