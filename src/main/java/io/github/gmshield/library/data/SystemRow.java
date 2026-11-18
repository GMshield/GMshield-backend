package io.github.gmshield.library.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code system} as stored: the name is a vault ciphertext beside its keyed fingerprint. Equality
 * compares the bytes; {@code toString} shows ids and lengths only, never a stored value.
 */
public record SystemRow(UUID id, UUID ownerId, byte[] name, byte[] nameFingerprint, Instant createdAt) {

    @Override
    public boolean equals(Object other) {
        return other instanceof SystemRow that
                && Objects.equals(id, that.id) && Objects.equals(ownerId, that.ownerId)
                && Arrays.equals(name, that.name) && Arrays.equals(nameFingerprint, that.nameFingerprint)
                && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, Arrays.hashCode(name), Arrays.hashCode(nameFingerprint), createdAt);
    }

    @Override
    public String toString() {
        return "SystemRow[id=" + id + ", ownerId=" + ownerId + ", name=" + length(name) + " bytes, nameFingerprint="
                + length(nameFingerprint) + " bytes]";
    }

    static int length(byte[] bytes) {
        return bytes == null ? 0 : bytes.length;
    }
}
