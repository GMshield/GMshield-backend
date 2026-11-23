package io.github.gmshield.knowledge.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code term_alias} (Story 4.4, V39): Hibernate fills {@code owner_id} from the owner of the work and filters every
 * query by it. Written when a term proposal is merged into its term; its proposal goes null when that proposal goes.
 */
@Entity(name = "TermAlias")
@Table(name = "term_alias")
class TermAliasEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "system_id", nullable = false, updatable = false)
    private UUID systemId;

    @Column(name = "term_id", nullable = false, updatable = false)
    private UUID termId;

    @Column(name = "name", nullable = false, updatable = false)
    private byte[] name;

    @Column(name = "name_fingerprint", nullable = false, updatable = false)
    private byte[] nameFingerprint;

    @Column(name = "proposal_id")
    private UUID proposalId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TermAliasEntity() {
        // for Hibernate
    }

    TermAliasEntity(TermAliasRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.systemId = row.systemId();
        this.termId = row.termId();
        this.name = row.name();
        this.nameFingerprint = row.nameFingerprint();
        this.proposalId = row.proposalId();
        this.createdAt = row.createdAt();
    }

    /** Its proposal went with its material: the alias stays, without an origin. */
    void dropProposal() {
        this.proposalId = null;
    }

    TermAliasRow row() {
        return new TermAliasRow(id, ownerId, systemId, termId, name, nameFingerprint, proposalId, createdAt);
    }
}
