package io.github.gmshield.library.service;

import java.util.Objects;
import java.util.UUID;

/**
 * A system of {@code ownerId} was just created (Story 4.1): published by {@link Library} inside the transaction that
 * inserts it, on both paths (the starter system of a first sign-in, and the GM's new system). A synchronous listener runs
 * in that transaction, so what it stores exists with the system or not at all ({@code knowledge} seeds the core record
 * types this way, and {@code library} depends on no module that listens). Ids only.
 */
public record SystemCreated(UUID ownerId, UUID systemId) {

    public SystemCreated {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(systemId, "systemId");
    }
}
