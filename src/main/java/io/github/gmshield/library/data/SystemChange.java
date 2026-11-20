package io.github.gmshield.library.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * An edit of a system as it is stored (Story 3.1): the row, the version the edit was made on, and every stored value
 * after it (vault ciphertexts and the name's fingerprint; null for no edition or no description). Equality compares the
 * bytes; {@code toString} shows the id, lengths and the version only, never a stored value.
 */
public record SystemChange(UUID id, long version, byte[] name, byte[] nameFingerprint, byte[] edition,
                           byte[] description, Instant at) {

    @Override
    public boolean equals(Object other) {
        return other instanceof SystemChange that
                && Objects.equals(id, that.id) && version == that.version
                && Arrays.equals(name, that.name) && Arrays.equals(nameFingerprint, that.nameFingerprint)
                && Arrays.equals(edition, that.edition) && Arrays.equals(description, that.description)
                && Objects.equals(at, that.at);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, version, Arrays.hashCode(name), Arrays.hashCode(nameFingerprint), Arrays.hashCode(edition),
                Arrays.hashCode(description), at);
    }

    @Override
    public String toString() {
        return "SystemChange[id=" + id + ", version=" + version + ", name=" + SystemRow.length(name) + " bytes, edition="
                + SystemRow.length(edition) + " bytes, description=" + SystemRow.length(description) + " bytes]";
    }
}
