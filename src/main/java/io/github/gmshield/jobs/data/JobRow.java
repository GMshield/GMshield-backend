package io.github.gmshield.jobs.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code job}. Ids, codes and times only: never content, input or result. The grant itself is never
 * stored, only its vault token hash. Lane and state are the stored words ({@code interactive}, {@code waiting}...).
 * From Story 2.10, a kind that calls a model records the provider, model and tier of its last claim (null for every
 * other kind, and before the first claim).
 */
public record JobRow(
        UUID id, UUID ownerId, String kind, String lane, String state, int attempt, String traceId, Instant offeredAt,
        UUID claimId, byte[] grantHash, Instant claimRenewAt, Instant claimLapsesAt, String outcomeCode,
        Instant createdAt, Instant updatedAt, String modelProvider, String modelId, String modelTier) {

    /** A copy in another state, with its own update time. */
    public JobRow withState(String newState, Instant now) {
        return new JobRow(id, ownerId, kind, lane, newState, attempt, traceId, offeredAt, claimId, grantHash,
                claimRenewAt, claimLapsesAt, outcomeCode, createdAt, now, modelProvider, modelId, modelTier);
    }

    /** A copy offered (again) at {@code now}. */
    public JobRow offered(Instant now) {
        return new JobRow(id, ownerId, kind, lane, "offered", attempt, traceId, now, claimId, grantHash, claimRenewAt,
                claimLapsesAt, outcomeCode, createdAt, now, modelProvider, modelId, modelTier);
    }

    /** A copy running under a new claim: one more attempt, the new grant's hash, the claim's times. */
    public JobRow claimed(UUID newClaimId, byte[] newGrantHash, Instant renewAt, Instant lapsesAt, Instant now) {
        return new JobRow(id, ownerId, kind, lane, "running", attempt + 1, traceId, offeredAt, newClaimId, newGrantHash,
                renewAt, lapsesAt, outcomeCode, createdAt, now, modelProvider, modelId, modelTier);
    }

    /** A copy whose attempt calls this model (Story 2.10): each claim overwrites the last one's. */
    public JobRow withModel(String provider, String model, String tier) {
        return new JobRow(id, ownerId, kind, lane, state, attempt, traceId, offeredAt, claimId, grantHash, claimRenewAt,
                claimLapsesAt, outcomeCode, createdAt, updatedAt, provider, model, tier);
    }

    /** A copy whose live claim has new times. */
    public JobRow renewed(Instant renewAt, Instant lapsesAt, Instant now) {
        return new JobRow(id, ownerId, kind, lane, state, attempt, traceId, offeredAt, claimId, grantHash, renewAt,
                lapsesAt, outcomeCode, createdAt, now, modelProvider, modelId, modelTier);
    }

    /**
     * A copy without a live claim, in {@code newState} ({@code waiting} again, or an end state with its code). The
     * claim id stays as a record of the last claim; the grant hash and the claim's times go, so no grant acts again.
     * The model stays too: once the job ends, it names the model that answered.
     */
    public JobRow released(String newState, String code, Instant now) {
        return new JobRow(id, ownerId, kind, lane, newState, attempt, traceId, offeredAt, claimId, null, null, null,
                code, createdAt, now, modelProvider, modelId, modelTier);
    }

    /** Compares the grant hash by its bytes, not by array identity (SonarQube S6218). */
    @Override
    public boolean equals(Object other) {
        return other instanceof JobRow that
                && attempt == that.attempt && Objects.equals(id, that.id) && Objects.equals(ownerId, that.ownerId)
                && Objects.equals(kind, that.kind) && Objects.equals(lane, that.lane) && Objects.equals(state, that.state)
                && Objects.equals(traceId, that.traceId) && Objects.equals(offeredAt, that.offeredAt)
                && Objects.equals(claimId, that.claimId) && Arrays.equals(grantHash, that.grantHash)
                && Objects.equals(claimRenewAt, that.claimRenewAt) && Objects.equals(claimLapsesAt, that.claimLapsesAt)
                && Objects.equals(outcomeCode, that.outcomeCode) && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(updatedAt, that.updatedAt) && Objects.equals(modelProvider, that.modelProvider)
                && Objects.equals(modelId, that.modelId) && Objects.equals(modelTier, that.modelTier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, kind, lane, state, attempt, traceId, offeredAt, claimId, Arrays.hashCode(grantHash),
                claimRenewAt, claimLapsesAt, outcomeCode, createdAt, updatedAt, modelProvider, modelId, modelTier);
    }

    @Override
    public String toString() {
        return "JobRow[id=" + id + ", kind=" + kind + ", lane=" + lane + ", state=" + state + ", attempt=" + attempt + "]";
    }
}
