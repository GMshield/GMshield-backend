package io.github.gmshield.library.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code campaign}: Hibernate fills {@code owner_id} from the owner of the work and filters every query by it.
 * Its system is a plain id: the database's key on (owner, system) keeps it within the same owner.
 */
@Entity(name = "LibraryCampaign")
@Table(name = "campaign")
class CampaignEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "system_id", nullable = false)
    private UUID systemId;

    @Column(name = "name", nullable = false)
    private byte[] name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CampaignEntity() {
        // for Hibernate
    }

    CampaignEntity(CampaignRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.systemId = row.systemId();
        this.name = row.name().clone();
        this.createdAt = row.createdAt();
        this.updatedAt = row.createdAt();
    }

    CampaignRow row() {
        return new CampaignRow(id, ownerId, systemId, name.clone(), createdAt);
    }
}
