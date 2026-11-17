package io.github.gmshield.materials.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the {@link VersionRemovalSweep} every {@code gmshield.materials.version-removal-sweep-every} (10 min), the first
 * time right after the start, so a removal the backend stopped in is finished after a restart. Switched off by
 * {@code gmshield.materials.version-removal-sweep.enabled=false} ({@code application-unit.yaml}, and the integration
 * tests, which drive the sweep themselves).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "gmshield.materials.version-removal-sweep.enabled", havingValue = "true",
        matchIfMissing = true)
class VersionRemovalSweepSchedule {

    private final VersionRemovalSweep sweep;

    VersionRemovalSweepSchedule(VersionRemovalSweep sweep) {
        this.sweep = sweep;
    }

    @Scheduled(fixedDelayString = "${gmshield.materials.version-removal-sweep-every}")
    void run() {
        sweep.sweep();
    }
}
