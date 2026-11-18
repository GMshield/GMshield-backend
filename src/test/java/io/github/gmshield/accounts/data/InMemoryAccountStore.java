package io.github.gmshield.accounts.data;

import io.github.gmshield.shared.data.RollbackUndo;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code account} in memory, with the same unique keys as the table, for tests that run without PostgreSQL. An
 * insert undoes itself when its transaction rolls back.
 */
public class InMemoryAccountStore implements AccountStore {

    public final Map<UUID, AccountRow> rows = new ConcurrentHashMap<>();

    @Override
    public synchronized Insert insert(AccountRow row) {
        boolean identity = rows.containsKey(row.id()) || rows.values().stream()
                .anyMatch(other -> Arrays.equals(other.googleIdFingerprint(), row.googleIdFingerprint()));
        if (identity) {
            return Insert.IDENTITY_EXISTS;
        }
        if (rows.values().stream().anyMatch(other -> Arrays.equals(other.usernameFingerprint(), row.usernameFingerprint()))) {
            return Insert.USERNAME_TAKEN;
        }
        rows.put(row.id(), row);
        RollbackUndo.onRollback(() -> rows.remove(row.id()));
        return Insert.INSERTED;
    }

    @Override
    public Optional<AccountRow> findById(UUID id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<AccountRow> findByGoogleIdFingerprint(byte[] fingerprint) {
        return rows.values().stream().filter(row -> Arrays.equals(row.googleIdFingerprint(), fingerprint)).findFirst();
    }

    @Override
    public synchronized boolean updateEmail(AccountRow row, int expectedSealVersion) {
        AccountRow current = rows.get(row.id());
        if (current == null || current.sealVersion() != expectedSealVersion) {
            return false;
        }
        rows.put(row.id(), current.withEmail(row.email(), row.emailFingerprint(), row.sealVersion(), row.seal()));
        return true;
    }

    /** Replaces a stored row as it is, the way a direct database edit would. */
    public void put(AccountRow row) {
        rows.put(row.id(), row);
    }
}
