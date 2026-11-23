package io.github.gmshield.conversations.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code search} (Story 3.9): its state and code change once, when its job ends. Hibernate fills {@code owner_id} from
 * the owner of the work and filters every query by it.
 */
@Entity(name = "Search")
@Table(name = "search")
class SearchEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;

    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Column(name = "query", nullable = false, updatable = false)
    private byte[] query;

    @Column(name = "tier", updatable = false, length = 16)
    private String tier;

    @Column(name = "material_id", updatable = false)
    private UUID materialId;

    /** The record-type filter, a core key (Story 4.3 part B, V37); null for none. */
    @Column(name = "record_type", updatable = false, length = 32)
    private String recordType;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "code", length = 64)
    private String code;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SearchEntity() {
        // for Hibernate
    }

    SearchEntity(SearchRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.campaignId = row.campaignId();
        this.jobId = row.jobId();
        this.query = row.query();
        this.tier = row.tier();
        this.materialId = row.materialId();
        this.recordType = row.recordType();
        this.state = row.state();
        this.code = row.code();
        this.createdAt = row.createdAt();
    }

    UUID id() {
        return id;
    }

    void end(String state, String code) {
        this.state = state;
        this.code = code;
    }

    SearchRow row() {
        return new SearchRow(id, ownerId, campaignId, jobId, query, tier, materialId, recordType, state, code,
                createdAt);
    }
}
