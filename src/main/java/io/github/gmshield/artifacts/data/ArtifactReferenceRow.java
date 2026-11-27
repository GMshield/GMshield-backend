package io.github.gmshield.artifacts.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code artifact_reference} as stored (Story 5.4 part A, V49): a source reference of the draft an artifact
 * was saved from, copied by value. Its artifact ({@code recordId}), owner and number, then {@code turn_reference}'s
 * columns: a passage's place ({@code versionId}, {@code materialId}, {@code page}, {@code passageIds}, and the material
 * name and section path as vault ciphertexts), its tier (null when the turn's had none), and for a record's reference
 * the cited record's id ({@code citedRecordId}), or for an artifact's the cited artifact's id and version, each with
 * its type key and its name and fields as vault ciphertexts. Equality compares the bytes; {@code toString} shows ids,
 * the number and the kind only, never a stored value.
 */
public record ArtifactReferenceRow(UUID recordId, UUID ownerId, int number, UUID versionId, UUID materialId,
                                   Integer page, List<UUID> passageIds, byte[] materialName, byte[] sectionPath,
                                   String tier, UUID citedRecordId, String recordType, byte[] recordName,
                                   byte[] recordFields, UUID artifactId, Integer artifactVersion, Instant createdAt) {

    public ArtifactReferenceRow {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (number < 1) {
            throw new IllegalArgumentException("artifacts.reference_number_invalid");
        }
        passageIds = passageIds == null ? null : List.copyOf(passageIds);
        materialName = materialName == null ? null : materialName.clone();
        sectionPath = sectionPath == null ? null : sectionPath.clone();
        recordName = recordName == null ? null : recordName.clone();
        recordFields = recordFields == null ? null : recordFields.clone();
    }

    /** Whether it cites an artifact (no place). */
    public boolean artifact() {
        return artifactId != null;
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
    public byte[] recordName() {
        return recordName == null ? null : recordName.clone();
    }

    @Override
    public byte[] recordFields() {
        return recordFields == null ? null : recordFields.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ArtifactReferenceRow that && recordId.equals(that.recordId)
                && ownerId.equals(that.ownerId) && number == that.number && Objects.equals(versionId, that.versionId)
                && Objects.equals(materialId, that.materialId) && Objects.equals(page, that.page)
                && Objects.equals(passageIds, that.passageIds) && Arrays.equals(materialName, that.materialName)
                && Arrays.equals(sectionPath, that.sectionPath) && Objects.equals(tier, that.tier)
                && Objects.equals(citedRecordId, that.citedRecordId) && Objects.equals(recordType, that.recordType)
                && Arrays.equals(recordName, that.recordName) && Arrays.equals(recordFields, that.recordFields)
                && Objects.equals(artifactId, that.artifactId) && Objects.equals(artifactVersion, that.artifactVersion)
                && createdAt.equals(that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recordId, ownerId, number, versionId, materialId, page, passageIds,
                Arrays.hashCode(materialName), Arrays.hashCode(sectionPath), tier, citedRecordId, recordType,
                Arrays.hashCode(recordName), Arrays.hashCode(recordFields), artifactId, artifactVersion, createdAt);
    }

    @Override
    public String toString() {
        String kind = artifact() ? "artifact" : citedRecordId != null ? "record" : "passage";
        return "ArtifactReferenceRow[recordId=" + recordId + ", number=" + number + ", kind=" + kind + "]";
    }
}
