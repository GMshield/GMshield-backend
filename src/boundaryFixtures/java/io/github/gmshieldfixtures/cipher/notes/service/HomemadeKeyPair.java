package io.github.gmshieldfixtures.cipher.notes.service;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

/** Outside the vault: a key pair of its own, which the cipher rule must refuse. */
public class HomemadeKeyPair {

    public KeyPair pair() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }
}
