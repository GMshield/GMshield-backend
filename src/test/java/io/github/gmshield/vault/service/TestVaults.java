package io.github.gmshield.vault.service;

import io.github.gmshield.vault.data.InMemoryDataKeyStore;

/** A working vault with random keys and in-memory data keys, for other modules' plain unit tests. */
public final class TestVaults {

    private TestVaults() {
    }

    public static Vault vault() {
        return VaultFixtures.vault(new InMemoryDataKeyStore(), VaultFixtures.keys());
    }
}
