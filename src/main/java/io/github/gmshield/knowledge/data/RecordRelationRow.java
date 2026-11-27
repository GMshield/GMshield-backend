package io.github.gmshield.knowledge.data;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code record_relation} as stored (Story 5.5 part A, V50): its id, its owner, the record it starts from
 * (an artifact), the record it points to (an extracted record), its kind ({@link #OVERRIDES}, from Story 5.5 part B
 * also {@link #RELATED}, whose target may be another artifact) and when it was written. It holds no stored value, so
 * the generated {@code toString} names ids, the kind and a time only.
 */
public record RecordRelationRow(UUID id, UUID ownerId, UUID fromRecordId, UUID toRecordId, String kind,
                                Instant createdAt) {

    /** The kind of an override: the artifact replaces the record in its own campaign. */
    public static final String OVERRIDES = "overrides";
    /** The kind of a relation (Story 5.5 part B): the two records are related, with no direction. */
    public static final String RELATED = "related";

    public RecordRelationRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(fromRecordId, "fromRecordId");
        Objects.requireNonNull(toRecordId, "toRecordId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** The other record of this link, seen from {@code recordId} (one of its two records). */
    public UUID other(UUID recordId) {
        return fromRecordId.equals(recordId) ? toRecordId : fromRecordId;
    }

    /** The same link pointing to another record (a replaced mark, or a reprocess's carry-over). */
    public RecordRelationRow retargeted(UUID newToRecordId) {
        return new RecordRelationRow(id, ownerId, fromRecordId, newToRecordId, kind, createdAt);
    }
}
