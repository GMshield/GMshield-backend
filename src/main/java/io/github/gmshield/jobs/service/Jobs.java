package io.github.gmshield.jobs.service;

import io.github.gmshield.jobs.data.JobOutputRow;
import io.github.gmshield.jobs.data.JobRow;
import io.github.gmshield.jobs.data.JobStorage;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.shared.service.TraceContext;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Creates jobs (Story 1.12). A job is written in the caller's transaction, as the work of the owner bound in the
 * {@link OwnerContext}: when that transaction rolls back there is no job and no message. After the commit the job
 * is offered on its lane's stream (an interactive job at once; an ingestion job when the lane is free). If Redis
 * fails then, the job is still recorded and the sweep offers it. A job can be cancelled in its owner's transaction
 * (Story 1.16), and its row locked there (Story 2.6). Before a deletion cancels jobs, it reads when their claims lapse
 * ({@link #claimLapse}, Story 3.1 part B).
 */
@Service
public class Jobs {

    private static final Logger log = LoggerFactory.getLogger(Jobs.class);
    /** The states a job can be cancelled in: it has not ended. */
    private static final Set<String> CANCELLABLE = Set.of(JobState.WAITING, JobState.OFFERED, JobState.RUNNING);

    private final JobStorage storage;
    private final JobHandlers handlers;
    private final JobOffers offers;
    private final JobClock clock;

    Jobs(JobStorage storage, JobHandlers handlers, JobOffers offers, JobClock clock) {
        this.storage = storage;
        this.handlers = handlers;
        this.offers = offers;
        this.clock = clock;
    }

    /** {@link #create(String, Lane, Consumer)} on the lane of the kind's handler. */
    public JobRef create(String kind, Consumer<JobRef> input) {
        JobHandler handler = handlers.find(kind).orElseThrow(() -> new IllegalArgumentException("jobs.unknown_kind"));
        return create(kind, handler.lane(), input);
    }

    /**
     * Records a job of a registered kind for the bound owner, inside the caller's transaction, and offers it after
     * the commit. {@code input} runs right after the row is written, in the same transaction, with the new job:
     * it is where the caller records what the handler hands to the worker at claim time, in its own tables.
     */
    public JobRef create(String kind, Lane lane, Consumer<JobRef> input) {
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(input, "input");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("jobs.transaction_required");
        }
        if (handlers.find(kind).isEmpty()) {
            throw new IllegalArgumentException("jobs.unknown_kind");
        }
        java.util.UUID owner = OwnerContext.require();
        String traceId = TraceContext.current();
        if (TraceContext.NONE.equals(traceId)) {
            traceId = TraceContext.newTraceId();
        }
        Instant now = clock.now();
        JobRow row = new JobRow(Ids.newId(), owner, kind, lane.code(), JobState.WAITING, 0, traceId, null, null, null,
                null, null, null, now, now, null, null, null);
        storage.joined().insert(row);
        JobRef job = new JobRef(row.id(), owner, kind, lane, 0, traceId);
        input.accept(job);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    offers.offerDue(lane, job.id());
                } catch (RuntimeException failure) {
                    // Never the caller's failure: the job is recorded, and the sweep offers it.
                    log.atWarn().addKeyValue("jobId", job.id()).addKeyValue("errorType", failure.getClass().getName())
                            .log("job.offer_failed");
                }
            }
        });
        JobTrace.run(traceId, () -> log.atInfo().addKeyValue("jobId", job.id()).addKeyValue("kind", kind)
                .addKeyValue("lane", lane.code()).addKeyValue("ownerId", owner).log("job.created"));
        return job;
    }

    /**
     * Adopts a file the worker sent back for {@code job} (Story 1.15), inside the caller's transaction (a handler's
     * {@link JobHandler#completed}): the output's row goes, so the jobs sweep never removes the file, which from
     * now on belongs to the caller. Empty when {@code fileId} is not a whole output of this job (unknown, another
     * job's, still being written, or adopted already); the caller decides what that means.
     */
    public Optional<JobOutput> adoptOutput(JobRef job, UUID fileId) {
        Objects.requireNonNull(job, "job");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("jobs.transaction_required");
        }
        if (fileId == null) {
            return Optional.empty();
        }
        Optional<JobOutputRow> found = storage.joined().findOutput(fileId)
                .filter(row -> row.jobId().equals(job.id()) && row.ownerId().equals(job.ownerId())
                        && JobOutputRow.STORED.equals(row.state()));
        if (found.isEmpty() || !storage.joined().adoptOutput(fileId, job.id())) {
            return Optional.empty();
        }
        JobOutputRow row = found.get();
        log.atDebug().addKeyValue("jobId", job.id()).addKeyValue("fileId", fileId).log("job.output_adopted");
        return Optional.of(new JobOutput(row.id(), row.sizeBytes(), row.pieceCount()));
    }

    /**
     * Those of {@code jobIds} that are jobs of the bound owner now {@code offered} or {@code running} (Story 1.17), in
     * one read joined to the caller's transaction: another owner's job, an unknown one or an ended one is left out.
     */
    public Set<UUID> inProgress(Collection<UUID> jobIds) {
        UUID owner = OwnerContext.require();
        return jobIds.isEmpty() ? Set.of() : Set.copyOf(storage.joined().inProgress(Set.copyOf(jobIds), owner));
    }

    /**
     * Those of {@code jobIds} that are jobs of the bound owner that have not ended ({@code waiting}, {@code offered} or
     * {@code running}; Story 2.1), in one read joined to the caller's transaction: another owner's job, an unknown one
     * or an ended one is left out.
     */
    public Set<UUID> unended(Collection<UUID> jobIds) {
        UUID owner = OwnerContext.require();
        return jobIds.isEmpty() ? Set.of() : Set.copyOf(storage.joined().unended(Set.copyOf(jobIds), owner));
    }

    /**
     * Those of {@code jobIds} that are jobs of the bound owner that ended {@code failed} (indexing at worker start), in
     * one read joined to the caller's transaction: another owner's job, an unknown one or one in any other state is
     * left out.
     */
    public Set<UUID> failed(Collection<UUID> jobIds) {
        UUID owner = OwnerContext.require();
        return jobIds.isEmpty() ? Set.of() : Set.copyOf(storage.joined().failed(Set.copyOf(jobIds), owner));
    }

    /**
     * The ids of the bound owner's jobs of {@code kind} that ended {@code failed}, in one read joined to the caller's
     * transaction (the index retry sweep).
     */
    public Set<UUID> failedOfKind(String kind) {
        Objects.requireNonNull(kind, "kind");
        UUID owner = OwnerContext.require();
        return Set.copyOf(storage.joined().failedOfKind(kind, owner));
    }

    /**
     * The owners, of every owner, with a job of {@code kind} that ended {@code failed}, in one read of its own: ids only,
     * for a sweep that then visits each one as that owner (indexing at worker start).
     */
    public List<UUID> ownersWithFailed(String kind) {
        Objects.requireNonNull(kind, "kind");
        return storage.separately(store -> store.ownersWithFailed(kind));
    }

    /**
     * Locks the row of a job of the bound owner (Story 2.6), inside the caller's transaction, and answers its state:
     * the same lock its event batches and its outcome take first, so what the caller does next is strictly ordered
     * with them. Empty when the job is unknown or another owner's.
     */
    public Optional<String> lock(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("jobs.transaction_required");
        }
        UUID owner = OwnerContext.require();
        return storage.joined().findForUpdate(jobId).filter(row -> row.ownerId().equals(owner)).map(JobRow::state);
    }

    /**
     * Locks every job of {@code jobIds} (Story 3.1 part B), in the caller's transaction, and answers the latest time a
     * live claim of one of them lapses, counting only the bound owner's {@code running} jobs; empty when none is. The
     * caller cancels them next ({@link #cancel}), which ends a claim at once, but a worker that still runs it may act
     * until then (at most the claim lapse after its last renewal); the locks keep any claim from coming in between.
     */
    public Optional<Instant> claimLapse(Collection<UUID> jobIds) {
        Objects.requireNonNull(jobIds, "jobIds");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("jobs.transaction_required");
        }
        UUID owner = OwnerContext.require();
        Instant latest = null;
        // In one order, so two callers locking overlapping jobs never wait on each other.
        for (UUID jobId : jobIds.stream().distinct().sorted().toList()) {
            Optional<JobRow> found = storage.joined().findForUpdate(jobId)
                    .filter(row -> row.ownerId().equals(owner) && JobState.RUNNING.equals(row.state()));
            Instant lapses = found.map(JobRow::claimLapsesAt).orElse(null);
            if (lapses != null && (latest == null || lapses.isAfter(latest))) {
                latest = lapses;
            }
        }
        return Optional.ofNullable(latest);
    }

    /**
     * Cancels a job of the bound owner (Story 1.16), inside the caller's transaction: a {@code waiting},
     * {@code offered} or {@code running} job becomes {@code cancelled}. A running job's claim ends with it, so its
     * later renewal, output or report gets the refusal of any ended claim ({@code job.grant_refused}); its outputs go
     * with the jobs sweep. When the lane was busy with it, the ingestion lane's next job is offered after the commit.
     * False when the job is unknown, another owner's, or ended already.
     */
    public boolean cancel(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("jobs.transaction_required");
        }
        UUID owner = OwnerContext.require();
        Optional<JobRow> found = storage.joined().findForUpdate(jobId)
                .filter(row -> row.ownerId().equals(owner) && CANCELLABLE.contains(row.state()));
        if (found.isEmpty()) {
            return false;
        }
        JobRow row = found.get();
        if (!storage.joined().replace(row, row.released(JobState.CANCELLED, null, clock.now()))) {
            return false;
        }
        boolean heldTheLane = !JobState.WAITING.equals(row.state());
        if (heldTheLane && Lane.of(row.lane()).orElse(null) == Lane.INGESTION) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        offers.offerNextIngestion();
                    } catch (RuntimeException failure) {
                        // Never the caller's failure: the sweep offers the lane's next job.
                        log.atWarn().addKeyValue("errorType", failure.getClass().getName()).log("job.offer_failed");
                    }
                }
            });
        }
        JobTrace.run(row.traceId(), () -> log.atInfo().addKeyValue("jobId", jobId).addKeyValue("state", row.state())
                .addKeyValue("attempt", row.attempt()).log("job.cancelled"));
        return true;
    }
}
