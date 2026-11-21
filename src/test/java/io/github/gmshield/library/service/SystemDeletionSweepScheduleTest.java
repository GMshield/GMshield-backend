package io.github.gmshield.library.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * The system deletion sweep is scheduled every 10 minutes by default, a deletion's one-shot pass runs on the task
 * scheduler, and neither exists when switched off (Story 3.1 part B).
 */
class SystemDeletionSweepScheduleTest {

    @Test
    void theSweepIsScheduledEveryTenMinutesAndAOneShotPassRunsUnlessSwitchedOff() {
        SystemDeletion deletion = mock(SystemDeletion.class);
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(SystemDeletionSweepSchedule.class)
                .withBean(SystemDeletion.class, () -> deletion)
                .withBean(ThreadPoolTaskScheduler.class, () -> {
                    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
                    scheduler.initialize();
                    return scheduler;
                })
                // The default of application.yaml, without its environment variable.
                .withPropertyValues("gmshield.library.deletion-sweep-every=${GMSHIELD_SYSTEM_DELETION_SWEEP_EVERY:10m}");

        runner.run(context -> {
            assertThat(context).hasSingleBean(SystemDeletionSweepSchedule.class);
            List<ScheduledTask> tasks = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                    .flatMap(holder -> holder.getScheduledTasks().stream()).toList();
            assertThat(tasks).hasSize(1);
            assertThat(tasks.getFirst().getTask()).isInstanceOfSatisfying(FixedDelayTask.class,
                    task -> assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(10)));
            UUID owner = UUID.randomUUID();
            UUID id = UUID.randomUUID();
            context.getBean(SystemDeletionSweepSchedule.class).once(() -> deletion.run(owner, id), Instant.now());
            verify(deletion, timeout(5000)).run(owner, id);
        });
        runner.withPropertyValues("gmshield.library.deletion-sweep.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SystemDeletionSweepSchedule.class));
    }
}
