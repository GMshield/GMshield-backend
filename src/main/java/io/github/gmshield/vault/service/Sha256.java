package io.github.gmshield.vault.service;

import java.security.MessageDigest;
import java.util.Objects;

/**
 * One SHA-256 computed as the content arrives ({@link Vault#sha256()}): {@link #update} as often as needed, then
 * {@link #digest()} or {@link #matches} once. Not thread-safe; one per piece of content.
 */
public final class Sha256 {

    /** The length of a SHA-256 digest in bytes. */
    public static final int BYTES = 32;

    private final MessageDigest digest;
    private byte[] result;

    Sha256(MessageDigest digest) {
        this.digest = digest;
    }

    public void update(byte[] bytes, int offset, int length) {
        if (result != null) {
            throw new IllegalStateException("the digest is already final");
        }
        digest.update(bytes, offset, length);
    }

    /** The digest of everything given so far; afterwards nothing more can be added. */
    public byte[] digest() {
        if (result == null) {
            result = digest.digest();
        }
        return result.clone();
    }

    /** Whether the digest equals {@code expected}, in time that does not depend on where they differ. */
    public boolean matches(byte[] expected) {
        Objects.requireNonNull(expected, "expected");
        return MessageDigest.isEqual(digest(), expected);
    }
}
