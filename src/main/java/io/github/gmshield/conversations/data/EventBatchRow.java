package io.github.gmshield.conversations.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code turn_event_batch}: a numbered batch of a claim ({@code claimId} and {@code batch} null for the
 * turn's final event), the sequences its events take ({@code lastSequence = firstSequence - 1} for none), and its events
 * as a vault ciphertext. {@code toString} names ids and numbers only.
 */
public record EventBatchRow(UUID id, UUID ownerId, UUID turnId, UUID claimId, Integer batch, long firstSequence,
                            long lastSequence, byte[] payload, Instant createdAt) {

    public EventBatchRow {
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EventBatchRow that && firstSequence == that.firstSequence
                && lastSequence == that.lastSequence && Objects.equals(id, that.id)
                && Objects.equals(ownerId, that.ownerId) && Objects.equals(turnId, that.turnId)
                && Objects.equals(claimId, that.claimId) && Objects.equals(batch, that.batch)
                && Arrays.equals(payload, that.payload) && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, turnId, claimId, batch, firstSequence, lastSequence, Arrays.hashCode(payload),
                createdAt);
    }

    @Override
    public String toString() {
        return "EventBatchRow[id=" + id + ", turnId=" + turnId + ", batch=" + batch + ", sequences=" + firstSequence
                + ".." + lastSequence + "]";
    }
}
