package io.github.gmshield.accounts.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The username rules (Dalton, 2026-11-16): 3 to 24 characters from the letters a to z, the digits, "_",
 * "." and "-". Unique whatever the case, and shown as typed: the fingerprint is taken over the NFC
 * lower-case form, the stored value is the one typed.
 */
public final class Usernames {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 24;
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_.-]{" + MIN_LENGTH + "," + MAX_LENGTH + "}");

    private Usernames() {
    }

    public static boolean valid(String username) {
        return username != null && ALLOWED.matcher(username).matches();
    }

    /** The form whose fingerprint is unique: NFC, then lower case in the root locale. */
    static String uniqueForm(String username) {
        return Normalizer.normalize(username, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
}
