package io.github.gmshield.materials.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the {@link IndexRemovalRetrySweep} every {@code gmshield.materials.index-retry-every} (10 min), the first time
 * right after the start, so a removal that failed while the backend was down is started again (Story 3.4 part B).
 * Switched off with the index retry sweep, by {@code gmshield.materials.index-retry-sweep.enabled=false}
 * ({@code application-unit.yaml}, and the integration tests, which drive the sweeps themselves).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "gmshield.materials.index-retry-sweep.enabled", havingValue = "true",
        matchIfMissing = true)
class IndexRemovalRetrySweepSchedule {

    private final IndexRemovalRetrySweep sweep;

    IndexRemovalRetrySweepSchedule(IndexRemovalRetrySweep sweep) {
        this.sweep = sweep;
    }

    @Scheduled(fixedDelayString = "${gmshield.materials.index-retry-every}")
    void run() {
        sweep.sweep();
    }
}
