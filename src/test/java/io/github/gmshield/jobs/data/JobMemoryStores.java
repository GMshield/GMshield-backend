package io.github.gmshield.jobs.data;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The jobs module's stores in memory and nothing else (no clock, no transaction manager), for Spring tests of other
 * modules that create jobs without PostgreSQL and Redis (Story 1.15: an upload's completion).
 */
@TestConfiguration(proxyBeanMethods = false)
public class JobMemoryStores {

    @Bean
    @Primary
    InMemoryJobStorage inMemoryJobs() {
        return new InMemoryJobStorage();
    }

    @Bean
    @Primary
    InMemoryJobStream inMemoryJobStream(ObjectProvider<Clock> clock) {
        return new InMemoryJobStream(clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @Primary
    InMemoryProbeJobStore inMemoryProbeJobs() {
        return new InMemoryProbeJobStore();
    }
}
