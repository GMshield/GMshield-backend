package io.github.gmshield.materials.data;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The materials stores in memory, for Spring tests that run without PostgreSQL. */
@TestConfiguration(proxyBeanMethods = false)
public class MaterialTestStores {

    @Bean
    @Primary
    InMemoryMaterialStore inMemoryMaterials() {
        return new InMemoryMaterialStore();
    }

    @Bean
    @Primary
    InMemoryPendingRemovals inMemoryPendingRemovals(InMemoryMaterialStore materials) {
        return new InMemoryPendingRemovals(materials);
    }

    @Bean
    @Primary
    InMemoryIndexStore inMemoryIndex(InMemoryMaterialStore materials) {
        return new InMemoryIndexStore(materials);
    }

    @Bean
    @Primary
    InMemoryStaleIndexes inMemoryStaleIndexes(InMemoryMaterialStore materials) {
        return new InMemoryStaleIndexes(materials);
    }

    @Bean
    @Primary
    InMemoryUploadStorage inMemoryUploads() {
        return new InMemoryUploadStorage();
    }
}
