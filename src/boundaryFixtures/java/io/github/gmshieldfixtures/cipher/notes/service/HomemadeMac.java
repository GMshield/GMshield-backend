package io.github.gmshieldfixtures.cipher.notes.service;

import java.security.GeneralSecurityException;
import javax.crypto.Mac;

/** Outside the vault: a MAC of its own, which the cipher rule must refuse. */
public class HomemadeMac {

    public Mac mac() throws GeneralSecurityException {
        return Mac.getInstance("HmacSHA256");
    }
}
