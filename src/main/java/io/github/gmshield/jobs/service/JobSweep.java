package io.github.gmshield.jobs.service;

import io.github.gmshield.jobs.data.JobOutputRow;
import io.github.gmshield.jobs.data.JobRow;
import io.github.gmshield.jobs.data.JobStorage;
import io.github.gmshield.jobs.data.JobStream;
import io.github.gmshield.vault.service.FileStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The jobs module's one recurring task (every 10 s, {@link JobSweepSchedule}). In order, each step on its own so
 * that one failure never stops the others:
 *
 * <ol>
 *   <li>creates the consumer group {@code workers} on each stream when it is missing (a new or a flushed Redis);</li>
 *   <li>takes back lapsed claims: the job goes back to waiting while attempts remain, otherwise it ends
 *       {@code failed} with {@code job.attempts_exhausted}; a job that delivered an event (Story 2.4,
 *       {@link JobHandler#delivered}) ends {@code failed} with {@code job.claim_lapsed} at once;</li>
 *   <li>offers again every job offered longer than the re-offer delay without a claim (Redis lost the message);</li>
 *   <li>offers waiting jobs (an earlier offer failed, or a job just went back to waiting);</li>
 *   <li>removes the outputs no handler adopted once their job ended (Story 1.15): the file's pieces, then its row;
 *       an output still being written only once its job ended more than {@link #WRITING_GRACE} ago;</li>
 *   <li>trims each stream to the last hour ({@code XTRIM MINID}): an older message was answered or superseded.</li>
 * </ol>
 *
 * Its own writes go through the separate transactions of {@link JobStorage#separately}; ending a job runs the
 * handler as the owner ({@link OwnerWork}).
 */
@Service
public class JobSweep {

    static final int BATCH = 100;
    /** How long after its job ended an output still marked as being written is left to its writer. */
    static final java.time.Duration WRITING_GRACE = java.time.Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(JobSweep.class);

    private final JobStorage storage;
    private final JobStream stream;
    private final JobOffers offers;
    private final JobHandlers handlers;
    private final OwnerWork owners;
    private final JobSettings settings;
    private final JobClock clock;
    private final FileStore files;

    JobSweep(JobStorage storage, JobStream stream, JobOffers offers, JobHandlers handlers, OwnerWork owners,
             JobSettings settings, JobClock clock, FileStore files) {
        this.storage = storage;
        this.stream = stream;
        this.offers = offers;
        this.handlers = handlers;
        this.owners = owners;
        this.settings = settings;
        this.clock = clock;
        this.files = files;
    }

    /** One sweep. Never throws: each failed step is logged by error type and retried at the next sweep. */
    public void sweep() {
        ensureGroups();
        step("lapses", this::takeBackLapsed);
        step("reoffers", this::reofferLost);
        step("offers", offers::offerWaiting);
        step("outputs", this::removeOrphanOutputs);
        trimStreams();
    }

    /** Creates the consumer group of each stream where it is missing. */
    public void ensureGroups() {
        for (Lane lane : Lane.values()) {
            try {
                if (stream.ensureGroup(lane.stream())) {
                    log.atInfo().addKeyValue("lane", lane.code()).log("job.group_created");
                }
            } catch (RuntimeException failure) {
                log.atWarn().addKeyValue("lane", lane.code()).addKeyValue("errorType", failure.getClass().getName())
                        .log("job.stream_unavailable");
            }
        }
    }

    void takeBackLapsed() {
        Instant now = clock.now();
        for (JobRow row : storage.separately(store -> store.lapsed(now, BATCH))) {
            try {
                takeBack(row, now);
            } catch (RuntimeException failure) {
                JobTrace.run(row.traceId(), () -> log.atWarn().addKeyValue("jobId", row.id())
                        .addKeyValue("errorType", failure.getClass().getName()).log("job.lapse_failed"));
            }
        }
    }

    private void takeBack(JobRow lapsed, Instant now) {
        Lane lane = Lane.of(lapsed.lane()).orElseThrow();
        Optional<JobHandler> handler = handlers.find(lapsed.kind());
        // No splice (Story 2.4): a job that delivered an event is never offered again, whatever its attempts.
        boolean delivered = handler.isPresent() && Boolean.TRUE.equals(JobTrace.call(lapsed.traceId(), () ->
                owners.read(lapsed.ownerId(), () -> handler.get().delivered(JobClaims.ref(lapsed)))));
        if (!delivered && lapsed.attempt() < settings.maxAttempts()) {
            JobRow waiting = lapsed.released(JobState.WAITING, JobProblems.CLAIM_LAPSED, now);
            if (storage.separately(store -> store.replace(lapsed, waiting))) {
                JobTrace.run(lapsed.traceId(), () -> log.atWarn().addKeyValue("jobId", lapsed.id())
                        .addKeyValue("claimId", lapsed.claimId()).addKeyValue("attempt", lapsed.attempt())
                        .log("job.claim_lapsed"));
                offers.offerDue(lane, lapsed.id());
            }
            return;
        }
        String code = delivered ? JobProblems.CLAIM_LAPSED : JobProblems.ATTEMPTS_EXHAUSTED;
        JobRow failed = lapsed.released(JobState.FAILED, code, now);
        boolean ended = handler.isEmpty()
                ? storage.separately(store -> store.replace(lapsed, failed))
                : JobTrace.call(lapsed.traceId(), () -> owners.call(lapsed.ownerId(), () -> {
                    JobRow current = storage.joined().findForUpdate(lapsed.id()).orElse(null);
                    if (current == null || !storage.joined().replace(lapsed, failed)) {
                        return false;
                    }
                    handler.get().failed(JobClaims.ref(lapsed), code);
                    return true;
                }));
        if (ended) {
            JobTrace.run(lapsed.traceId(), () -> log.atWarn().addKeyValue("jobId", lapsed.id())
                    .addKeyValue("attempt", lapsed.attempt()).addKeyValue("state", JobState.FAILED)
                    .addKeyValue("code", JobProblems.ATTEMPTS_EXHAUSTED).log("job.failed"));
            if (lane == Lane.INGESTION) {
                offers.offerNextIngestion();
            }
        }
    }

    void reofferLost() {
        Instant offeredBefore = clock.now().minus(settings.reofferAfter());
        List<JobRow> lost = storage.separately(store -> store.unclaimed(offeredBefore, BATCH));
        for (JobRow row : lost) {
            offers.reoffer(row);
        }
    }

    /** Removes the files and rows of outputs no handler adopted, once their job ended. */
    void removeOrphanOutputs() {
        Instant writingEndedBefore = clock.now().minus(WRITING_GRACE);
        for (JobOutputRow output : storage.separately(store -> store.orphanOutputs(writingEndedBefore, BATCH))) {
            try {
                files.deleteFile(output.ownerId(), output.id());
                storage.separately(store -> store.deleteOutput(output.id()));
                log.atInfo().addKeyValue("jobId", output.jobId()).addKeyValue("fileId", output.id())
                        .log("job.output_removed");
            } catch (RuntimeException failure) {
                log.atWarn().addKeyValue("jobId", output.jobId()).addKeyValue("fileId", output.id())
                        .addKeyValue("errorType", failure.getClass().getName()).log("job.output_not_removed");
            }
        }
    }

    /** Trims each stream to the messages of the last hour. */
    public void trimStreams() {
        Instant before = clock.now().minus(JobSettings.STREAM_RETENTION);
        for (Lane lane : Lane.values()) {
            try {
                long removed = stream.trimBefore(lane.stream(), before);
                if (removed > 0) {
                    log.atDebug().addKeyValue("lane", lane.code()).addKeyValue("removed", removed).log("job.stream_trimmed");
                }
            } catch (RuntimeException failure) {
                log.atWarn().addKeyValue("lane", lane.code()).addKeyValue("errorType", failure.getClass().getName())
                        .log("job.stream_unavailable");
            }
        }
    }

    private static void step(String name, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException failure) {
            log.atWarn().addKeyValue("step", name).addKeyValue("errorType", failure.getClass().getName())
                    .log("job.sweep_failed");
        }
    }
}
