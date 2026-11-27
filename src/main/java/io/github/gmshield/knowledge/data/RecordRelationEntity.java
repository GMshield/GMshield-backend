package io.github.gmshield.knowledge.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code record_relation} (Story 5.5 part A, V50): Hibernate fills {@code owner_id} from the owner of the work and
 * filters every query by it. Both records are plain ids: the database's keys on (owner, record) keep them within the
 * same owner and remove the link with either record. Only the record it points to ever changes (a replaced mark, or a
 * reprocess's carry-over).
 */
@Entity(name = "RecordRelation")
@Table(name = "record_relation")
class RecordRelationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "from_record_id", nullable = false, updatable = false)
    private UUID fromRecordId;

    @Column(name = "to_record_id", nullable = false)
    private UUID toRecordId;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RecordRelationEntity() {
        // for Hibernate
    }

    RecordRelationEntity(RecordRelationRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.fromRecordId = row.fromRecordId();
        this.toRecordId = row.toRecordId();
        this.kind = row.kind();
        this.createdAt = row.createdAt();
    }

    void retarget(UUID newToRecordId) {
        this.toRecordId = newToRecordId;
    }

    RecordRelationRow row() {
        return new RecordRelationRow(id, ownerId, fromRecordId, toRecordId, kind, createdAt);
    }
}
