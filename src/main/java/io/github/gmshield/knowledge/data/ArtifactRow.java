package io.github.gmshield.knowledge.data;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An artifact's identity as a row of {@code record} stores it (Story 5.1, V43): its id, its owner, its campaign, its
 * record type's key and when it was written. Its content (name, fields, content) is in the {@code artifacts} module's
 * versions, never here. It holds no stored value, so the generated {@code toString} names ids, the key and a time only.
 * From Story 5.4 part A (V49), the conversation it was saved from ({@code conversation_id}): null for one written by
 * hand, and once that conversation is deleted. From Story 6.2 part A (V54), the Shield holding a note
 * ({@code shield_id}): null for every other artifact and for a note no Shield holds.
 */
public record ArtifactRow(UUID id, UUID ownerId, UUID campaignId, String type, Instant createdAt,
                          UUID conversationId, UUID shieldId) {

    public ArtifactRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** An artifact of no Shield (Story 5.4 part A): saved from {@code conversationId}, or written by hand if null. */
    public ArtifactRow(UUID id, UUID ownerId, UUID campaignId, String type, Instant createdAt, UUID conversationId) {
        this(id, ownerId, campaignId, type, createdAt, conversationId, null);
    }

    /** An artifact written by hand: no conversation. */
    public ArtifactRow(UUID id, UUID ownerId, UUID campaignId, String type, Instant createdAt) {
        this(id, ownerId, campaignId, type, createdAt, null);
    }
}
