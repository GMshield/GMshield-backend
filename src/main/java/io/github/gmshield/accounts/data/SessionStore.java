package io.github.gmshield.accounts.data;

import java.time.Duration;
import java.util.Optional;

/**
 * Sessions, each under {@code gmshield:session:<hex of the token's vault hash>}, and one set per account,
 * {@code gmshield:session:account:<account id>}, that names its sessions so that all can end at once.
 */
public interface SessionStore {

    void create(byte[] tokenHash, Session session, Duration timeToLive);

    Optional<Session> find(byte[] tokenHash);

    /** Ends the session and removes it from its account's set; nothing happens when it does not exist. */
    void delete(byte[] tokenHash);
}
