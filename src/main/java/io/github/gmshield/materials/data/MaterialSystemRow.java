package io.github.gmshield.materials.data;

import java.time.Instant;
import java.util.UUID;

/** One row of {@code material_system} (Story 1.14): a material belongs to a game system of the same owner. */
public record MaterialSystemRow(UUID id, UUID ownerId, UUID materialId, UUID systemId, Instant createdAt) {

    @Override
    public String toString() {
        return "MaterialSystemRow[id=" + id + ", ownerId=" + ownerId + "]";
    }
}
