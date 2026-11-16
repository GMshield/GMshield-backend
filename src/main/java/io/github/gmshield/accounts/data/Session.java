package io.github.gmshield.accounts.data;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A session as Redis holds it: the account and the seal version it was created under, never a token. */
public record Session(UUID accountId, int sealVersion, Instant createdAt) {

    public Session {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
