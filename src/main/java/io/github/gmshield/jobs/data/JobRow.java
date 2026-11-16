package io.github.gmshield.jobs.data;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code job}. Ids, codes and times only: never content, input or result. The grant itself is never
 * stored, only its vault token hash. Lane and state are the stored words ({@code interactive}, {@code waiting}...).
 */
public record JobRow(
        UUID id, UUID ownerId, String kind, String lane, String state, int attempt, String traceId, Instant offeredAt,
        UUID claimId, byte[] grantHash, Instant claimRenewAt, Instant claimLapsesAt, String outcomeCode,
        Instant createdAt, Instant updatedAt) {

    /** A copy in another state, with its own update time. */
    public JobRow withState(String newState, Instant now) {
        return new JobRow(id, ownerId, kind, lane, newState, attempt, traceId, offeredAt, claimId, grantHash,
                claimRenewAt, claimLapsesAt, outcomeCode, createdAt, now);
    }

    /** A copy offered (again) at {@code now}. */
    public JobRow offered(Instant now) {
        return new JobRow(id, ownerId, kind, lane, "offered", attempt, traceId, now, claimId, grantHash, claimRenewAt,
                claimLapsesAt, outcomeCode, createdAt, now);
    }

    /** A copy running under a new claim: one more attempt, the new grant's hash, the claim's times. */
    public JobRow claimed(UUID newClaimId, byte[] newGrantHash, Instant renewAt, Instant lapsesAt, Instant now) {
        return new JobRow(id, ownerId, kind, lane, "running", attempt + 1, traceId, offeredAt, newClaimId, newGrantHash,
                renewAt, lapsesAt, outcomeCode, createdAt, now);
    }

    /** A copy whose live claim has new times. */
    public JobRow renewed(Instant renewAt, Instant lapsesAt, Instant now) {
        return new JobRow(id, ownerId, kind, lane, state, attempt, traceId, offeredAt, claimId, grantHash, renewAt,
                lapsesAt, outcomeCode, createdAt, now);
    }

    /**
     * A copy without a live claim, in {@code newState} ({@code waiting} again, or an end state with its code). The
     * claim id stays as a record of the last claim; the grant hash and the claim's times go, so no grant acts again.
     */
    public JobRow released(String newState, String code, Instant now) {
        return new JobRow(id, ownerId, kind, lane, newState, attempt, traceId, offeredAt, claimId, null, null, null,
                code, createdAt, now);
    }

    @Override
    public String toString() {
        return "JobRow[id=" + id + ", kind=" + kind + ", lane=" + lane + ", state=" + state + ", attempt=" + attempt + "]";
    }
}
