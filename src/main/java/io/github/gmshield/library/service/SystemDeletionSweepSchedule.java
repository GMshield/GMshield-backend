package io.github.gmshield.library.service;

import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the {@link SystemDeletion} sweep every {@code gmshield.library.deletion-sweep-every} (10 min), the first time
 * right after the start, so a deletion the backend stopped in is finished after a restart; and runs one deletion's
 * pass once at its {@code not_before} ({@link #once}), on the application's task scheduler. Switched off, one-shot
 * passes included, by {@code gmshield.library.deletion-sweep.enabled=false} ({@code application-unit.yaml}, and the
 * integration tests, which drive the passes themselves).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "gmshield.library.deletion-sweep.enabled", havingValue = "true", matchIfMissing = true)
class SystemDeletionSweepSchedule {

    private final ObjectProvider<SystemDeletion> deletion;
    private final ObjectProvider<TaskScheduler> scheduler;

    SystemDeletionSweepSchedule(ObjectProvider<SystemDeletion> deletion, ObjectProvider<TaskScheduler> scheduler) {
        this.deletion = deletion;
        this.scheduler = scheduler;
    }

    @Scheduled(fixedDelayString = "${gmshield.library.deletion-sweep-every}")
    void run() {
        deletion.getObject().sweep();
    }

    /** Runs {@code pass} once at {@code at} (at once when it has passed), when the application has one scheduler. */
    void once(Runnable pass, Instant at) {
        TaskScheduler tasks = scheduler.getIfUnique();
        if (tasks != null) {
            tasks.schedule(pass, at);
        }
    }
}
