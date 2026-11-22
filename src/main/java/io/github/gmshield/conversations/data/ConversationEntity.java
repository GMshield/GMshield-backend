package io.github.gmshield.conversations.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.OptimisticLock;
import org.hibernate.annotations.TenantId;

/**
 * {@code conversation}: Hibernate fills {@code owner_id} from the owner of the work and filters every query by it.
 *
 * <p>Story 3.10 (V30): its {@code version} is Hibernate's: a rename raises it, and an update of a row whose version
 * moved meanwhile fails. A question asked in it moves {@code last_asked_at} by a bulk update of the store, which never
 * raises it, so asking never makes an open rename stale.
 */
@Entity(name = "Conversation")
@Table(name = "conversation")
class ConversationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;

    @Column(name = "area", nullable = false, updatable = false)
    private String area;

    @Column(name = "name")
    private byte[] name;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OptimisticLock(excluded = true)
    @Column(name = "last_asked_at", nullable = false)
    private Instant lastAskedAt;

    protected ConversationEntity() {
        // for Hibernate
    }

    ConversationEntity(ConversationRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.campaignId = row.campaignId();
        this.area = row.area();
        this.name = row.name();
        this.version = row.version();
        this.createdAt = row.createdAt();
        this.lastAskedAt = row.lastAskedAt();
    }

    long version() {
        return version;
    }

    /** Its new name; Hibernate raises the version when it writes it. */
    void rename(byte[] newName) {
        this.name = newName.clone();
    }

    ConversationRow row() {
        return new ConversationRow(id, ownerId, campaignId, area, name, version, createdAt, lastAskedAt);
    }
}
