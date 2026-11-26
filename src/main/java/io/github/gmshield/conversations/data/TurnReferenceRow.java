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
 * record's reference also holds {@link Recorded} (null on a passage's). From Story 5.1 part B (V44) a cited
 * artifact's reference holds {@link Recorded} with the artifact's version, and no version, material, page, passage,
 * material name or section path (all null, its passages empty). {@code toString} names ids and numbers only.
 */
public record TurnReferenceRow(UUID id, UUID ownerId, UUID turnId, int number, UUID versionId, UUID materialId,
                               Integer page, List<UUID> passageIds, byte[] materialName, byte[] sectionPath,
                               Instant createdAt, String tier, Recorded record) {

    /**
     * The record a reference names (V38): its id and core type's key, and its name and fields (a JSON array of
     * {@code {name, value}}) as vault ciphertexts. For an artifact (V44) {@code recordId} is the artifact's id and
     * {@code artifactVersion} the number of the version cited (null for an extracted record). {@code toString} names the
     * id, the type and the version only.
     */
    public record Recorded(UUID recordId, String recordType, byte[] name, byte[] fields, Integer artifactVersion) {
        public Recorded {
            Objects.requireNonNull(recordId, "recordId");
            Objects.requireNonNull(recordType, "recordType");
            name = name.clone();
            fields = fields.clone();
        }

        /** An extracted record (V38). */
        public Recorded(UUID recordId, String recordType, byte[] name, byte[] fields) {
            this(recordId, recordType, name, fields, null);
        }

        /** Whether it names an artifact (V44). */
        public boolean artifact() {
            return artifactVersion != null;
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
                    && Arrays.equals(fields, that.fields) && Objects.equals(artifactVersion, that.artifactVersion);
        }

        @Override
        public int hashCode() {
            return Objects.hash(recordId, recordType, Arrays.hashCode(name), Arrays.hashCode(fields), artifactVersion);
        }

        @Override
        public String toString() {
            return "Recorded[recordId=" + recordId + ", recordType=" + recordType + ", artifactVersion="
                    + artifactVersion + "]";
        }
    }

    public TurnReferenceRow {
        passageIds = passageIds == null ? List.of() : List.copyOf(passageIds);
        materialName = materialName == null ? null : materialName.clone();
        sectionPath = sectionPath == null ? null : sectionPath.clone();
    }

    /** A cited artifact's reference (Story 5.1 part B): its record, and no place. */
    public static TurnReferenceRow artifact(UUID id, UUID ownerId, UUID turnId, int number, Instant createdAt,
                                            String tier, Recorded record) {
        if (record == null || !record.artifact()) {
            throw new IllegalArgumentException("conversations.artifact_reference_invalid");
        }
        return new TurnReferenceRow(id, ownerId, turnId, number, null, null, null, List.of(), null, null, createdAt,
                tier, record);
    }

    /** Whether it is a cited artifact's reference (Story 5.1 part B). */
    public boolean artifact() {
        return record != null && record.artifact();
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
        return materialName == null ? null : materialName.clone();
    }

    @Override
    public byte[] sectionPath() {
        return sectionPath == null ? null : sectionPath.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TurnReferenceRow that && number == that.number && Objects.equals(page, that.page)
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
