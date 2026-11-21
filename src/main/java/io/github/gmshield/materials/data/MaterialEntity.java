package io.github.gmshield.materials.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code material}: Hibernate fills {@code owner_id} from the owner of the work and filters every query by it. Its
 * campaign is a plain id: the database's key on (owner, campaign) keeps it within the same owner. Its kind and language
 * are written once, at creation (Story 1.14). From Story 3.3 its campaign changes with where it belongs
 * ({@link #belong}), and its {@code version} is Hibernate's: each such change raises it, and an update of a row whose
 * version moved meanwhile fails.
 */
@Entity(name = "Material")
@Table(name = "material")
class MaterialEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "name", nullable = false)
    private byte[] name;

    @Column(name = "kind", updatable = false)
    private String kind;

    @Column(name = "language", updatable = false)
    private String language;

    @Column(name = "campaign_id")
    private UUID campaignId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MaterialEntity() {
        // for Hibernate
    }

    MaterialEntity(MaterialRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.name = row.name().clone();
        this.kind = row.kind();
        this.language = row.language();
        this.campaignId = row.campaignId();
        this.createdAt = row.createdAt();
        this.updatedAt = row.createdAt();
    }

    long version() {
        return version;
    }

    /**
     * Where the material belongs changed (Story 3.3): its campaign (null when it belongs to systems), at {@code now}.
     * The row is always written (its update time always moves, by a microsecond at least), so Hibernate raises the
     * version even when only its systems changed.
     */
    void belong(UUID campaign, Instant now) {
        this.campaignId = campaign;
        this.updatedAt = now.isAfter(updatedAt) ? now : updatedAt.plusNanos(1000);
    }

    MaterialRow row() {
        return new MaterialRow(id, ownerId, name.clone(), createdAt, kind, language, campaignId, version);
    }
}
