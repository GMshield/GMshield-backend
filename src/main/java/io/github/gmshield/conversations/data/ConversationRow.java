package io.github.gmshield.conversations.data;

import java.time.Instant;
import java.util.UUID;

/** One row of {@code conversation}: a campaign's conversation. */
public record ConversationRow(UUID id, UUID ownerId, UUID campaignId, Instant createdAt) {
}
