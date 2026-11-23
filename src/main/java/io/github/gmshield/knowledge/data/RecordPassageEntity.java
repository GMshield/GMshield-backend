package io.github.gmshield.knowledge.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code record_passage} (Story 4.2, V35): one passage a record was read from. Hibernate fills {@code owner_id} from the
 * owner of the work and filters every query by it; the record and the passage are plain ids, and the database's keys on
 * (owner, record) and (owner, passage) keep both within the same owner. Written once, removed before its record.
 */
@Entity(name = "RecordPassage")
@Table(name = "record_passage")
@IdClass(RecordPassageEntity.Key.class)
class RecordPassageEntity {

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Id
    @Column(name = "record_id", nullable = false, updatable = false)
    private UUID recordId;

    @Id
    @Column(name = "passage_id", nullable = false, updatable = false)
    private UUID passageId;

    protected RecordPassageEntity() {
        // for Hibernate
    }

    RecordPassageEntity(UUID ownerId, UUID recordId, UUID passageId) {
        this.ownerId = ownerId;
        this.recordId = recordId;
        this.passageId = passageId;
    }

    UUID recordId() {
        return recordId;
    }

    UUID passageId() {
        return passageId;
    }

    /** The link's identity within its owner: the record and the passage. */
    static final class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID recordId;
        private UUID passageId;

        Key() {
            // for Hibernate
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key that && Objects.equals(recordId, that.recordId)
                    && Objects.equals(passageId, that.passageId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(recordId, passageId);
        }
    }
}
