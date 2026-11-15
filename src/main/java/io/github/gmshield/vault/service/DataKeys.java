package io.github.gmshield.vault.service;

import io.github.gmshield.vault.data.DataKeyStore;
import io.github.gmshield.vault.data.WrappedDataKey;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One data key per owner, created on first use and stored wrapped by the master key under AAD
 * that names the owner and the version. Unwrapped keys are kept in a bounded in-memory map and
 * are never logged.
 */
@Component
class DataKeys {

    static final int FIRST_VERSION = 1;
    static final int CACHE_SIZE = 4096;
    private static final byte WRAP_FORMAT = 1;
    private static final Logger log = LoggerFactory.getLogger(DataKeys.class);

    private final DataKeyStore store;
    private final VaultKeys keys;
    private final Map<CacheKey, SecretKey> unwrapped = Collections.synchronizedMap(lru());
    private final Map<UUID, Integer> current = Collections.synchronizedMap(lru());

    DataKeys(DataKeyStore store, VaultKeys keys) {
        this.store = store;
        this.keys = keys;
    }

    record Current(int version, SecretKey key) {
    }

    /** The owner's newest data key, created when the owner has none. */
    Current current(UUID ownerId) {
        Integer version = current.get(ownerId);
        if (version != null) {
            SecretKey key = unwrapped.get(new CacheKey(ownerId, version));
            if (key != null) {
                return new Current(version, key);
            }
        }
        Optional<WrappedDataKey> stored = store.latest(ownerId);
        if (stored.isEmpty()) {
            byte[] fresh = VaultCrypto.randomBytes(VaultCrypto.KEY_BYTES);
            store.insertIfAbsent(new WrappedDataKey(ownerId, FIRST_VERSION, wrap(ownerId, FIRST_VERSION, fresh)));
            java.util.Arrays.fill(fresh, (byte) 0);
            stored = store.latest(ownerId);   // another request may have created it first: use the stored one
        }
        WrappedDataKey latest = stored.orElseThrow(() -> new IllegalStateException("data key not stored"));
        SecretKey key = unwrapAndCache(latest);
        current.put(ownerId, latest.version());
        return new Current(latest.version(), key);
    }

    /** The owner's data key of that version; an unknown version or a failed unwrap is an integrity failure. */
    SecretKey byVersion(UUID ownerId, int version) {
        SecretKey cached = unwrapped.get(new CacheKey(ownerId, version));
        if (cached != null) {
            return cached;
        }
        if (version < FIRST_VERSION) {
            throw failure(ownerId, version);
        }
        WrappedDataKey stored = store.find(ownerId, version).orElseThrow(() -> failure(ownerId, version));
        return unwrapAndCache(stored);
    }

    /** Drops the owner's keys from memory, so the next use reads them from the store again. */
    void forget(UUID ownerId) {
        current.remove(ownerId);
        synchronized (unwrapped) {
            unwrapped.keySet().removeIf(key -> key.ownerId().equals(ownerId));
        }
    }

    private SecretKey unwrapAndCache(WrappedDataKey stored) {
        byte[] wrapped = stored.wrapped();
        byte[] raw = wrapped.length > 0 && wrapped[0] == WRAP_FORMAT
                ? VaultCrypto.open(keys.master(), aad(stored.ownerId(), stored.version()), wrapped, 1)
                : null;
        if (raw == null || raw.length != VaultCrypto.KEY_BYTES) {
            throw failure(stored.ownerId(), stored.version());
        }
        SecretKey key = VaultCrypto.aesKey(raw);
        java.util.Arrays.fill(raw, (byte) 0);
        unwrapped.put(new CacheKey(stored.ownerId(), stored.version()), key);
        return key;
    }

    private byte[] wrap(UUID ownerId, int version, byte[] dataKey) {
        return VaultCrypto.concat(new byte[] {WRAP_FORMAT}, VaultCrypto.seal(keys.master(), aad(ownerId, version), dataKey));
    }

    private static byte[] aad(UUID ownerId, int version) {
        return VaultCrypto.concat(VaultCrypto.lengthPrefixed("gmshield/vault/data-key/v1", ownerId.toString()),
                ByteBuffer.allocate(4).putInt(version).array());
    }

    private static IntegrityException failure(UUID ownerId, int version) {
        log.atWarn().addKeyValue("ownerId", ownerId).addKeyValue("keyVersion", version).log("vault.data_key_failed");
        return new IntegrityException();
    }

    private static <K, V> LinkedHashMap<K, V> lru() {
        return new LinkedHashMap<>(256, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > CACHE_SIZE;
            }
        };
    }

    private record CacheKey(UUID ownerId, int version) {
    }
}
