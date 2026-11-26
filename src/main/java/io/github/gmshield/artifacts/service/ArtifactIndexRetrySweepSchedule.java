package io.github.gmshield.artifacts.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the {@link ArtifactIndexRetrySweep} on the materials' index retry schedule (Story 5.1 part B): every
 * {@code gmshield.materials.index-retry-every} (10 min), the first time one period after the start, and switched off
 * with it by {@code gmshield.materials.index-retry-sweep.enabled=false} (the unit and integration tests, which drive the
 * sweep themselves), so every index retry of the backend follows one setting.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "gmshield.materials.index-retry-sweep.enabled", havingValue = "true",
        matchIfMissing = true)
class ArtifactIndexRetrySweepSchedule {

    private final ArtifactIndexRetrySweep sweep;

    ArtifactIndexRetrySweepSchedule(ArtifactIndexRetrySweep sweep) {
        this.sweep = sweep;
    }

    @Scheduled(fixedDelayString = "${gmshield.materials.index-retry-every}",
            initialDelayString = "${gmshield.materials.index-retry-every}")
    void run() {
        sweep.sweep();
    }
}
