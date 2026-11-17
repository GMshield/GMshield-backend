package io.github.gmshield.materials.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code material} as stored: the name is a vault ciphertext. Equality compares the bytes;
 * {@code toString} shows ids and lengths only, never a stored value.
 */
public record MaterialRow(UUID id, UUID ownerId, byte[] name, Instant createdAt) {

    @Override
    public boolean equals(Object other) {
        return other instanceof MaterialRow that
                && Objects.equals(id, that.id) && Objects.equals(ownerId, that.ownerId)
                && Arrays.equals(name, that.name) && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, Arrays.hashCode(name), createdAt);
    }

    @Override
    public String toString() {
        return "MaterialRow[id=" + id + ", ownerId=" + ownerId + ", name=" + (name == null ? 0 : name.length) + " bytes]";
    }
}
