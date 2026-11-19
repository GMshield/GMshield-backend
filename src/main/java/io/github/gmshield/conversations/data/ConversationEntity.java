package io.github.gmshield.conversations.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** {@code conversation}: Hibernate fills {@code owner_id} from the owner of the work and filters every query by it. */
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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ConversationEntity() {
        // for Hibernate
    }

    ConversationEntity(ConversationRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.campaignId = row.campaignId();
        this.createdAt = row.createdAt();
    }

    ConversationRow row() {
        return new ConversationRow(id, ownerId, campaignId, createdAt);
    }
}
