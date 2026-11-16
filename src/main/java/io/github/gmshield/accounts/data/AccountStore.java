package io.github.gmshield.accounts.data;

import java.util.Optional;
import java.util.UUID;

/** The table {@code account}. Lookups are exact, by id or by fingerprint: there is no text search. */
public interface AccountStore {

    /** What an insert met: the row was stored, its username was taken, or its id or Google id exists already. */
    enum Insert { INSERTED, USERNAME_TAKEN, IDENTITY_EXISTS }

    /** Stores a new row unless a unique key (id, Google id, username fingerprint) is taken; never throws for that. */
    Insert insert(AccountRow row);

    Optional<AccountRow> findById(UUID id);

    Optional<AccountRow> findByGoogleIdFingerprint(byte[] fingerprint);

    /**
     * Replaces the email address and the seal of the row whose seal version is still {@code expectedSealVersion}.
     * False when another change came first.
     */
    boolean updateEmail(AccountRow row, int expectedSealVersion);
}
