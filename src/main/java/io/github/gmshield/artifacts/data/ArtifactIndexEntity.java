package io.github.gmshield.artifacts.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code artifact_index} (Story 5.1 part B, V44): Hibernate fills {@code owner_id} from the owner of the work and
 * filters every query by it. Written once; only {@code recipe} changes, when its job completes. A retry replaces the row
 * with one keyed by the new job ({@code retries} one higher).
 */
@Entity(name = "ArtifactIndex")
@Table(name = "artifact_index")
class ArtifactIndexEntity {

    @Id
    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "record_id", nullable = false, updatable = false)
    private UUID recordId;

    @Column(name = "number", nullable = false, updatable = false)
    private int number;

    @Column(name = "recipe")
    private String recipe;

    @Column(name = "retries", nullable = false, updatable = false)
    private short retries;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ArtifactIndexEntity() {
        // for Hibernate
    }

    ArtifactIndexEntity(ArtifactIndexRow row) {
        this.jobId = row.jobId();
        this.ownerId = row.ownerId();
        this.recordId = row.recordId();
        this.number = row.number();
        this.recipe = row.recipe();
        this.retries = (short) row.retries();
        this.createdAt = row.createdAt();
    }

    void recordRecipe(String reported) {
        this.recipe = reported;
    }

    ArtifactIndexRow row() {
        return new ArtifactIndexRow(jobId, ownerId, recordId, number, recipe, retries, createdAt);
    }
}
