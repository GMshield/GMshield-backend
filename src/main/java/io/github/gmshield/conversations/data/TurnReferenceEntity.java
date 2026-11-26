package io.github.gmshield.conversations.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/** {@code turn_reference}: written once. Hibernate fills {@code owner_id} and filters every query by it. */
@Entity(name = "TurnReference")
@Table(name = "turn_reference")
class TurnReferenceEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "turn_id", nullable = false, updatable = false)
    private UUID turnId;

    @Column(name = "number", nullable = false, updatable = false)
    private int number;

    /** Null, with the material, page, passages, material name and section path, on an artifact's (V44). */
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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Story 3.7 (V28): null on a row written before it. */
    @Column(name = "tier", updatable = false, length = 16)
    private String tier;

    /** Story 4.3 part C (V38): the cited record's id, core type key, name and fields; all null on a passage's. */
    @Column(name = "record_id", updatable = false)
    private UUID recordId;

    @Column(name = "record_type", updatable = false, length = 32)
    private String recordType;

    @Column(name = "record_name", updatable = false)
    private byte[] recordName;

    @Column(name = "record_fields", updatable = false)
    private byte[] recordFields;

    /** Story 5.1 part B (V44): a cited artifact's id and version; both null on any other reference. */
    @Column(name = "artifact_id", updatable = false)
    private UUID artifactId;

    @Column(name = "artifact_version", updatable = false)
    private Integer artifactVersion;

    protected TurnReferenceEntity() {
        // for Hibernate
    }

    TurnReferenceEntity(TurnReferenceRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.turnId = row.turnId();
        this.number = row.number();
        this.versionId = row.versionId();
        this.materialId = row.materialId();
        this.page = row.page();
        this.passageIds = row.artifact() ? null : row.passageIds().toArray(UUID[]::new);
        this.materialName = row.materialName();
        this.sectionPath = row.sectionPath();
        this.createdAt = row.createdAt();
        this.tier = row.tier();
        if (row.artifact()) {
            this.artifactId = row.record().recordId();
            this.artifactVersion = row.record().artifactVersion();
            this.recordType = row.record().recordType();
            this.recordName = row.record().name();
            this.recordFields = row.record().fields();
        } else if (row.record() != null) {
            this.recordId = row.record().recordId();
            this.recordType = row.record().recordType();
            this.recordName = row.record().name();
            this.recordFields = row.record().fields();
        }
    }

    TurnReferenceRow row() {
        if (artifactId != null) {
            return TurnReferenceRow.artifact(id, ownerId, turnId, number, createdAt, tier,
                    new TurnReferenceRow.Recorded(artifactId, recordType, recordName, recordFields, artifactVersion));
        }
        return new TurnReferenceRow(id, ownerId, turnId, number, versionId, materialId, page, List.of(passageIds),
                materialName, sectionPath, createdAt, tier, recordId == null ? null
                : new TurnReferenceRow.Recorded(recordId, recordType, recordName, recordFields));
    }
}
