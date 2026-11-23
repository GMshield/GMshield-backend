package io.github.gmshield.conversations.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code turn_reference} (Story 2.5): a numbered source reference of a turn, stored by value: its version
 * and material ids, its page, the ids of the cited passages at that place, the material's name and the section path as
 * vault ciphertexts, and (Story 3.7) the tier of its source in the turn's scope at answer time ({@code campaign},
 * {@code system} or {@code other}; {@code null} on a row written before V28). From Story 4.3 part C (V38) a cited
 * record's reference also holds {@link Recorded} (null on a passage's). {@code toString} names ids and numbers only.
 */
public record TurnReferenceRow(UUID id, UUID ownerId, UUID turnId, int number, UUID versionId, UUID materialId,
                               int page, List<UUID> passageIds, byte[] materialName, byte[] sectionPath,
                               Instant createdAt, String tier, Recorded record) {

    /**
     * The record a reference names (V38): its id and core type's key, and its name and fields (a JSON array of
     * {@code {name, value}}) as vault ciphertexts. {@code toString} names the id and the type only.
     */
    public record Recorded(UUID recordId, String recordType, byte[] name, byte[] fields) {
        public Recorded {
            Objects.requireNonNull(recordId, "recordId");
            Objects.requireNonNull(recordType, "recordType");
            name = name.clone();
            fields = fields.clone();
        }

        @Override
        public byte[] name() {
            return name.clone();
        }

        @Override
        public byte[] fields() {
            return fields.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Recorded that && recordId.equals(that.recordId)
                    && recordType.equals(that.recordType) && Arrays.equals(name, that.name)
                    && Arrays.equals(fields, that.fields);
        }

        @Override
        public int hashCode() {
            return Objects.hash(recordId, recordType, Arrays.hashCode(name), Arrays.hashCode(fields));
        }

        @Override
        public String toString() {
            return "Recorded[recordId=" + recordId + ", recordType=" + recordType + "]";
        }
    }

    public TurnReferenceRow {
        passageIds = List.copyOf(passageIds);
        materialName = materialName.clone();
        sectionPath = sectionPath.clone();
    }

    /** A passage place's reference: no record. */
    public TurnReferenceRow(UUID id, UUID ownerId, UUID turnId, int number, UUID versionId, UUID materialId, int page,
                            List<UUID> passageIds, byte[] materialName, byte[] sectionPath, Instant createdAt,
                            String tier) {
        this(id, ownerId, turnId, number, versionId, materialId, page, passageIds, materialName, sectionPath, createdAt,
                tier, null);
    }

    @Override
    public byte[] materialName() {
        return materialName.clone();
    }

    @Override
    public byte[] sectionPath() {
        return sectionPath.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TurnReferenceRow that && number == that.number && page == that.page
                && Objects.equals(id, that.id) && Objects.equals(ownerId, that.ownerId)
                && Objects.equals(turnId, that.turnId) && Objects.equals(versionId, that.versionId)
                && Objects.equals(materialId, that.materialId) && Objects.equals(passageIds, that.passageIds)
                && Arrays.equals(materialName, that.materialName) && Arrays.equals(sectionPath, that.sectionPath)
                && Objects.equals(createdAt, that.createdAt) && Objects.equals(tier, that.tier)
                && Objects.equals(record, that.record);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, turnId, number, versionId, materialId, page, passageIds,
                Arrays.hashCode(materialName), Arrays.hashCode(sectionPath), createdAt, tier, record);
    }

    @Override
    public String toString() {
        return "TurnReferenceRow[id=" + id + ", turnId=" + turnId + ", number=" + number + ", versionId=" + versionId
                + ", page=" + page + ", recordId=" + (record == null ? null : record.recordId()) + "]";
    }
}
