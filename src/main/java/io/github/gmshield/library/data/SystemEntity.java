package io.github.gmshield.library.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code system}: Hibernate fills {@code owner_id} from the owner of the work and filters every query by it. From
 * Story 3.1 it holds an edition and a description (vault ciphertexts, null when none), and its {@code version} is
 * Hibernate's: each update raises it, and an update of a row whose version moved meanwhile fails. From Story 3.1 part B
 * (V22) it has a {@code state}: every read of the store but the deletion's own takes {@code ready} rows only.
 */
@Entity(name = "LibrarySystem")
@Table(name = "system")
class SystemEntity {

    static final String READY = "ready";
    static final String DELETING = "deleting";

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

    /** {@value #READY}, or {@value #DELETING} from the GM's delete until the row goes (Story 3.1 part B, V22). */
    @Column(name = "state", nullable = false)
    private String state = READY;

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

    /**
     * The system is being deleted (Story 3.1 part B): it leaves every read, and its name fingerprint becomes its own id
     * (16 bytes, never a keyed fingerprint's 32), so the name and edition are free at once.
     */
    void markDeleting(Instant at) {
        this.state = DELETING;
        ByteBuffer id16 = ByteBuffer.allocate(16);
        id16.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        this.nameFingerprint = id16.array();
        this.updatedAt = at;
    }

    SystemRow row() {
        return new SystemRow(id, ownerId, name.clone(), nameFingerprint.clone(), SystemRow.copy(edition),
                SystemRow.copy(description), version, createdAt);
    }
}
