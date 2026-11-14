package io.github.gmshield.vault.service;

import java.util.Objects;
import java.util.UUID;

/**
 * Where a value belongs: its entity (table), field (column), row and owner. A ciphertext decrypts
 * only under the binding it was written with, so a value copied to another row, field, entity or
 * owner fails the integrity check instead of being shown.
 */
public record Binding(String entity, String field, UUID rowId, UUID ownerId) {

    public Binding {
        if (entity == null || entity.isBlank() || field == null || field.isBlank()) {
            throw new IllegalArgumentException("entity and field are required");
        }
        Objects.requireNonNull(rowId, "rowId");
        Objects.requireNonNull(ownerId, "ownerId");
    }
}
