package io.github.gmshield.library.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code roll} as stored (Story 6.4, V56): its Shield, the key the browser made for it (unique per owner),
 * its content (one vault ciphertext of the formula, the results and the label) and the browser's time of the roll.
 * Equality compares the bytes; {@code toString} shows ids, the content's length and the roll's time only, never a stored
 * value.
 */
public record RollRow(UUID id, UUID ownerId, UUID shieldId, UUID rollKey, byte[] content, Instant rolledAt,
                      Instant createdAt) {

    @Override
    public boolean equals(Object other) {
        return other instanceof RollRow that
                && Objects.equals(id, that.id) && Objects.equals(ownerId, that.ownerId)
                && Objects.equals(shieldId, that.shieldId) && Objects.equals(rollKey, that.rollKey)
                && Arrays.equals(content, that.content) && Objects.equals(rolledAt, that.rolledAt)
                && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, shieldId, rollKey, Arrays.hashCode(content), rolledAt, createdAt);
    }

    @Override
    public String toString() {
        return "RollRow[id=" + id + ", ownerId=" + ownerId + ", shieldId=" + shieldId + ", rollKey=" + rollKey
                + ", content=" + SystemRow.length(content) + " bytes, rolledAt=" + rolledAt + "]";
    }
}
