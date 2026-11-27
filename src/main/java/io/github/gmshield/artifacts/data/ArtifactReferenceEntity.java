package io.github.gmshield.artifacts.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * {@code artifact_reference} (Story 5.4 part A, V49): written once. Hibernate fills {@code owner_id} from the owner of
 * the work and filters every query by it. The artifact is a plain id: the database's key on (owner, record) keeps it
 * within the same owner, and removes the rows with the artifact's.
 */
@Entity(name = "ArtifactReference")
@Table(name = "artifact_reference")
@IdClass(ArtifactReferenceEntity.Key.class)
class ArtifactReferenceEntity {

    @Id
    @Column(name = "record_id", nullable = false, updatable = false)
    private UUID recordId;

    @Id
    @Column(name = "number", nullable = false, updatable = false)
    private int number;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "version_id", updatable = false)
    private UUID versionId;

    @Column(name = "material_id", updatable = false)
    private UUID materialId;

    @Column(name = "page", updatable = false)
    private Integer page;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "passage_ids", updatable = false)
    private UUID[] passageIds;

    @Column(name = "material_name", updatable = false)
    private byte[] materialName;

    @Column(name = "section_path", updatable = false)
    private byte[] sectionPath;

    @Column(name = "tier", updatable = false, length = 16)
    private String tier;

    @Column(name = "cited_record_id", updatable = false)
    private UUID citedRecordId;

    @Column(name = "record_type", updatable = false, length = 32)
    private String recordType;

    @Column(name = "record_name", updatable = false)
    private byte[] recordName;

    @Column(name = "record_fields", updatable = false)
    private byte[] recordFields;

    @Column(name = "artifact_id", updatable = false)
    private UUID artifactId;

    @Column(name = "artifact_version", updatable = false)
    private Integer artifactVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ArtifactReferenceEntity() {
        // for Hibernate
    }

    ArtifactReferenceEntity(ArtifactReferenceRow row) {
        this.recordId = row.recordId();
        this.number = row.number();
        this.ownerId = row.ownerId();
        this.versionId = row.versionId();
        this.materialId = row.materialId();
        this.page = row.page();
        this.passageIds = row.passageIds() == null ? null : row.passageIds().toArray(UUID[]::new);
        this.materialName = row.materialName();
        this.sectionPath = row.sectionPath();
        this.tier = row.tier();
        this.citedRecordId = row.citedRecordId();
        this.recordType = row.recordType();
        this.recordName = row.recordName();
        this.recordFields = row.recordFields();
        this.artifactId = row.artifactId();
        this.artifactVersion = row.artifactVersion();
        this.createdAt = row.createdAt();
    }

    ArtifactReferenceRow row() {
        return new ArtifactReferenceRow(recordId, ownerId, number, versionId, materialId, page,
                passageIds == null ? null : List.of(passageIds), materialName, sectionPath, tier, citedRecordId,
                recordType, recordName, recordFields, artifactId, artifactVersion, createdAt);
    }

    /** The reference's identity: its artifact and its number. */
    static final class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID recordId;
        private int number;

        Key() {
            // for Hibernate
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key that && Objects.equals(recordId, that.recordId) && number == that.number;
        }

        @Override
        public int hashCode() {
            return Objects.hash(recordId, number);
        }
    }
}
