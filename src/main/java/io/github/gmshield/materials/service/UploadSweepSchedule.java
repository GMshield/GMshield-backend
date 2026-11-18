package io.github.gmshield.materials.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the {@link UploadSweep} every {@code gmshield.materials.upload-sweep-every} (10 min), the first time right after
 * the start. Switched off by {@code gmshield.materials.upload-sweep.enabled=false} ({@code application-unit.yaml}, and
 * the integration tests, which drive the sweep themselves).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "gmshield.materials.upload-sweep.enabled", havingValue = "true", matchIfMissing = true)
class UploadSweepSchedule {

    private final UploadSweep sweep;

    UploadSweepSchedule(UploadSweep sweep) {
        this.sweep = sweep;
    }

    @Scheduled(fixedDelayString = "${gmshield.materials.upload-sweep-every}")
    void run() {
        sweep.sweep();
    }
}
