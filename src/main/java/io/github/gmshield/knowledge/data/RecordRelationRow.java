package io.github.gmshield.knowledge.data;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code record_relation} as stored (Story 5.5 part A, V50): its id, its owner, the record it starts from
 * (an artifact), the record it points to (an extracted record), its kind ({@link #OVERRIDES}) and when it was written.
 * It holds no stored value, so the generated {@code toString} names ids, the kind and a time only.
 */
public record RecordRelationRow(UUID id, UUID ownerId, UUID fromRecordId, UUID toRecordId, String kind,
                                Instant createdAt) {

    /** The kind of an override: the artifact replaces the record in its own campaign. */
    public static final String OVERRIDES = "overrides";

    public RecordRelationRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(fromRecordId, "fromRecordId");
        Objects.requireNonNull(toRecordId, "toRecordId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** The same link pointing to another record (a replaced mark, or a reprocess's carry-over). */
    public RecordRelationRow retargeted(UUID newToRecordId) {
        return new RecordRelationRow(id, ownerId, fromRecordId, newToRecordId, kind, createdAt);
    }
}
