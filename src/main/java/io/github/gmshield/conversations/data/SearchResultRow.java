package io.github.gmshield.conversations.data;

import java.util.UUID;

/**
 * One row of {@code search_result} (Story 3.9): a result of a done search, by rank (1..20): the passage, its version and
 * the tier its source had when the search ran, all by value. From Story 4.3 part B (V37) a result is a passage or a
 * record: exactly one of {@code passageId} and {@code recordId} is set. Ids and words only.
 */
public record SearchResultRow(UUID id, UUID ownerId, UUID searchId, int rank, UUID passageId, UUID recordId,
                              UUID versionId, String tier) {

    public SearchResultRow {
        if ((passageId == null) == (recordId == null)) {
            throw new IllegalArgumentException("conversations.search_result_item");
        }
    }

    /** Whether the result is a record (else a passage). */
    public boolean record() {
        return recordId != null;
    }
}
