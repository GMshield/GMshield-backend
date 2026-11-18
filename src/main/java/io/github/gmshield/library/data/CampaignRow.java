package io.github.gmshield.library.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code campaign} as stored: the name is a vault ciphertext. Equality compares the bytes;
 * {@code toString} shows ids and lengths only, never a stored value.
 */
public record CampaignRow(UUID id, UUID ownerId, UUID systemId, byte[] name, Instant createdAt) {

    @Override
    public boolean equals(Object other) {
        return other instanceof CampaignRow that
                && Objects.equals(id, that.id) && Objects.equals(ownerId, that.ownerId)
                && Objects.equals(systemId, that.systemId) && Arrays.equals(name, that.name)
                && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, systemId, Arrays.hashCode(name), createdAt);
    }

    @Override
    public String toString() {
        return "CampaignRow[id=" + id + ", ownerId=" + ownerId + ", systemId=" + systemId + ", name="
                + SystemRow.length(name) + " bytes]";
    }
}
