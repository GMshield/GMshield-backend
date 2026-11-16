package io.github.gmshield.library.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** {@code system}: Hibernate fills {@code owner_id} from the owner of the work and filters every query by it. */
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
        this.createdAt = row.createdAt();
        this.updatedAt = row.createdAt();
    }

    SystemRow row() {
        return new SystemRow(id, ownerId, name.clone(), nameFingerprint.clone(), createdAt);
    }
}
