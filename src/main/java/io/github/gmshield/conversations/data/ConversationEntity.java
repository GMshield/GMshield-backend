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
 *
 * <p>Story 3.10 part B (V31): it has a {@code state}, {@code ready} or {@code deleting}: every read of the store but
 * the deletion's own takes {@code ready} rows only. A delete sets it by a bulk update of the store, which never raises
 * the version. Only those bulk updates change {@code last_asked_at} and {@code state}: both are mapped not updatable,
 * so a rename that read the row before either committed never writes its old value back.
 */
@Entity(name = "Conversation")
@Table(name = "conversation")
class ConversationEntity {

    static final String READY = "ready";

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
    @Column(name = "last_asked_at", nullable = false, updatable = false)
    private Instant lastAskedAt;

    @OptimisticLock(excluded = true)
    @Column(name = "state", nullable = false, updatable = false)
    private String state = READY;

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
