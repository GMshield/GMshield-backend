package io.github.gmshield.library.data;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code system} as stored: the name is a vault ciphertext beside its keyed fingerprint; from Story 3.1 the
 * edition and the description are vault ciphertexts too (null when the GM gave none), and {@code version} is the row's
 * version, which an edit names. Equality compares the bytes; {@code toString} shows ids, lengths and the version only,
 * never a stored value.
 */
public record SystemRow(UUID id, UUID ownerId, byte[] name, byte[] nameFingerprint, byte[] edition, byte[] description,
                        long version, Instant createdAt) {

    /** A new row with a name only (a first sign-in's starter system): no edition, no description, version 0. */
    public SystemRow(UUID id, UUID ownerId, byte[] name, byte[] nameFingerprint, Instant createdAt) {
        this(id, ownerId, name, nameFingerprint, null, null, 0, createdAt);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SystemRow that
                && Objects.equals(id, that.id) && Objects.equals(ownerId, that.ownerId)
                && Arrays.equals(name, that.name) && Arrays.equals(nameFingerprint, that.nameFingerprint)
                && Arrays.equals(edition, that.edition) && Arrays.equals(description, that.description)
                && version == that.version && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, Arrays.hashCode(name), Arrays.hashCode(nameFingerprint), Arrays.hashCode(edition),
                Arrays.hashCode(description), version, createdAt);
    }

    @Override
    public String toString() {
        return "SystemRow[id=" + id + ", ownerId=" + ownerId + ", name=" + length(name) + " bytes, nameFingerprint="
                + length(nameFingerprint) + " bytes, edition=" + length(edition) + " bytes, description="
                + length(description) + " bytes, version=" + version + "]";
    }

    static int length(byte[] bytes) {
        return bytes == null ? 0 : bytes.length;
    }

    static byte[] copy(byte[] bytes) {
        return bytes == null ? null : bytes.clone();
    }

    /**
     * An id as 16 bytes, the name fingerprint of a row being deleted (Story 6.2 part B, a Shield): never a keyed
     * fingerprint's 32, so it frees the name and takes no other.
     */
    public static byte[] idBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits())
                .array();
    }
}
