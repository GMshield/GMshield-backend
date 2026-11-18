package io.github.gmshield.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmshield.jobs.data.ProbeJobRow;
import io.github.gmshield.jobs.data.ProbeJobStore;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The race deferred from Story 1.12 and added with the first real ingestion kind (Story 1.15), against a real
 * PostgreSQL and Redis: many offer decisions at once (an outcome report, the sweep, the offer after a commit, all of
 * them {@link JobOffers#offerNextIngestion}) never leave two ingestion jobs offered or running. The lane's advisory lock
 * is what makes it hold: each decision reads "busy" and the next job, and offers it, while no other decision runs.
 */
@SpringBootTest(properties = {"GMSHIELD_MODE=test", "gmshield.jobs.sweep.enabled=false",
    "gmshield.materials.upload-sweep.enabled=false", "gmshield.materials.version-removal-sweep.enabled=false"})
@ActiveProfiles("test-mode")
class IngestionOfferRaceIntegrationTest {

    private static final int OWNERS = 8;
    private static final int RACERS = 12;
    private static final int ROUNDS = 15;

    @Autowired
    private JobOffers offers;
    @Autowired
    private Jobs jobs;
    @Autowired
    private JobSweep sweep;
    @Autowired
    private ProbeJobStore probes;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void cleanSlate() {
        jdbc.sql("delete from job_output").update();
        jdbc.sql("delete from job_probe").update();
        jdbc.sql("delete from job").update();
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        sweep.ensureGroups();
    }

    @Test
    void racingOfferDecisionsNeverLeaveTwoIngestionJobsOfferedOrRunning() throws Exception {
        for (int owner = 0; owner < OWNERS; owner++) {
            UUID id = Ids.newId();
            for (int job = 0; job < ROUNDS; job++) {
                create(id);
            }
        }
        ExecutorService threads = Executors.newFixedThreadPool(RACERS);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                // The offered job ends, as a done outcome would end it, and every racer decides what comes next.
                jdbc.sql("update job set state = 'done' where lane = 'ingestion' and state in ('offered', 'running')")
                        .update();
                CountDownLatch go = new CountDownLatch(1);
                List<Future<?>> racers = new ArrayList<>();
                for (int racer = 0; racer < RACERS; racer++) {
                    racers.add(threads.submit(() -> {
                        go.await();
                        return offers.offerNextIngestion();
                    }));
                }
                go.countDown();
                for (Future<?> racer : racers) {
                    racer.get(30, TimeUnit.SECONDS);
                }
                assertThat(busy()).as("round %d", round).isEqualTo(1);
            }
        } finally {
            threads.shutdownNow();
        }
        assertThat(jdbc.sql("select count(distinct owner_id) from job where lane = 'ingestion' and state = 'done'")
                .query(Long.class).single()).isGreaterThan(1);
    }

    private long busy() {
        return jdbc.sql("select count(*) from job where lane = 'ingestion' and state in ('offered', 'running')")
                .query(Long.class).single();
    }

    private void create(UUID owner) {
        OwnerContext.runAs(owner, () -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                jobs.create(ProbeJobs.KIND, Lane.INGESTION, job -> probes.insert(new ProbeJobRow(job.id(), 0, 0)))));
    }
}
