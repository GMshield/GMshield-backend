package io.github.gmshield.conversations.data;

import io.github.gmshield.limits.data.InMemoryUsageStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The conversation tables and the usage table in memory, for Spring tests that run without PostgreSQL. */
@TestConfiguration(proxyBeanMethods = false)
public class ConversationTestStores {

    @Bean
    @Primary
    InMemoryConversationStore inMemoryConversations() {
        return new InMemoryConversationStore();
    }

    @Bean
    @Primary
    InMemoryUsageStore inMemoryUsage() {
        return new InMemoryUsageStore();
    }
}
