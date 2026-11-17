package io.github.gmshield.jobs.data;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The table {@code job}. A change is a {@link #replace} that succeeds only while the row is still exactly in the
 * state, attempt and claim it was read in, so two writers never both move a job. Times always come from the
 * caller: the database's own clock is never used.
 */
public interface JobStore {

    void insert(JobRow row);

    Optional<JobRow> find(UUID id);

    /** The row, locked until the end of the transaction ({@code for update}). */
    Optional<JobRow> findForUpdate(UUID id);

    /** Writes {@code next} if the stored row still has {@code expected}'s state, attempt and claim id. */
    boolean replace(JobRow expected, JobRow next);

    /** Up to {@code limit} {@code waiting} jobs of a lane, oldest first. */
    List<JobRow> waiting(String lane, int limit);

    /** For each owner with a {@code waiting} job in the lane, that owner's oldest one. */
    List<JobRow> oldestWaitingPerOwner(String lane);

    /** The owner of the job of the lane offered last, if any job of the lane was ever offered. */
    Optional<UUID> lastOfferedOwner(String lane);

    /** Whether a job of the lane is {@code offered} or {@code running}. */
    boolean busy(String lane);

    /** Up to {@code limit} {@code running} jobs whose claim lapsed at or before {@code now}. */
    List<JobRow> lapsed(Instant now, int limit);

    /** Up to {@code limit} {@code offered} jobs offered at or before {@code offeredBefore}, never claimed since. */
    List<JobRow> unclaimed(Instant offeredBefore, int limit);

    /** Holds the lane's lock until the end of the transaction: one offer decision of the lane at a time. */
    void lockLane(String lane);

    // --- job_output (Story 1.15)

    void insertOutput(JobOutputRow row);

    Optional<JobOutputRow> findOutput(UUID id);

    /** A {@code writing} output becomes {@code stored} with its size and piece count; false when it is not writing. */
    boolean finishOutput(UUID id, long sizeBytes, int pieceCount, Instant now);

    /** The bytes of the {@code stored} outputs of one claim of a job. */
    long claimOutputBytes(UUID jobId, UUID claimId);

    /** Deletes an output's row whatever its state; false when there is none. */
    boolean deleteOutput(UUID id);

    /** Deletes a {@code stored} output of the job (its adoption); false when there is no such row. */
    boolean adoptOutput(UUID id, UUID jobId);

    /**
     * Up to {@code limit} outputs whose job has ended ({@code done}, {@code failed} or {@code cancelled}): every
     * {@code stored} one, and a {@code writing} one once its job ended at or before {@code writingEndedBefore}.
     */
    List<JobOutputRow> orphanOutputs(Instant writingEndedBefore, int limit);
}
