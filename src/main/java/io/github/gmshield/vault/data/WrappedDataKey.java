package io.github.gmshield.vault.data;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One owner's data key, as stored: wrapped by the master key. Never the key itself.
 * Equality compares the bytes; {@code toString} shows their length only, never the wrapped key.
 */
public record WrappedDataKey(UUID ownerId, int version, byte[] wrapped) {

    @Override
    public boolean equals(Object other) {
        return other instanceof WrappedDataKey that
                && version == that.version
                && Objects.equals(ownerId, that.ownerId)
                && Arrays.equals(wrapped, that.wrapped);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ownerId, version, Arrays.hashCode(wrapped));
    }

    @Override
    public String toString() {
        return "WrappedDataKey[ownerId=" + ownerId + ", version=" + version + ", wrapped="
                + (wrapped == null ? 0 : wrapped.length) + " bytes]";
    }
}
