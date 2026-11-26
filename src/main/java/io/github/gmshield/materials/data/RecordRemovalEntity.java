package io.github.gmshield.materials.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code record_removal} (Story 4.5 part B): Hibernate fills {@code owner_id} from the owner of the work and filters
 * every query by it. Its version and record are plain ids with no reference, so the row outlives them. Written once,
 * never changed.
 */
@Entity(name = "RecordRemoval")
@Table(name = "record_removal")
class RecordRemovalEntity {

    @Id
    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Column(name = "record_id", nullable = false, updatable = false)
    private UUID recordId;

    protected RecordRemovalEntity() {
        // for Hibernate
    }

    RecordRemovalEntity(RecordRemovalRow row) {
        this.jobId = row.jobId();
        this.ownerId = row.ownerId();
        this.versionId = row.versionId();
        this.recordId = row.recordId();
    }

    RecordRemovalRow row() {
        return new RecordRemovalRow(jobId, ownerId, versionId, recordId);
    }
}
