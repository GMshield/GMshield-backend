package io.github.gmshield.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import tools.jackson.databind.JsonNode;

/** How the jobs module is wired at start: the handler registry checks its kinds, and the sweep is scheduled. */
class JobsWiringTest {

    @Configuration(proxyBeanMethods = false)
    static class Registry {
        @Bean
        JobHandlers jobHandlers(ObjectProvider<JobHandler> handlers) {
            return new JobHandlers(handlers);
        }
    }

    @Test
    void twoHandlersOfOneKindStopTheStart() {
        new ApplicationContextRunner().withUserConfiguration(Registry.class)
                .withBean("first", JobHandler.class, () -> handler("same"))
                .withBean("second", JobHandler.class, () -> handler("same"))
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessage("jobs.handler_duplicate: same"));
    }

    @Test
    void aKindThatIsNotAShortLowerCaseWordStopsTheStart() {
        new ApplicationContextRunner().withUserConfiguration(Registry.class)
                .withBean("bad", JobHandler.class, () -> handler("Not A Kind"))
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessage("jobs.handler_invalid"));
    }

    @Test
    void distinctKindsStart() {
        new ApplicationContextRunner().withUserConfiguration(Registry.class)
                .withBean("one", JobHandler.class, () -> handler("one"))
                .withBean("two", JobHandler.class, () -> handler("two"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JobHandlers.class).find("two")).isPresent();
                });
    }

    @Test
    void theSweepIsScheduledEveryIntervalUnlessSwitchedOff() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(JobSweepSchedule.class)
                .withBean(JobSweep.class, () -> mock(JobSweep.class))
                .withPropertyValues("gmshield.jobs.sweep-every=10s");

        runner.run(context -> {
            assertThat(context).hasSingleBean(JobSweepSchedule.class);
            java.util.List<ScheduledTask> tasks = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                    .flatMap(holder -> holder.getScheduledTasks().stream()).toList();
            assertThat(tasks).hasSize(1);
            assertThat(tasks.getFirst().getTask()).isInstanceOfSatisfying(FixedDelayTask.class,
                    task -> assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofSeconds(10)));
        });
        runner.withPropertyValues("gmshield.jobs.sweep.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(JobSweepSchedule.class));
    }

    private static JobHandler handler(String kind) {
        return new JobHandler() {
            @Override
            public String kind() {
                return kind;
            }

            @Override
            public Lane lane() {
                return Lane.INTERACTIVE;
            }

            @Override
            public JsonNode input(JobRef job) {
                return null;
            }

            @Override
            public void completed(JobRef job, JsonNode result) {
            }

            @Override
            public void failed(JobRef job, String code) {
            }
        };
    }
}
