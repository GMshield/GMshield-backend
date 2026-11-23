package io.github.gmshield.knowledge.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code record_type} as stored (Story 4.1): its system, its core key (null for a type that is not core), the
 * name as a vault ciphertext beside its keyed fingerprint, and its version, which a rename names. From Story 4.4 its
 * {@code key} (a core type's core key, or the stable slug of an approved type's name) and its {@code proposalId}, the
 * approving proposal (null exactly for a core type). Equality compares the bytes; {@code toString} shows ids, the keys,
 * lengths and the version only, never a stored value.
 */
public record RecordTypeRow(UUID id, UUID ownerId, UUID systemId, String core, String key, UUID proposalId, byte[] name,
                            byte[] nameFingerprint, long version, Instant createdAt) {

    public RecordTypeRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(systemId, "systemId");
        Objects.requireNonNull(key, "key");
        name = name.clone();
        nameFingerprint = nameFingerprint.clone();
    }

    /** A core type's row (Story 4.1): its key is its core key, and it has no proposal. */
    public RecordTypeRow(UUID id, UUID ownerId, UUID systemId, String core, byte[] name, byte[] nameFingerprint,
                         long version, Instant createdAt) {
        this(id, ownerId, systemId, core, Objects.requireNonNull(core, "core"), null, name, nameFingerprint, version,
                createdAt);
    }

    @Override
    public byte[] name() {
        return name.clone();
    }

    @Override
    public byte[] nameFingerprint() {
        return nameFingerprint.clone();
    }

    /** The same row, renamed and at {@code newVersion}. */
    public RecordTypeRow renamed(byte[] newName, byte[] newFingerprint, long newVersion) {
        return new RecordTypeRow(id, ownerId, systemId, core, key, proposalId, newName, newFingerprint, newVersion,
                createdAt);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RecordTypeRow that
                && id.equals(that.id) && ownerId.equals(that.ownerId) && systemId.equals(that.systemId)
                && Objects.equals(core, that.core) && key.equals(that.key) && Objects.equals(proposalId, that.proposalId)
                && Arrays.equals(name, that.name)
                && Arrays.equals(nameFingerprint, that.nameFingerprint) && version == that.version
                && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, systemId, core, key, proposalId, Arrays.hashCode(name), Arrays.hashCode(nameFingerprint), version,
                createdAt);
    }

    @Override
    public String toString() {
        return "RecordTypeRow[id=" + id + ", ownerId=" + ownerId + ", systemId=" + systemId + ", core=" + core
                + ", key=" + key + ", proposalId=" + proposalId + ", name=" + name.length + " bytes, nameFingerprint="
                + nameFingerprint.length + " bytes, version=" + version + "]";
    }
}
