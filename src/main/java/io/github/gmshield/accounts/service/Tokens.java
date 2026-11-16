package io.github.gmshield.accounts.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** Attempt and session tokens: 32 random bytes in unpadded Base64url (43 characters). */
final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern FORM = Pattern.compile("[A-Za-z0-9_-]{43}");

    private Tokens() {
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return base64Url(bytes);
    }

    /** Whether a value can be a token at all; anything else is refused before any lookup. */
    static boolean wellFormed(String token) {
        return token != null && FORM.matcher(token).matches();
    }

    static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Equal texts, compared in time that does not depend on where they first differ. */
    static boolean sameText(String expected, String given) {
        if (expected == null || given == null) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = given.getBytes(StandardCharsets.UTF_8);
        int difference = a.length ^ b.length;
        for (int i = 0; i < a.length; i++) {
            difference |= a[i] ^ (i < b.length ? b[i] : 0);
        }
        return difference == 0;
    }
}
