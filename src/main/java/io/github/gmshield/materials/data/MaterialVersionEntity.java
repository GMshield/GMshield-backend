package io.github.gmshield.materials.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code material_version}: Hibernate fills {@code owner_id} from the owner of the work and filters every query by it.
 * Its material is a plain id: the database's key on (owner, material) keeps it within the same owner.
 */
@Entity(name = "MaterialVersion")
@Table(name = "material_version")
class MaterialVersionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "format")
    private String format;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MaterialVersionEntity() {
        // for Hibernate
    }

    MaterialVersionEntity(VersionRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.materialId = row.materialId();
        this.state = row.state();
        this.format = row.format();
        this.sizeBytes = row.sizeBytes();
        this.createdAt = row.createdAt();
        this.updatedAt = row.updatedAt();
    }

    void change(String newState, String newFormat, Long newSize, Instant now) {
        this.state = newState;
        this.format = newFormat;
        this.sizeBytes = newSize;
        this.updatedAt = now;
    }

    VersionRow row() {
        return new VersionRow(id, ownerId, materialId, state, format, sizeBytes, createdAt, updatedAt);
    }
}
