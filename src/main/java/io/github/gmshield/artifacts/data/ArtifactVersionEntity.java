package io.github.gmshield.artifacts.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code record_version} (Story 5.1, V43): Hibernate fills {@code owner_id} from the owner of the work and filters every
 * query by it. The record is a plain id: the database's key on (owner, record) keeps it within the same owner. Written
 * once; a later version is a new row with the next number, and an earlier one never changes.
 */
@Entity(name = "RecordVersion")
@Table(name = "record_version")
@IdClass(ArtifactVersionEntity.Key.class)
class ArtifactVersionEntity {

    @Id
    @Column(name = "record_id", nullable = false, updatable = false)
    private UUID recordId;

    @Id
    @Column(name = "number", nullable = false, updatable = false)
    private int number;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    /** The version's record type key (Story 5.2, V45). */
    @Column(name = "record_type", nullable = false, updatable = false)
    private String recordType;

    @Column(name = "name", nullable = false, updatable = false)
    private byte[] name;

    @Column(name = "fields", nullable = false, updatable = false)
    private byte[] fields;

    @Column(name = "content", updatable = false)
    private byte[] content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ArtifactVersionEntity() {
        // for Hibernate
    }

    ArtifactVersionEntity(ArtifactVersionRow row) {
        this.recordId = row.recordId();
        this.number = row.number();
        this.ownerId = row.ownerId();
        this.recordType = row.recordType();
        this.name = row.name();
        this.fields = row.fields();
        this.content = row.content();
        this.createdAt = row.createdAt();
    }

    ArtifactVersionRow row() {
        return new ArtifactVersionRow(recordId, ownerId, number, recordType, name, fields, content, createdAt);
    }

    /** The version's identity: its record and its number. */
    static final class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID recordId;
        private int number;

        Key() {
            // for Hibernate
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key that && Objects.equals(recordId, that.recordId) && number == that.number;
        }

        @Override
        public int hashCode() {
            return Objects.hash(recordId, number);
        }
    }
}
