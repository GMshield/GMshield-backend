package io.github.gmshield.vault.service;

import io.github.gmshield.vault.data.DataKeyStore;
import java.nio.file.Path;

/** Builds the vault from its parts, as Spring does, for plain unit tests. */
final class VaultFixtures {

    private VaultFixtures() {
    }

    static VaultKeys keys() {
        return VaultKeys.random();
    }

    static Vault vault(DataKeyStore store, VaultKeys keys) {
        return new Vault(new DataKeys(store, keys), keys);
    }

    static FileStore files(Vault vault, Path root) {
        return new FileStore(vault, root);
    }
}
