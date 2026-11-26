package io.github.gmshield.artifacts.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code artifact_index_removal} (Story 5.1 part B, V44): Hibernate fills {@code owner_id} from the owner of the work
 * and filters every query by it. Written once; a retry replaces it with a row keyed by the new job.
 */
@Entity(name = "ArtifactIndexRemoval")
@Table(name = "artifact_index_removal")
class ArtifactIndexRemovalEntity {

    @Id
    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "artifact_id", nullable = false, updatable = false)
    private UUID artifactId;

    protected ArtifactIndexRemovalEntity() {
        // for Hibernate
    }

    ArtifactIndexRemovalEntity(ArtifactIndexRemovalRow row) {
        this.jobId = row.jobId();
        this.ownerId = row.ownerId();
        this.artifactId = row.artifactId();
    }

    ArtifactIndexRemovalRow row() {
        return new ArtifactIndexRemovalRow(jobId, ownerId, artifactId);
    }
}
