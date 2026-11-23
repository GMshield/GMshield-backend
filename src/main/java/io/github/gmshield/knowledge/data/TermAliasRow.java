package io.github.gmshield.knowledge.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code term_alias} as stored (Story 4.4, V39): another name of a term, added when a term proposal is merged
 * into it: its system and term, its name as a vault ciphertext beside its keyed fingerprint, and the merged proposal
 * (null once that proposal went with its material). Equality compares the bytes; {@code toString} shows ids and lengths
 * only, never a stored value.
 */
public record TermAliasRow(UUID id, UUID ownerId, UUID systemId, UUID termId, byte[] name, byte[] nameFingerprint,
                           UUID proposalId, Instant createdAt) {

    public TermAliasRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(systemId, "systemId");
        Objects.requireNonNull(termId, "termId");
        name = name.clone();
        nameFingerprint = nameFingerprint.clone();
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
    public boolean equals(Object other) {
        return other instanceof TermAliasRow that
                && id.equals(that.id) && ownerId.equals(that.ownerId) && systemId.equals(that.systemId)
                && termId.equals(that.termId) && Arrays.equals(name, that.name)
                && Arrays.equals(nameFingerprint, that.nameFingerprint) && Objects.equals(proposalId, that.proposalId)
                && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, systemId, termId, Arrays.hashCode(name), Arrays.hashCode(nameFingerprint),
                proposalId, createdAt);
    }

    @Override
    public String toString() {
        return "TermAliasRow[id=" + id + ", ownerId=" + ownerId + ", systemId=" + systemId + ", termId=" + termId
                + ", proposalId=" + proposalId + ", name=" + name.length + " bytes]";
    }
}
