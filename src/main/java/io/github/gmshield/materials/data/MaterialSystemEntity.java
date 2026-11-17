package io.github.gmshield.materials.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code material_system}: Hibernate fills {@code owner_id} from the owner of the work and filters every query by it.
 * The material and the system are plain ids: the database's keys on (owner, id) keep both within the same owner.
 */
@Entity(name = "MaterialSystem")
@Table(name = "material_system")
class MaterialSystemEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    @Column(name = "system_id", nullable = false, updatable = false)
    private UUID systemId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MaterialSystemEntity() {
        // for Hibernate
    }

    MaterialSystemEntity(MaterialSystemRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.materialId = row.materialId();
        this.systemId = row.systemId();
        this.createdAt = row.createdAt();
    }

    MaterialSystemRow row() {
        return new MaterialSystemRow(id, ownerId, materialId, systemId, createdAt);
    }
}
