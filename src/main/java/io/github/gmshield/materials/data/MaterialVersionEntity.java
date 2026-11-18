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

    @Column(name = "page_total")
    private Integer pageTotal;

    @Column(name = "pipeline_version")
    private String pipelineVersion;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "removal_pending", nullable = false)
    private boolean removalPending;

    @Column(name = "index_recipe")
    private String indexRecipe;

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
        this.pageTotal = row.pageTotal();
        this.pipelineVersion = row.pipelineVersion();
        this.failureCode = row.failureCode();
        this.removalPending = row.removalPending();
        this.indexRecipe = row.indexRecipe();
        this.createdAt = row.createdAt();
        this.updatedAt = row.updatedAt();
    }

    void change(String newState, String newFormat, Long newSize, Instant now) {
        this.state = newState;
        this.format = newFormat;
        this.sizeBytes = newSize;
        this.updatedAt = now;
    }

    /** Only the state (Story 1.15: {@code waiting} to {@code processing}), nothing else of the version. */
    void changeState(String newState, Instant now) {
        this.state = newState;
        this.updatedAt = now;
    }

    /** What a step reported: the pipeline version and the page total, each only when given. */
    void record(String newPipelineVersion, Integer newPageTotal, Instant now) {
        if (newPipelineVersion != null) {
            this.pipelineVersion = newPipelineVersion;
        }
        if (newPageTotal != null) {
            this.pageTotal = newPageTotal;
        }
        this.updatedAt = now;
    }

    /** A restarted chain (Story 1.16): its pipeline version goes, its page total stays. */
    void clearPipelineVersion(Instant now) {
        this.pipelineVersion = null;
        this.updatedAt = now;
    }

    /** The version ended {@code failed} with a code. */
    void fail(String code, Instant now) {
        this.state = "failed";
        this.failureCode = code;
        this.updatedAt = now;
    }

    /** Discarded (Story 1.20): the state {@code discarded}, and its removal pending until it has finished. */
    void discard(Instant now) {
        this.state = "discarded";
        this.removalPending = true;
        this.updatedAt = now;
    }

    /** The removal of a discarded version has finished (Story 1.20). */
    void clearRemovalPending(Instant now) {
        this.removalPending = false;
        this.updatedAt = now;
    }

    /**
     * The index recipe of the version's current set of index jobs (Story 2.1), or null; the version's update time
     * stays: nothing the GM sees changes.
     */
    void recordIndexRecipe(String recipe) {
        this.indexRecipe = recipe;
    }

    VersionRow row() {
        return new VersionRow(id, ownerId, materialId, state, format, sizeBytes, createdAt, updatedAt, pageTotal,
                pipelineVersion, failureCode, removalPending, indexRecipe);
    }
}
