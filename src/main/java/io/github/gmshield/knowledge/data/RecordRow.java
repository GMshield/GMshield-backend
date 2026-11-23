package io.github.gmshield.knowledge.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code record} as stored (Story 4.2), with the ids of the passages it links to ({@code record_passage}):
 * its version, its ordinal in its step's report, its first page, its core type key, and its name and fields as vault
 * ciphertexts. From Story 4.3 part B (V37) it also holds its name's keyed fingerprint (of the name's unique form), null
 * for a record stored before V37. Equality compares the bytes; {@code toString} shows ids, numbers, the type and lengths
 * only, never a stored value.
 */
public record RecordRow(UUID id, UUID ownerId, UUID versionId, int ordinal, int firstPage, String type, byte[] name,
                        byte[] nameFingerprint, byte[] fields, List<UUID> passageIds, Instant createdAt) {

    public RecordRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(type, "type");
        name = name.clone();
        nameFingerprint = nameFingerprint == null ? null : nameFingerprint.clone();
        fields = fields.clone();
        passageIds = List.copyOf(passageIds);
    }

    @Override
    public byte[] name() {
        return name.clone();
    }

    @Override
    public byte[] nameFingerprint() {
        return nameFingerprint == null ? null : nameFingerprint.clone();
    }

    @Override
    public byte[] fields() {
        return fields.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RecordRow that
                && id.equals(that.id) && ownerId.equals(that.ownerId) && versionId.equals(that.versionId)
                && ordinal == that.ordinal && firstPage == that.firstPage && type.equals(that.type)
                && Arrays.equals(name, that.name) && Arrays.equals(nameFingerprint, that.nameFingerprint)
                && Arrays.equals(fields, that.fields)
                && passageIds.equals(that.passageIds) && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, versionId, ordinal, firstPage, type, Arrays.hashCode(name),
                Arrays.hashCode(nameFingerprint), Arrays.hashCode(fields), passageIds, createdAt);
    }

    @Override
    public String toString() {
        return "RecordRow[id=" + id + ", ownerId=" + ownerId + ", versionId=" + versionId + ", ordinal=" + ordinal
                + ", firstPage=" + firstPage + ", type=" + type + ", name=" + name.length + " bytes, fields="
                + fields.length + " bytes, passages=" + passageIds.size() + "]";
    }
}
