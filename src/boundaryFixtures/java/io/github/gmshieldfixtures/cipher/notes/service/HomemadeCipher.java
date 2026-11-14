package io.github.gmshieldfixtures.cipher.notes.service;

import javax.crypto.spec.SecretKeySpec;

/** Outside the vault: a key of its own (any javax.crypto type), which the cipher rule must refuse. */
public class HomemadeCipher {

    public Object key(byte[] raw) {
        return new SecretKeySpec(raw, "AES");
    }
}
