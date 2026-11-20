package io.github.gmshield.materials.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code index_removal} (Story 2.1): Hibernate fills {@code owner_id} from the owner of the work and filters every query
 * by it. Its version is a plain id with no reference, so the row outlives a deleted version. Written once, never
 * changed.
 */
@Entity(name = "IndexRemoval")
@Table(name = "index_removal")
class IndexRemovalEntity {

    @Id
    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    protected IndexRemovalEntity() {
        // for Hibernate
    }

    IndexRemovalEntity(IndexRemovalRow row) {
        this.jobId = row.jobId();
        this.ownerId = row.ownerId();
        this.versionId = row.versionId();
    }

    IndexRemovalRow row() {
        return new IndexRemovalRow(jobId, ownerId, versionId);
    }
}
