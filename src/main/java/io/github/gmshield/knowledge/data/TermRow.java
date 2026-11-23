package io.github.gmshield.knowledge.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code term} as stored (Story 4.4, V39): its system, its name as a vault ciphertext beside its keyed
 * fingerprint, its definition (quoted from its material) as a vault ciphertext, the approving proposal, and its version.
 * Equality compares the bytes; {@code toString} shows ids, lengths and the version only, never a stored value.
 */
public record TermRow(UUID id, UUID ownerId, UUID systemId, byte[] name, byte[] nameFingerprint, byte[] definition,
                      UUID proposalId, long version, Instant createdAt) {

    public TermRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(systemId, "systemId");
        Objects.requireNonNull(proposalId, "proposalId");
        name = name.clone();
        nameFingerprint = nameFingerprint.clone();
        definition = definition.clone();
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
        return definition.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TermRow that
                && id.equals(that.id) && ownerId.equals(that.ownerId) && systemId.equals(that.systemId)
                && Arrays.equals(name, that.name) && Arrays.equals(nameFingerprint, that.nameFingerprint)
                && Arrays.equals(definition, that.definition) && proposalId.equals(that.proposalId)
                && version == that.version && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, systemId, Arrays.hashCode(name), Arrays.hashCode(nameFingerprint),
                Arrays.hashCode(definition), proposalId, version, createdAt);
    }

    @Override
    public String toString() {
        return "TermRow[id=" + id + ", ownerId=" + ownerId + ", systemId=" + systemId + ", proposalId=" + proposalId
                + ", name=" + name.length + " bytes, definition=" + definition.length + " bytes, version=" + version
                + "]";
    }
}
