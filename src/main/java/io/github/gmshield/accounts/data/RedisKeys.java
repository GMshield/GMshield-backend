package io.github.gmshield.accounts.data;

import java.util.HexFormat;
import java.util.UUID;

/** The Redis key names of the accounts module. Every key is under {@code gmshield:}, the backend's ACL. */
final class RedisKeys {

    static final String SIGN_IN = "gmshield:sign-in:";
    static final String SESSION = "gmshield:session:";
    static final String ACCOUNT_SESSIONS = "gmshield:session:account:";

    private RedisKeys() {
    }

    static String signIn(byte[] tokenHash) {
        return SIGN_IN + hex(tokenHash);
    }

    static String session(byte[] tokenHash) {
        return SESSION + hex(tokenHash);
    }

    static String accountSessions(UUID accountId) {
        return ACCOUNT_SESSIONS + accountId;
    }

    static String hex(byte[] tokenHash) {
        if (tokenHash == null || tokenHash.length != 32) {
            throw new IllegalArgumentException("a token hash is 32 bytes");
        }
        return HexFormat.of().formatHex(tokenHash);
    }
}
