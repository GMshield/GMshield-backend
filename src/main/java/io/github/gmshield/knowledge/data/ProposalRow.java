package io.github.gmshield.knowledge.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code proposal} as stored (Story 4.4, V39): its system, the material and version that proposed it (both
 * null once detached: the material deleted, or the version cleared), its kind ({@value #RECORD_TYPE} or {@value #TERM}),
 * its name as a vault ciphertext beside its keyed fingerprint, its definition (a term's; may be null for a record type)
 * and its examples (a JSON array of {@code {page, quote}}) as vault ciphertexts, its state, when it was decided, and its
 * version, which a decision names. Equality compares the bytes; {@code toString} shows ids, the kind, the state, lengths
 * and the version only, never a stored value.
 */
public record ProposalRow(UUID id, UUID ownerId, UUID systemId, UUID materialId, UUID versionId, String kind, byte[] name,
                          byte[] nameFingerprint, byte[] definition, byte[] examples, String state, Instant decidedAt,
                          long version, Instant createdAt) {

    public static final String RECORD_TYPE = "record_type";
    public static final String TERM = "term";
    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String MERGED = "merged";
    public static final String REJECTED = "rejected";

    public ProposalRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(systemId, "systemId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        name = name.clone();
        nameFingerprint = nameFingerprint.clone();
        definition = definition == null ? null : definition.clone();
        examples = examples.clone();
    }

    @Override
    public byte[] name() {
        return name.clone();
    }

    @Override
    public byte[] nameFingerprint() {
        return nameFingerprint.clone();
    }

    @Override
    public byte[] definition() {
        return definition == null ? null : definition.clone();
    }

    @Override
    public byte[] examples() {
        return examples.clone();
    }

    /** Whether no decision was taken on it yet. */
    public boolean pending() {
        return PENDING.equals(state);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProposalRow that
                && id.equals(that.id) && ownerId.equals(that.ownerId) && systemId.equals(that.systemId)
                && Objects.equals(materialId, that.materialId) && Objects.equals(versionId, that.versionId)
                && kind.equals(that.kind) && Arrays.equals(name, that.name)
                && Arrays.equals(nameFingerprint, that.nameFingerprint) && Arrays.equals(definition, that.definition)
                && Arrays.equals(examples, that.examples) && state.equals(that.state)
                && Objects.equals(decidedAt, that.decidedAt) && version == that.version
                && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, systemId, materialId, versionId, kind, Arrays.hashCode(name),
                Arrays.hashCode(nameFingerprint), Arrays.hashCode(definition), Arrays.hashCode(examples), state,
                decidedAt, version, createdAt);
    }

    @Override
    public String toString() {
        return "ProposalRow[id=" + id + ", ownerId=" + ownerId + ", systemId=" + systemId + ", materialId=" + materialId
                + ", versionId=" + versionId + ", kind=" + kind + ", state=" + state + ", name=" + name.length
                + " bytes, examples=" + examples.length + " bytes, version=" + version + "]";
    }
}
