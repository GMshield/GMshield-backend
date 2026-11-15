package io.github.gmshieldfixtures.cipher.vault.service;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Cipher;

/** Inside the vault: allowed to use a cipher and a digest. */
public class AllowedCipher {

    public Cipher cipher() throws GeneralSecurityException {
        return Cipher.getInstance("AES/GCM/NoPadding");
    }

    public MessageDigest digest() throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256");
    }
}
