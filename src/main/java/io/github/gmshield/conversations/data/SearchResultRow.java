package io.github.gmshield.conversations.data;

import java.util.UUID;

/**
 * One row of {@code search_result} (Story 3.9): a result of a done search, by rank (1..20): the passage, its version and
 * the tier its source had when the search ran, all by value. Ids and words only.
 */
public record SearchResultRow(UUID id, UUID ownerId, UUID searchId, int rank, UUID passageId, UUID versionId,
                              String tier) {
}
