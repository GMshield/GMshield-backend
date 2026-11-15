package io.github.gmshieldfixtures.cipher.notes.service;

import java.security.SecureRandom;

/** Outside the vault, without a cipher: SecureRandom alone is allowed. */
public class PlainNotes {

    public int roll() {
        return new SecureRandom().nextInt(20) + 1;
    }
}
