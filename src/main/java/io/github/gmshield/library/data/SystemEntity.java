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
 * {@code system}: Hibernate fills {@code owner_id} from the owner of the work and filters every query by it. From
 * Story 3.1 it holds an edition and a description (vault ciphertexts, null when none), and its {@code version} is
 * Hibernate's: each update raises it, and an update of a row whose version moved meanwhile fails.
 */
@Entity(name = "LibrarySystem")
@Table(name = "system")
class SystemEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "name", nullable = false)
    private byte[] name;

    @Column(name = "name_fingerprint", nullable = false)
    private byte[] nameFingerprint;

    @Column(name = "edition")
    private byte[] edition;

    @Column(name = "description")
    private byte[] description;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SystemEntity() {
        // for Hibernate
    }

    SystemEntity(SystemRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.name = row.name().clone();
        this.nameFingerprint = row.nameFingerprint().clone();
        this.edition = SystemRow.copy(row.edition());
        this.description = SystemRow.copy(row.description());
        this.createdAt = row.createdAt();
        this.updatedAt = row.createdAt();
    }

    long version() {
        return version;
    }

    /** Replaces the stored values; Hibernate raises the version when it writes them. */
    void change(SystemChange change) {
        this.name = change.name().clone();
        this.nameFingerprint = change.nameFingerprint().clone();
        this.edition = SystemRow.copy(change.edition());
        this.description = SystemRow.copy(change.description());
        this.updatedAt = change.at();
    }

    SystemRow row() {
        return new SystemRow(id, ownerId, name.clone(), nameFingerprint.clone(), SystemRow.copy(edition),
                SystemRow.copy(description), version, createdAt);
    }
}
