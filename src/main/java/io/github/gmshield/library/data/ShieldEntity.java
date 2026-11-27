package io.github.gmshield.library.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code shield} (Story 6.1, V53): Hibernate fills {@code owner_id} from the owner of the work and filters every query by
 * it. Its campaign is a plain id: the database's key on (owner, campaign) keeps it within the same owner. Its
 * {@code version} is Hibernate's: a rename raises it, and an update of a row whose version moved meanwhile fails.
 */
@Entity(name = "LibraryShield")
@Table(name = "shield")
class ShieldEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;

    @Column(name = "name")
    private byte[] name;

    @Column(name = "name_fingerprint")
    private byte[] nameFingerprint;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ShieldEntity() {
        // for Hibernate
    }

    ShieldEntity(ShieldRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.campaignId = row.campaignId();
        this.name = SystemRow.copy(row.name());
        this.nameFingerprint = SystemRow.copy(row.nameFingerprint());
        this.createdAt = row.createdAt();
    }

    long version() {
        return version;
    }

    /** Replaces the name and its fingerprint; Hibernate raises the version when it writes them. */
    void rename(byte[] newName, byte[] newFingerprint) {
        this.name = newName.clone();
        this.nameFingerprint = newFingerprint.clone();
    }

    ShieldRow row() {
        return new ShieldRow(id, ownerId, campaignId, SystemRow.copy(name), SystemRow.copy(nameFingerprint), version,
                createdAt);
    }
}
