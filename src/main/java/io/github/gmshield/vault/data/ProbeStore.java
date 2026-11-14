package io.github.gmshield.vault.data;

import java.util.Optional;
import java.util.UUID;

/** The test-mode table {@code vault_probe} (created only by {@code db/test-mode-migration}). */
public interface ProbeStore {

    void insert(ProbeRow row);

    Optional<ProbeRow> find(UUID id);
}
