package io.github.gmshield.knowledge.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code record} (Story 4.2, V35): Hibernate fills {@code owner_id} from the owner of the work and filters every query
 * by it. The version is a plain id: the database's key on (owner, version) keeps it within the same owner. Written
 * once; a step reported again replaces its records with new rows.
 */
@Entity(name = "Record")
@Table(name = "record")
class RecordEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Column(name = "ordinal", nullable = false, updatable = false)
    private int ordinal;

    @Column(name = "first_page", nullable = false, updatable = false)
    private int firstPage;

    /** The core type's key: a JPQL path names it {@code recordType}, never the keyword {@code type}. */
    @Column(name = "type", nullable = false, updatable = false)
    private String recordType;

    @Column(name = "name", nullable = false, updatable = false)
    private byte[] name;

    @Column(name = "fields", nullable = false, updatable = false)
    private byte[] fields;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RecordEntity() {
        // for Hibernate
    }

    RecordEntity(RecordRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.versionId = row.versionId();
        this.ordinal = row.ordinal();
        this.firstPage = row.firstPage();
        this.recordType = row.type();
        this.name = row.name();
        this.fields = row.fields();
        this.createdAt = row.createdAt();
    }

    UUID id() {
        return id;
    }

    RecordRow row(List<UUID> passageIds) {
        return new RecordRow(id, ownerId, versionId, ordinal, firstPage, recordType, name, fields, passageIds,
                createdAt);
    }
}
