package io.github.gmshield.library.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code shield} as stored (Story 6.1, V53): its campaign, its name (a vault ciphertext) and the keyed
 * fingerprint of that name, both null while the Shield is the campaign's unnamed first one, and its version, which a
 * rename names. Equality compares the bytes; {@code toString} shows ids, lengths and the version only, never a stored
 * value.
 */
public record ShieldRow(UUID id, UUID ownerId, UUID campaignId, byte[] name, byte[] nameFingerprint, long version,
                        Instant createdAt) {

    /** A campaign's unnamed first Shield, at version 0. */
    public static ShieldRow unnamed(UUID id, UUID ownerId, UUID campaignId, Instant createdAt) {
        return new ShieldRow(id, ownerId, campaignId, null, null, 0, createdAt);
    }

    /** Whether it still has no name of its own (the campaign's first Shield, never renamed). */
    public boolean unnamed() {
        return name == null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ShieldRow that
                && Objects.equals(id, that.id) && Objects.equals(ownerId, that.ownerId)
                && Objects.equals(campaignId, that.campaignId) && Arrays.equals(name, that.name)
                && Arrays.equals(nameFingerprint, that.nameFingerprint) && version == that.version
                && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, campaignId, Arrays.hashCode(name), Arrays.hashCode(nameFingerprint), version,
                createdAt);
    }

    @Override
    public String toString() {
        return "ShieldRow[id=" + id + ", ownerId=" + ownerId + ", campaignId=" + campaignId + ", name="
                + SystemRow.length(name) + " bytes, nameFingerprint=" + SystemRow.length(nameFingerprint)
                + " bytes, version=" + version + "]";
    }
}
