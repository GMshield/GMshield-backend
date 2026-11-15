package io.github.gmshield.vault.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Encryption at rest for every module (AD-19).
 *
 * <p>A stored value is {@code format(1) ‖ dataKeyVersion(4) ‖ nonce(12) ‖ ciphertext ‖ tag(16)}:
 * AES-256-GCM under the owner's data key, with AAD that encodes the format, the data key version,
 * the entity, the field, the row id and the owner id, each length-prefixed. Any change, move or
 * unknown version fails with one {@link IntegrityException}.
 *
 * <p>Exact lookups use keyed fingerprints and tokens are stored as keyed hashes: HMAC-SHA256 under
 * subkeys derived by HKDF from the index key, separated by purpose and by entity and field.
 */
@Service
public class Vault {

    static final byte FORMAT = 1;
    static final int HEADER_BYTES = 1 + 4;
    private static final byte[] INDEX_SALT = "gmshield/vault/index/v1".getBytes(StandardCharsets.US_ASCII);
    private static final Logger log = LoggerFactory.getLogger(Vault.class);

    private final DataKeys dataKeys;
    private final VaultKeys keys;
    private final Map<String, SecretKey> subkeys = new ConcurrentHashMap<>();

    Vault(DataKeys dataKeys, VaultKeys keys) {
        this.dataKeys = dataKeys;
        this.keys = keys;
    }

    public byte[] encrypt(Binding binding, byte[] plaintext) {
        DataKeys.Current key = dataKeys.current(binding.ownerId());
        byte[] header = ByteBuffer.allocate(HEADER_BYTES).put(FORMAT).putInt(key.version()).array();
        return VaultCrypto.concat(header, VaultCrypto.seal(key.key(), aad(binding, header), plaintext));
    }

    public byte[] encryptText(Binding binding, String plaintext) {
        return encrypt(binding, plaintext.getBytes(StandardCharsets.UTF_8));
    }

    /** The plaintext, only under the binding it was written with. */
    public byte[] decrypt(Binding binding, byte[] stored) {
        if (stored == null || stored.length < HEADER_BYTES || stored[0] != FORMAT) {
            throw failure(binding);
        }
        int version = ByteBuffer.wrap(stored, 1, 4).getInt();
        SecretKey key;
        try {
            key = dataKeys.byVersion(binding.ownerId(), version);
        } catch (IntegrityException unknownKey) {
            throw failure(binding);
        }
        byte[] plaintext = VaultCrypto.open(key, aad(binding, java.util.Arrays.copyOf(stored, HEADER_BYTES)), stored,
                HEADER_BYTES);
        if (plaintext == null) {
            throw failure(binding);
        }
        return plaintext;
    }

    public String decryptText(Binding binding, byte[] stored) {
        return new String(decrypt(binding, stored), StandardCharsets.UTF_8);
    }

    /**
     * A keyed fingerprint for exact lookups: the same value in the same entity and field gives the
     * same fingerprint for every owner; another entity or field gives another one.
     */
    public byte[] fingerprint(String entity, String field, byte[] value) {
        return VaultCrypto.hmac(subkey("fingerprint", entity, field), value);
    }

    public byte[] fingerprint(String entity, String field, String value) {
        return fingerprint(entity, field, value.getBytes(StandardCharsets.UTF_8));
    }

    /** The keyed hash under which a token (a session, a grant) is stored; never equal to a fingerprint. */
    public byte[] tokenHash(String purpose, String token) {
        return VaultCrypto.hmac(subkey("token", purpose), token.getBytes(StandardCharsets.UTF_8));
    }

    private SecretKey subkey(String... domain) {
        for (String part : domain) {
            if (part == null || part.isBlank()) {
                throw new IllegalArgumentException("purpose, entity and field are required");
            }
        }
        byte[] info = VaultCrypto.lengthPrefixed(domain);
        return subkeys.computeIfAbsent(java.util.HexFormat.of().formatHex(info),
                ignored -> VaultCrypto.hmacKey(VaultCrypto.hkdf(keys.index(), INDEX_SALT, info)));
    }

    private static byte[] aad(Binding binding, byte[] header) {
        return VaultCrypto.concat(VaultCrypto.lengthPrefixed("gmshield/vault/value/v1", binding.entity(),
                binding.field(), binding.rowId().toString(), binding.ownerId().toString()), header);
    }

    /** Logs the ids, never the value, and gives the one integrity failure. */
    static IntegrityException failure(Binding binding) {
        log.atWarn()
                .addKeyValue("entity", binding.entity())
                .addKeyValue("field", binding.field())
                .addKeyValue("rowId", binding.rowId())
                .addKeyValue("ownerId", binding.ownerId())
                .log("vault.integrity_failed");
        return new IntegrityException();
    }
}
