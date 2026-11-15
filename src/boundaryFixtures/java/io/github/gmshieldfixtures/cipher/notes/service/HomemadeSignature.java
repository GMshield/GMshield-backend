package io.github.gmshieldfixtures.cipher.notes.service;

import java.security.GeneralSecurityException;
import java.security.Signature;

/** Outside the vault: a signature of its own, which the cipher rule must refuse. */
public class HomemadeSignature {

    public Signature signature() throws GeneralSecurityException {
        return Signature.getInstance("Ed25519");
    }
}
