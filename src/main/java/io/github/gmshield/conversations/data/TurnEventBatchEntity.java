package io.github.gmshield.conversations.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** {@code turn_event_batch}: written once. Hibernate fills {@code owner_id} and filters every query by it. */
@Entity(name = "TurnEventBatch")
@Table(name = "turn_event_batch")
class TurnEventBatchEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "turn_id", nullable = false, updatable = false)
    private UUID turnId;

    @Column(name = "claim_id", updatable = false)
    private UUID claimId;

    @Column(name = "batch", updatable = false)
    private Integer batch;

    @Column(name = "first_sequence", nullable = false, updatable = false)
    private long firstSequence;

    @Column(name = "last_sequence", nullable = false, updatable = false)
    private long lastSequence;

    @Column(name = "payload", nullable = false, updatable = false)
    private byte[] payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TurnEventBatchEntity() {
        // for Hibernate
    }

    TurnEventBatchEntity(EventBatchRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.turnId = row.turnId();
        this.claimId = row.claimId();
        this.batch = row.batch();
        this.firstSequence = row.firstSequence();
        this.lastSequence = row.lastSequence();
        this.payload = row.payload();
        this.createdAt = row.createdAt();
    }

    EventBatchRow row() {
        return new EventBatchRow(id, ownerId, turnId, claimId, batch, firstSequence, lastSequence, payload, createdAt);
    }
}
