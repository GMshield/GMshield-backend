package io.github.gmshield.jobs.data;

import io.github.gmshield.shared.data.InMemoryTransactionManager;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * The jobs module's stores in memory, a transaction manager and a clock the test moves, for Spring tests that run
 * without PostgreSQL and Redis.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(JobMemoryStores.class)
public class JobTestStores {

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-11-16T12:00:00Z"));
    }

    @Bean
    InMemoryTransactionManager inMemoryJobTransactions() {
        return new InMemoryTransactionManager();
    }
}
