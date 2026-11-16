package io.github.gmshield.accounts.data;

import java.time.Duration;
import java.util.Optional;

/** Sign-in attempts, each under {@code gmshield:sign-in:<hex of the token's vault hash>} with a time to live. */
public interface SignInAttemptStore {

    void save(byte[] tokenHash, SignInAttempt attempt, Duration timeToLive);

    /** The attempt, removed in the same step: a second take of the same attempt finds nothing. */
    Optional<SignInAttempt> take(byte[] tokenHash);

    Optional<SignInAttempt> find(byte[] tokenHash);

    void delete(byte[] tokenHash);
}
