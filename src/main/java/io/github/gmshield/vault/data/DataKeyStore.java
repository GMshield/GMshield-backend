package io.github.gmshield.vault.data;

import java.util.Optional;
import java.util.UUID;

/** Where wrapped data keys are kept: the table {@code vault_data_key}. */
public interface DataKeyStore {

    /** The owner's newest data key, if the owner has one. */
    Optional<WrappedDataKey> latest(UUID ownerId);

    Optional<WrappedDataKey> find(UUID ownerId, int version);

    /**
     * Stores the key unless that owner and version exist already (another request may have won). Commits in
     * its own transaction, whatever the caller's transaction does afterwards.
     */
    void insertIfAbsent(WrappedDataKey key);
}
