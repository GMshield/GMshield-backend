package io.github.gmshieldfixtures.cipher.notes.service;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/** Outside the vault: a digest of its own, which the cipher rule must refuse. */
public class HomemadeHash {

    public byte[] hash(byte[] value) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }
}
