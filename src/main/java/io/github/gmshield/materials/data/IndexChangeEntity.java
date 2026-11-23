package io.github.gmshield.materials.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code index_change} (Story 2.1): Hibernate fills {@code owner_id} from the owner of the work and filters every query
 * by it. Its version is a plain id: the database's key on (owner, version) keeps it within the same owner. Written
 * once; only {@code recipe} changes, when its job completes. A retry replaces the row with one keyed by the new job
 * ({@code retries} one higher, {@code records} kept). For a records row ({@code records} true, Story 4.2 part B),
 * {@code first_ordinal} and {@code last_ordinal} hold the first and last page of its run.
 */
@Entity(name = "IndexChange")
@Table(name = "index_change")
class IndexChangeEntity {

    @Id
    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Column(name = "first_ordinal", nullable = false, updatable = false)
    private int firstOrdinal;

    @Column(name = "last_ordinal", nullable = false, updatable = false)
    private int lastOrdinal;

    @Column(name = "recipe")
    private String recipe;

    @Column(name = "retries", nullable = false, updatable = false)
    private short retries;

    @Column(name = "records", nullable = false, updatable = false)
    private boolean records;

    protected IndexChangeEntity() {
        // for Hibernate
    }

    IndexChangeEntity(IndexChangeRow row) {
        this.jobId = row.jobId();
        this.ownerId = row.ownerId();
        this.versionId = row.versionId();
        this.firstOrdinal = row.firstOrdinal();
        this.lastOrdinal = row.lastOrdinal();
        this.recipe = row.recipe();
        this.retries = (short) row.retries();
        this.records = row.records();
    }

    void recordRecipe(String reported) {
        this.recipe = reported;
    }

    IndexChangeRow row() {
        return new IndexChangeRow(jobId, ownerId, versionId, firstOrdinal, lastOrdinal, recipe, retries, records);
    }
}
