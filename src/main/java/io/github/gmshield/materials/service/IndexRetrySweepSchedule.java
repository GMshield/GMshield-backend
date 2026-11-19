package io.github.gmshield.materials.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the {@link IndexRetrySweep} every {@code gmshield.materials.index-retry-every} (10 min), the first time one
 * period after the start, so a run that failed is retried within that period. Switched off by
 * {@code gmshield.materials.index-retry-sweep.enabled=false} ({@code application-unit.yaml}, and the integration tests,
 * which drive the sweep themselves).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "gmshield.materials.index-retry-sweep.enabled", havingValue = "true",
        matchIfMissing = true)
class IndexRetrySweepSchedule {

    private final IndexRetrySweep sweep;

    IndexRetrySweepSchedule(IndexRetrySweep sweep) {
        this.sweep = sweep;
    }

    @Scheduled(fixedDelayString = "${gmshield.materials.index-retry-every}",
            initialDelayString = "${gmshield.materials.index-retry-every}")
    void run() {
        sweep.sweep();
    }
}
