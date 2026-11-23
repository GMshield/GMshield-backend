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
 * {@code term} (Story 4.4, V39): Hibernate fills {@code owner_id} from the owner of the work and filters every query by
 * it. Written once, when its proposal is approved.
 */
@Entity(name = "Term")
@Table(name = "term")
class TermEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "system_id", nullable = false, updatable = false)
    private UUID systemId;

    @Column(name = "name", nullable = false)
    private byte[] name;

    @Column(name = "name_fingerprint", nullable = false)
    private byte[] nameFingerprint;

    @Column(name = "definition", nullable = false)
    private byte[] definition;

    @Column(name = "proposal_id", nullable = false, updatable = false)
    private UUID proposalId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TermEntity() {
        // for Hibernate
    }

    TermEntity(TermRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.systemId = row.systemId();
        this.name = row.name();
        this.nameFingerprint = row.nameFingerprint();
        this.definition = row.definition();
        this.proposalId = row.proposalId();
        this.createdAt = row.createdAt();
    }

    TermRow row() {
        return new TermRow(id, ownerId, systemId, name, nameFingerprint, definition, proposalId, version, createdAt);
    }
}
