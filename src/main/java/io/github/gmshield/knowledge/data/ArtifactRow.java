package io.github.gmshield.knowledge.data;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An artifact's identity as a row of {@code record} stores it (Story 5.1, V43): its id, its owner, its campaign, its
 * record type's key and when it was written. Its content (name, fields, content) is in the {@code artifacts} module's
 * versions, never here. It holds no stored value, so the generated {@code toString} names ids, the key and a time only.
 */
public record ArtifactRow(UUID id, UUID ownerId, UUID campaignId, String type, Instant createdAt) {

    public ArtifactRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
