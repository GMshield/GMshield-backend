package io.github.gmshield.shared.service;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Every id the backend mints: a UUID version 7 (RFC 9562), a millisecond timestamp followed by 74 random
 * bits. Ids sort by the millisecond they were made in; ids made in the same millisecond sort randomly.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        long millis = System.currentTimeMillis();
        long high = (millis << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
        long low = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(high, low);
    }
}
