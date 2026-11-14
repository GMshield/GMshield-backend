package io.github.gmshield.vault.service;

import javax.crypto.SecretKey;

/**
 * The two secrets of the vault, held in memory only: the master key, which wraps every data key,
 * and the index key, from which the fingerprint and token-hash subkeys are derived. Its
 * {@code toString} never shows a key.
 */
final class VaultKeys {

    private final SecretKey master;
    private final byte[] index;

    VaultKeys(byte[] master, byte[] index) {
        this.master = VaultCrypto.aesKey(master);
        this.index = index.clone();
        if (this.index.length != VaultCrypto.KEY_BYTES) {
            throw new IllegalArgumentException("a vault key is 32 bytes");
        }
    }

    /** Two new random keys: test mode, a new pair at each start. */
    static VaultKeys random() {
        return new VaultKeys(VaultCrypto.randomBytes(VaultCrypto.KEY_BYTES), VaultCrypto.randomBytes(VaultCrypto.KEY_BYTES));
    }

    SecretKey master() {
        return master;
    }

    byte[] index() {
        return index;
    }

    @Override
    public String toString() {
        return "VaultKeys[hidden]";
    }
}
