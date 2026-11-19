package io.github.gmshield.conversations.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** {@code turn}: written once. Hibernate fills {@code owner_id} and filters every query by it. */
@Entity(name = "Turn")
@Table(name = "turn")
class TurnEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Column(name = "question", nullable = false, updatable = false)
    private byte[] question;

    @Column(name = "language", updatable = false)
    private String language;

    @Column(name = "campaign_tier", nullable = false, updatable = false)
    private boolean campaignTier;

    @Column(name = "system_tier", nullable = false, updatable = false)
    private boolean systemTier;

    @Column(name = "model_knowledge", nullable = false, updatable = false)
    private boolean modelKnowledge;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TurnEntity() {
        // for Hibernate
    }

    TurnEntity(TurnRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.conversationId = row.conversationId();
        this.jobId = row.jobId();
        this.question = row.question();
        this.language = row.language();
        this.campaignTier = row.campaignTier();
        this.systemTier = row.systemTier();
        this.modelKnowledge = row.modelKnowledge();
        this.createdAt = row.createdAt();
    }

    TurnRow row() {
        return new TurnRow(id, ownerId, conversationId, jobId, question, language, campaignTier, systemTier,
                modelKnowledge, createdAt);
    }
}
