package io.github.gmshield.jobs.service;

import io.github.gmshield.jobs.data.JobRow;
import io.github.gmshield.jobs.data.JobStorage;
import io.github.gmshield.jobs.data.JobStream;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Offers jobs on their streams. An offer moves the row to {@code offered} and adds the message in one transaction
 * of its own: when Redis fails, the transaction rolls back and the job stays as it was, for the sweep to offer
 * later. When the commit fails after the message was added, the worker's claim finds the job not offered and the
 * sweep offers it again: a message never makes a job run twice.
 *
 * <p>Interactive jobs are offered as soon as they are due. Ingestion jobs go out one at a time: none while another
 * is {@code offered} or {@code running}; the next one is the oldest of the owner after the owner offered last
 * (round robin over owner ids), under a lock of the lane so two decisions never overlap.
 */
@Component
class JobOffers {

    /** How many waiting interactive jobs one sweep offers at most. */
    static final int BATCH = 100;
    private static final Logger log = LoggerFactory.getLogger(JobOffers.class);
    /** Owner ids in one fixed order: unsigned, byte by byte, as Postgres orders uuid. */
    static final Comparator<UUID> OWNER_ORDER = Comparator.comparing(UUID::getMostSignificantBits, Long::compareUnsigned)
            .thenComparing(UUID::getLeastSignificantBits, Long::compareUnsigned);

    private final JobStorage storage;
    private final JobStream stream;
    private final JobClock clock;

    JobOffers(JobStorage storage, JobStream stream, JobClock clock) {
        this.storage = storage;
        this.stream = stream;
        this.clock = clock;
    }

    /** What is due after a job of the lane was created or went back to waiting: that job, or the lane's next one. */
    void offerDue(Lane lane, UUID jobId) {
        if (lane == Lane.INGESTION) {
            offerNextIngestion();
        } else {
            offerInteractive(jobId);
        }
    }

    /** Offers one waiting interactive job; empty when it is no longer waiting or the offer failed. */
    Optional<JobRow> offerInteractive(UUID jobId) {
        return offer(Lane.INTERACTIVE, store -> store.find(jobId).filter(row -> JobState.WAITING.equals(row.state())));
    }

    /** Offers the ingestion lane's next job, unless one is already offered or running. */
    Optional<JobRow> offerNextIngestion() {
        return offer(Lane.INGESTION, store -> {
            store.lockLane(Lane.INGESTION.code());
            if (store.busy(Lane.INGESTION.code())) {
                return Optional.empty();
            }
            return nextInTurn(store.oldestWaitingPerOwner(Lane.INGESTION.code()),
                    store.lastOfferedOwner(Lane.INGESTION.code()));
        });
    }

    /** Offers again a job offered long ago and never claimed: Redis lost its message. */
    Optional<JobRow> reoffer(JobRow lost) {
        Lane lane = Lane.of(lost.lane()).orElseThrow();
        return offer(lane, store -> store.find(lost.id()).filter(row -> JobState.OFFERED.equals(row.state())
                && row.attempt() == lost.attempt() && row.offeredAt() != null && !row.offeredAt().isAfter(lost.offeredAt())))
                .map(row -> {
                    JobTrace.run(row.traceId(), () -> log.atInfo().addKeyValue("jobId", row.id())
                            .addKeyValue("lane", row.lane()).log("job.reoffered"));
                    return row;
                });
    }

    /** Offers every waiting interactive job (up to a batch) and the ingestion lane's next one. */
    void offerWaiting() {
        for (JobRow row : storage.separately(store -> store.waiting(Lane.INTERACTIVE.code(), BATCH))) {
            offerInteractive(row.id());
        }
        offerNextIngestion();
    }

    /**
     * The job whose turn it is among the oldest waiting job of each owner: the first owner after the one offered
     * last, in owner order, wrapping around; with no owner offered before, the oldest job.
     */
    static Optional<JobRow> nextInTurn(List<JobRow> oldestPerOwner, Optional<UUID> lastOwner) {
        if (oldestPerOwner.isEmpty()) {
            return Optional.empty();
        }
        if (lastOwner.isEmpty()) {
            return oldestPerOwner.stream().min(Comparator.comparing(JobRow::createdAt).thenComparing(JobRow::id, OWNER_ORDER));
        }
        List<JobRow> byOwner = oldestPerOwner.stream().sorted(Comparator.comparing(JobRow::ownerId, OWNER_ORDER)).toList();
        UUID last = lastOwner.get();
        return byOwner.stream().filter(row -> OWNER_ORDER.compare(row.ownerId(), last) > 0).findFirst()
                .or(() -> Optional.of(byOwner.getFirst()));
    }

    private Optional<JobRow> offer(Lane lane, Function<io.github.gmshield.jobs.data.JobStore, Optional<JobRow>> pick) {
        Instant now = clock.now();
        Optional<JobRow> offered;
        try {
            offered = storage.separately(store -> pick.apply(store).flatMap(row -> {
                JobRow next = row.offered(now);
                if (!store.replace(row, next)) {
                    return Optional.empty();
                }
                stream.offer(lane.stream(), row.id());
                return Optional.of(next);
            }));
        } catch (RuntimeException failure) {
            // Redis or the database failed: nothing changed; the sweep offers the job later.
            log.atWarn().addKeyValue("lane", lane.code()).addKeyValue("errorType", failure.getClass().getName())
                    .log("job.offer_failed");
            return Optional.empty();
        }
        offered.ifPresent(row -> JobTrace.run(row.traceId(), () -> log.atInfo().addKeyValue("jobId", row.id())
                .addKeyValue("lane", row.lane()).addKeyValue("attempt", row.attempt()).log("job.offered")));
        return offered;
    }
}
