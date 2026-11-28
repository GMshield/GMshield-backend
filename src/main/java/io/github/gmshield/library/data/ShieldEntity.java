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
 *
 * <p>Story 6.2 part B (V55): the GM's delete sets {@code deleting_at} and frees the name ({@link #markDeleting}); it never
 * goes back to null.
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

    @Column(name = "deleting_at")
    private Instant deletingAt;

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
        this.deletingAt = row.deletingAt();
    }

    UUID id() {
        return id;
    }

    long version() {
        return version;
    }

    /** Replaces the name and its fingerprint; Hibernate raises the version when it writes them. */
    void rename(byte[] newName, byte[] newFingerprint) {
        this.name = newName.clone();
        this.nameFingerprint = newFingerprint.clone();
    }

    /**
     * The GM deleted it (Story 6.2 part B): it leaves every read from {@code at}, and a named Shield's name fingerprint
     * becomes its own id (16 bytes, never a keyed fingerprint's 32), so the name is free in its campaign at once, as a
     * deleting campaign's is. An unnamed Shield keeps both null (V53's check). Hibernate raises the version.
     */
    void markDeleting(Instant at) {
        this.deletingAt = at;
        if (nameFingerprint != null) {
            this.nameFingerprint = SystemRow.idBytes(id);
        }
    }

    ShieldRow row() {
        return new ShieldRow(id, ownerId, campaignId, SystemRow.copy(name), SystemRow.copy(nameFingerprint), version,
                createdAt, deletingAt);
    }
}
