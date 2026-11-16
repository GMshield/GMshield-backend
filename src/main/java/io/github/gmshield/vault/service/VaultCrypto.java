package io.github.gmshield.vault.service;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The JDK primitives the vault uses, and nothing else: AES-256-GCM with a random 96-bit nonce,
 * HMAC-SHA256, SHA-256, and HKDF-SHA256 (RFC 5869) for subkeys. A failed check returns null; callers turn
 * it into one {@link IntegrityException}.
 */
final class VaultCrypto {

    static final int KEY_BYTES = 32;
    static final int NONCE_BYTES = 12;
    static final int TAG_BYTES = 16;
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final String HMAC = "HmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private VaultCrypto() {
    }

    static SecretKey aesKey(byte[] key) {
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("a vault key is 32 bytes");
        }
        return new SecretKeySpec(key, "AES");
    }

    static SecretKey hmacKey(byte[] key) {
        return new SecretKeySpec(key, HMAC);
    }

    static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** nonce ‖ ciphertext ‖ tag, under a new random nonce. */
    static byte[] seal(SecretKey key, byte[] aad, byte[] plaintext) {
        byte[] nonce = randomBytes(NONCE_BYTES);
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce));
            cipher.updateAAD(aad);
            byte[] sealed = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available", e);
        }
    }

    /** The plaintext of nonce ‖ ciphertext ‖ tag, or null when any byte, the key or the AAD differs. */
    static byte[] open(SecretKey key, byte[] aad, byte[] sealed, int offset) {
        if (sealed.length - offset < NONCE_BYTES + TAG_BYTES) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, sealed, offset, NONCE_BYTES));
            cipher.updateAAD(aad);
            return cipher.doFinal(sealed, offset + NONCE_BYTES, sealed.length - offset - NONCE_BYTES);
        } catch (javax.crypto.AEADBadTagException failed) {
            return null;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available", e);
        }
    }

    static byte[] hmac(SecretKey key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    static byte[] sha256(byte[] message) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** HKDF-SHA256 (RFC 5869), one output block: a 32-byte key for {@code info}. */
    static byte[] hkdf(byte[] inputKey, byte[] salt, byte[] info) {
        byte[] prk = hmac(hmacKey(salt), inputKey);
        byte[] block = ByteBuffer.allocate(info.length + 1).put(info).put((byte) 1).array();
        try {
            return hmac(hmacKey(prk), block);
        } finally {
            Arrays.fill(prk, (byte) 0);
        }
    }

    /** Each part as a 4-byte length and its UTF-8 bytes, so that no two different lists encode alike. */
    static byte[] lengthPrefixed(String... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            out.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
            out.writeBytes(bytes);
        }
        return out.toByteArray();
    }

    static byte[] concat(byte[] first, byte[] second) {
        return ByteBuffer.allocate(first.length + second.length).put(first).put(second).array();
    }
}
