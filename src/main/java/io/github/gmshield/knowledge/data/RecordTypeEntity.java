package io.github.gmshield.knowledge.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code record_type} (Story 4.1, V34): Hibernate fills {@code owner_id} from the owner of the work and filters every
 * query by it. The system is a plain id: the database's key on (owner, system) keeps it within the same owner. Its
 * {@code version} is Hibernate's: each rename raises it, and an update of a row whose version moved meanwhile fails.
 */
@Entity(name = "RecordType")
@Table(name = "record_type")
class RecordTypeEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "system_id", nullable = false, updatable = false)
    private UUID systemId;

    /** The core key, or null for a type that is not core; it never changes. */
    @Column(name = "core", updatable = false)
    private String core;

    /**
     * The stable key (Story 4.4): the core key (null in a core row the release before V39 wrote), or the slug of an
     * approved type's name; it never changes. A JPQL path
     * names it {@code typeKey}, never the function name {@code key}.
     */
    @Column(name = "key", updatable = false)
    private String typeKey;

    /** The approving proposal (Story 4.4), null for a core type; it never changes. */
    @Column(name = "proposal_id", updatable = false)
    private UUID proposalId;

    @Column(name = "name", nullable = false)
    private byte[] name;

    @Column(name = "name_fingerprint", nullable = false)
    private byte[] nameFingerprint;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RecordTypeEntity() {
        // for Hibernate
    }

    RecordTypeEntity(RecordTypeRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.systemId = row.systemId();
        this.core = row.core();
        this.typeKey = row.key();
        this.proposalId = row.proposalId();
        this.name = row.name();
        this.nameFingerprint = row.nameFingerprint();
        this.createdAt = row.createdAt();
    }

    long version() {
        return version;
    }

    /** Replaces the name; Hibernate raises the version when it writes it. */
    void rename(byte[] newName, byte[] newFingerprint) {
        this.name = newName.clone();
        this.nameFingerprint = newFingerprint.clone();
    }

    RecordTypeRow row() {
        // A core type written by the release before V39 has no key: its key is its core key.
        return new RecordTypeRow(id, ownerId, systemId, core, typeKey == null ? core : typeKey, proposalId, name, nameFingerprint, version,
                createdAt);
    }
}
