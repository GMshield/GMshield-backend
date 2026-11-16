package io.github.gmshield.jobs.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the {@link JobSweep} every {@code gmshield.jobs.sweep-every} (10 s), the first time right after the start,
 * which also creates the consumer groups. Switched off by {@code gmshield.jobs.sweep.enabled=false}
 * ({@code application-unit.yaml}, and the integration tests, which drive the sweep themselves).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "gmshield.jobs.sweep.enabled", havingValue = "true", matchIfMissing = true)
class JobSweepSchedule {

    private final JobSweep sweep;

    JobSweepSchedule(JobSweep sweep) {
        this.sweep = sweep;
    }

    @Scheduled(fixedDelayString = "${gmshield.jobs.sweep-every}")
    void run() {
        sweep.sweep();
    }
}
