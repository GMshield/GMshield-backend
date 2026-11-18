package io.github.gmshield.materials.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code version_outline} (Story 1.18): the outline of a version's sections, encrypted, one row per version. Hibernate
 * fills {@code owner_id} from the owner of the work and filters every query by it. Its version is a plain id: the
 * database's key on (owner, version) keeps it within the same owner. Written once, never changed; a report carrying
 * passages removes the version's earlier outline first.
 */
@Entity(name = "VersionOutline")
@Table(name = "version_outline")
class VersionOutlineEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Column(name = "outline", nullable = false, updatable = false)
    private byte[] outline;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected VersionOutlineEntity() {
        // for Hibernate
    }

    VersionOutlineEntity(OutlineRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.versionId = row.versionId();
        this.outline = row.outline().clone();
        this.createdAt = row.createdAt();
    }

    OutlineRow row() {
        return new OutlineRow(id, ownerId, versionId, outline.clone(), createdAt);
    }
}
