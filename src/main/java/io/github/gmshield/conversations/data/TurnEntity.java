package io.github.gmshield.conversations.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code turn}: written once, but for {@code stopped_at}, set once by the GM's stop (Story 2.6). Hibernate fills
 * {@code owner_id} and filters every query by it. From Story 3.5 it freezes the other tier's switch too; the materials
 * switched off are {@link TurnMaterialOffEntity} rows.
 *
 * <p>Story 3.11 (V32): {@code dismissed_at} is mapped not updatable: only the store's bulk updates
 * ({@code JpaConversationStore#markDismissed}, {@code #clearDismissed}) change it, so a stop's flush of this entity
 * (loaded before a dismissal committed) never writes it back to null.
 *
 * <p>Story 3.12 (V33): {@code bookmarked_at} is mapped the same way: only the store's bulk updates
 * ({@code JpaConversationStore#markBookmarked}, {@code #clearBookmarked}) change it.
 *
 * <p>Story 5.3 part A (V47): {@code kind}, written once with the turn ({@code question} or {@code draft}).
 */
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

    @Column(name = "other_tier", nullable = false, updatable = false)
    private boolean otherTier;

    @Column(name = "model_knowledge", nullable = false, updatable = false)
    private boolean modelKnowledge;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "dismissed_at", updatable = false)
    private Instant dismissedAt;

    @Column(name = "bookmarked_at", updatable = false)
    private Instant bookmarkedAt;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

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
        this.otherTier = row.otherTier();
        this.modelKnowledge = row.modelKnowledge();
        this.createdAt = row.createdAt();
        this.stoppedAt = row.stoppedAt();
        this.dismissedAt = row.dismissedAt();
        this.bookmarkedAt = row.bookmarkedAt();
        this.kind = row.kind();
    }

    void stop(Instant at) {
        this.stoppedAt = at;
    }

    /** The row, with the materials switched off frozen with it (read from {@code turn_material_off}). */
    TurnRow row(Set<UUID> materialsOff) {
        return new TurnRow(id, ownerId, conversationId, jobId, question, language, campaignTier, systemTier,
                otherTier, modelKnowledge, materialsOff, createdAt, stoppedAt, dismissedAt, bookmarkedAt, kind);
    }

    UUID id() {
        return id;
    }

    UUID conversationId() {
        return conversationId;
    }

    Instant createdAt() {
        return createdAt;
    }
}
