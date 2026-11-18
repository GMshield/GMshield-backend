package io.github.gmshield.jobs.service;

import io.github.gmshield.jobs.data.JobRow;
import io.github.gmshield.jobs.data.JobStorage;
import io.github.gmshield.jobs.data.ProbeJobRow;
import io.github.gmshield.jobs.data.ProbeJobStore;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.github.gmshield.shared.service.StartupSettings;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test mode only: the {@code probe} job kind, which drives the jobs tests until Story 1.15 adds the first real
 * kind. A probe job belongs to a fixed test-mode owner; the worker's probe handler works {@code workMs}
 * milliseconds and fails its first {@code failTimes} attempts. No public route creates one.
 */
@Service
@Profile(StartupSettings.TEST_MODE_PROFILE)
public class ProbeJobs implements JobHandler {

    public static final String KIND = "probe";
    /** The owner of every probe job: no account has it. */
    public static final UUID OWNER = UUID.fromString("0195a3c0-0000-7000-8000-00000000b0b0");
    public static final int MAX_WORK_MS = 3_600_000;
    public static final int MAX_FAIL_TIMES = 10;
    private static final Logger log = LoggerFactory.getLogger(ProbeJobs.class);

    private final Jobs jobs;
    private final ProbeJobStore store;
    private final JobStorage storage;
    private final OwnerWork owners;

    ProbeJobs(Jobs jobs, ProbeJobStore store, JobStorage storage, OwnerWork owners) {
        this.jobs = jobs;
        this.store = store;
        this.storage = storage;
        this.owners = owners;
    }

    /** What the test-mode route shows of a probe job: never more than the row's ids, codes and times. */
    public record Status(UUID id, String lane, String state, int attempt, String outcomeCode, String traceId,
                         Instant offeredAt, Instant updatedAt) {
    }

    /** Creates a probe job in the lane named, for the fixed owner, and offers it after the commit. */
    public UUID create(String lane, Integer workMs, Integer failTimes) {
        Optional<Lane> chosen = Lane.of(lane == null ? Lane.INTERACTIVE.code() : lane);
        int work = workMs == null ? 0 : workMs;
        int fails = failTimes == null ? 0 : failTimes;
        if (chosen.isEmpty() || work < 0 || work > MAX_WORK_MS || fails < 0 || fails > MAX_FAIL_TIMES) {
            throw new ApiException(ApiProblem.forStatus(400));
        }
        return owners.call(OWNER, () -> jobs.create(KIND, chosen.get(),
                job -> store.insert(new ProbeJobRow(job.id(), work, fails))).id());
    }

    /** A probe job's status; empty for any other job. */
    public Optional<Status> status(UUID id) {
        return storage.joined().find(id).filter(row -> OWNER.equals(row.ownerId()) && KIND.equals(row.kind()))
                .map(ProbeJobs::status);
    }

    private static Status status(JobRow row) {
        return new Status(row.id(), row.lane(), row.state(), row.attempt(), row.outcomeCode(), row.traceId(),
                row.offeredAt(), row.updatedAt());
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Lane lane() {
        return Lane.INTERACTIVE;
    }

    @Override
    public JsonNode input(JobRef job) {
        ProbeJobRow row = store.find(job.id()).orElseThrow(() -> new IllegalStateException("probe.input_missing"));
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("workMs", row.workMs());
        input.put("failTimes", row.failTimes());
        return input;
    }

    @Override
    public void completed(JobRef job, JsonNode result) {
        log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("attempt", job.attempt()).log("probe.completed");
    }

    @Override
    public void failed(JobRef job, String code) {
        log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("attempt", job.attempt()).addKeyValue("code", code)
                .log("probe.failed");
    }
}
