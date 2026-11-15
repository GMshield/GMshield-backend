package io.github.gmshield.vault.data;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of the test-mode table {@code vault_probe}: the NFR-1 gate's text, encrypted, and its piece's file id.
 * Equality compares the bytes; {@code toString} shows their lengths only, never the stored values.
 */
public record ProbeRow(UUID id, UUID ownerId, byte[] text, byte[] textFingerprint, UUID fileId) {

    @Override
    public boolean equals(Object other) {
        return other instanceof ProbeRow that
                && Objects.equals(id, that.id)
                && Objects.equals(ownerId, that.ownerId)
                && Arrays.equals(text, that.text)
                && Arrays.equals(textFingerprint, that.textFingerprint)
                && Objects.equals(fileId, that.fileId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, Arrays.hashCode(text), Arrays.hashCode(textFingerprint), fileId);
    }

    @Override
    public String toString() {
        return "ProbeRow[id=" + id + ", ownerId=" + ownerId + ", text=" + length(text) + " bytes, textFingerprint="
                + length(textFingerprint) + " bytes, fileId=" + fileId + "]";
    }

    private static int length(byte[] bytes) {
        return bytes == null ? 0 : bytes.length;
    }
}
