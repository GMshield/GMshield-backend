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
 * {@code proposal} (Story 4.4, V39): Hibernate fills {@code owner_id} from the owner of the work and filters every query
 * by it. System, material and version are plain ids: the database's keys on (owner, id) keep them within the same owner.
 * Its {@code version} is Hibernate's: a decision and new examples raise it.
 */
@Entity(name = "Proposal")
@Table(name = "proposal")
class ProposalEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "system_id", nullable = false, updatable = false)
    private UUID systemId;

    @Column(name = "material_id")
    private UUID materialId;

    @Column(name = "version_id")
    private UUID versionId;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @Column(name = "name", nullable = false, updatable = false)
    private byte[] name;

    @Column(name = "name_fingerprint", nullable = false, updatable = false)
    private byte[] nameFingerprint;

    @Column(name = "definition", updatable = false)
    private byte[] definition;

    @Column(name = "examples", nullable = false)
    private byte[] examples;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProposalEntity() {
        // for Hibernate
    }

    ProposalEntity(ProposalRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.systemId = row.systemId();
        this.materialId = row.materialId();
        this.versionId = row.versionId();
        this.kind = row.kind();
        this.name = row.name();
        this.nameFingerprint = row.nameFingerprint();
        this.definition = row.definition();
        this.examples = row.examples();
        this.state = row.state();
        this.decidedAt = row.decidedAt();
        this.createdAt = row.createdAt();
    }

    long version() {
        return version;
    }

    String state() {
        return state;
    }

    void examples(UUID newVersionId, byte[] newExamples) {
        this.versionId = newVersionId;
        this.examples = newExamples.clone();
    }

    void decide(String newState, Instant at) {
        this.state = newState;
        this.decidedAt = at;
    }

    /** No longer names its version (the version was cleared). */
    void detachVersion() {
        this.versionId = null;
    }

    /** No longer names its material nor a version (the material went). */
    void detachMaterial() {
        this.materialId = null;
        this.versionId = null;
    }

    ProposalRow row() {
        return new ProposalRow(id, ownerId, systemId, materialId, versionId, kind, name, nameFingerprint, definition,
                examples, state, decidedAt, version, createdAt);
    }
}
