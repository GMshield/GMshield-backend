package io.github.gmshield.vault.data;

import java.util.UUID;

/** One row of the test-mode table {@code vault_probe}: the NFR-1 gate's text, encrypted, and its piece's file id. */
public record ProbeRow(UUID id, UUID ownerId, byte[] text, byte[] textFingerprint, UUID fileId) {
}
