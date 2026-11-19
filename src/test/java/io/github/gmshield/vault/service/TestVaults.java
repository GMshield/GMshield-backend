package io.github.gmshield.vault.service;

import io.github.gmshield.vault.data.InMemoryDataKeyStore;

/** A working vault with random keys and in-memory data keys, for other modules' plain unit tests. */
public final class TestVaults {

    private TestVaults() {
    }

    public static Vault vault() {
        return VaultFixtures.vault(new InMemoryDataKeyStore(), VaultFixtures.keys());
    }

    /**
     * A working vault, as {@link #vault()}, that also keeps every token key buffer it hands out in {@code handed}, so a
     * test can see that the caller zeroed it.
     */
    public static Vault handingOutTokenKeys(java.util.List<byte[]> handed) {
        VaultKeys keys = VaultFixtures.keys();
        return new Vault(new DataKeys(new InMemoryDataKeyStore(), keys), keys) {
            @Override
            public byte[] ownerTokenKey(java.util.UUID ownerId) {
                byte[] key = super.ownerTokenKey(ownerId);
                handed.add(key);
                return key;
            }
        };
    }

    /** The encrypted file store of {@code vault} under {@code root}. */
    public static FileStore files(Vault vault, java.nio.file.Path root) {
        return VaultFixtures.files(vault, root);
    }
}
