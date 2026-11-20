package io.github.gmshield.vault.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
 * subkeys derived by HKDF from the index key, separated by purpose and by entity and field. Fields that
 * grant power are sealed (AD-20): an HMAC-SHA256 over the row id and the sealed values, under a subkey of
 * the entity, checked on every load.
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
        return VaultCrypto.seal(key.key(), aad(binding, header), header, plaintext);
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

    /**
     * The seal of a row (AD-20): a keyed signature over the entity, the row id and the sealed values, in
     * order. A change to any of them, or the seal moved to another row, fails {@link #verifySeal}.
     */
    public byte[] seal(String entity, UUID rowId, String... values) {
        return VaultCrypto.hmac(subkey("seal", entity), sealed(entity, rowId, values));
    }

    /**
     * Checks a row's seal against its values, in constant time. A mismatch logs the entity and the row id
     * (never a value) and throws {@link IntegrityException}: a failed seal grants nothing.
     */
    public void verifySeal(String entity, UUID rowId, byte[] seal, String... values) {
        byte[] expected = seal(entity, rowId, values);
        if (seal == null || !MessageDigest.isEqual(expected, seal)) {
            log.atWarn().addKeyValue("entity", entity).addKeyValue("rowId", rowId).log("vault.seal_failed");
            throw new IntegrityException();
        }
    }

    /**
     * The owner's key for the token numbers of the keyword index (Story 2.1, AD-10): 32 bytes, derived by HKDF from
     * the index key under the domain {@code ("index-token", ownerId)}, the same for the owner on every call and another
     * for every other owner. It goes to the worker with an index job's input and nowhere else: it is never logged, kept
     * or stored, and each call answers a new copy.
     */
    public byte[] ownerTokenKey(UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        return VaultCrypto.hkdf(keys.index(), INDEX_SALT, VaultCrypto.lengthPrefixed("index-token", ownerId.toString()));
    }

    /** The PKCE code challenge of a verifier (RFC 7636, method S256): Base64url of its SHA-256, unpadded. */
    public String pkceChallenge(String verifier) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                VaultCrypto.sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    /**
     * An incremental SHA-256 of content that streams through in parts (Story 1.13: a tus part and a whole upload),
     * never held whole. The digest is the content's, unkeyed: it is compared with a checksum the browser sent, and
     * is never stored or logged.
     */
    public Sha256 sha256() {
        return new Sha256(VaultCrypto.newSha256());
    }

    private static byte[] sealed(String entity, UUID rowId, String... values) {
        Objects.requireNonNull(rowId, "rowId");
        String[] parts = new String[values.length + 2];
        parts[0] = "gmshield/vault/seal/v1";
        parts[1] = rowId.toString();
        for (int i = 0; i < values.length; i++) {
            parts[i + 2] = Objects.requireNonNull(values[i], "a sealed value");
        }
        return VaultCrypto.lengthPrefixed(parts);
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
