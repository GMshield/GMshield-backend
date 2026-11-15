package io.github.gmshield.vault.data;

import java.util.UUID;

/** One owner's data key, as stored: wrapped by the master key. Never the key itself. */
public record WrappedDataKey(UUID ownerId, int version, byte[] wrapped) {
}
