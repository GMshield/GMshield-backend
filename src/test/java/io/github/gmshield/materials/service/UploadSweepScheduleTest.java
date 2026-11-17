package io.github.gmshield.materials.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/** The upload sweep is scheduled every 10 minutes by default, and not at all when switched off (Story 1.13). */
class UploadSweepScheduleTest {

    @Test
    void theSweepIsScheduledEveryTenMinutesUnlessSwitchedOff() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(UploadSweepSchedule.class)
                .withBean(UploadSweep.class, () -> mock(UploadSweep.class))
                // The default of application.yaml, without its environment variable.
                .withPropertyValues("gmshield.materials.upload-sweep-every=${GMSHIELD_UPLOAD_SWEEP_EVERY:10m}");

        runner.run(context -> {
            assertThat(context).hasSingleBean(UploadSweepSchedule.class);
            List<ScheduledTask> tasks = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                    .flatMap(holder -> holder.getScheduledTasks().stream()).toList();
            assertThat(tasks).hasSize(1);
            assertThat(tasks.getFirst().getTask()).isInstanceOfSatisfying(FixedDelayTask.class,
                    task -> assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(10)));
        });
        runner.withPropertyValues("gmshield.materials.upload-sweep.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(UploadSweepSchedule.class));
    }
}
