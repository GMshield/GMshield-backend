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

/**
 * Story 3.4 part B: the index removal retry sweep runs at the start and every 10 minutes by default, and not at all
 * when the index retry sweep is switched off.
 */
class IndexRemovalRetrySweepScheduleTest {

    @Test
    void theSweepRunsAtTheStartAndEveryTenMinutesUnlessSwitchedOff() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(IndexRemovalRetrySweepSchedule.class)
                .withBean(IndexRemovalRetrySweep.class, () -> mock(IndexRemovalRetrySweep.class))
                // The default of application.yaml, without its environment variable.
                .withPropertyValues("gmshield.materials.index-retry-every=${GMSHIELD_INDEX_RETRY_EVERY:10m}");

        runner.run(context -> {
            assertThat(context).hasSingleBean(IndexRemovalRetrySweepSchedule.class);
            List<ScheduledTask> tasks = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                    .flatMap(holder -> holder.getScheduledTasks().stream()).toList();
            assertThat(tasks).hasSize(1);
            assertThat(tasks.getFirst().getTask()).isInstanceOfSatisfying(FixedDelayTask.class, task -> {
                assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(10));
                assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ZERO);
            });
        });
        runner.withPropertyValues("gmshield.materials.index-retry-sweep.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(IndexRemovalRetrySweepSchedule.class));
    }
}
