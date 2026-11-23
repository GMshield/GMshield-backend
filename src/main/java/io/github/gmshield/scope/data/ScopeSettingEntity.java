package io.github.gmshield.scope.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code scope_setting}: Hibernate fills {@code owner_id} from the owner of the work and filters every query by it. The
 * campaign is a plain id: the database's key on (owner, campaign) keeps it within the same owner. Its {@code version}
 * is Hibernate's: the store forces its increment on every change of an existing row (one rise per change, whether or
 * not a column changed), and the first row starts at 1 (no row reads as version 0).
 */
@Entity(name = "ScopeSetting")
@Table(name = "scope_setting")
class ScopeSettingEntity {

    @Id
    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "campaign_tier", nullable = false)
    private boolean campaignTier;

    @Column(name = "system_tier", nullable = false)
    private boolean systemTier;

    @Column(name = "other_tier", nullable = false)
    private boolean otherTier;

    @Column(name = "model_knowledge", nullable = false)
    private boolean modelKnowledge;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ScopeSettingEntity() {
        // for Hibernate
    }

    /** A first row, at version 1: a campaign with no row reads as version 0. */
    ScopeSettingEntity(UUID campaignId, UUID ownerId, Instant now) {
        this.campaignId = campaignId;
        this.ownerId = ownerId;
        this.version = 1;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID campaignId() {
        return campaignId;
    }

    long version() {
        return version;
    }

    boolean campaignTier() {
        return campaignTier;
    }

    boolean systemTier() {
        return systemTier;
    }

    boolean otherTier() {
        return otherTier;
    }

    boolean modelKnowledge() {
        return modelKnowledge;
    }

    /** Replaces the switches; Hibernate raises the version when it writes them. */
    void change(boolean campaignTier, boolean systemTier, boolean otherTier, boolean modelKnowledge, Instant now) {
        this.campaignTier = campaignTier;
        this.systemTier = systemTier;
        this.otherTier = otherTier;
        this.modelKnowledge = modelKnowledge;
        this.updatedAt = now;
    }
}
