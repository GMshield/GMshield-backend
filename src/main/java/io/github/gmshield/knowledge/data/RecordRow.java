package io.github.gmshield.knowledge.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code record} as stored (Story 4.2), with the ids of the passages it links to ({@code record_passage}):
 * its version, its ordinal in its step's report, its first page, its type key, and its name and fields as vault
 * ciphertexts. From Story 4.3 part B (V37) it also holds its name's keyed fingerprint (of the name's unique form), null
 * for a record stored before V37. From Story 4.5 (V41) it holds its row version ({@code version}, raised by each
 * correction), the time of its last correction ({@code correctedAt}, null for a record never corrected) and the match
 * key its extraction produced, kept from its first correction on ({@code extractedType} and
 * {@code extractedNameFingerprint}, null for a record never corrected; the fingerprint is null too when the record was
 * stored before V37). From Story 4.5 part B (V42) it holds the time of the GM's delete ({@code deletedAt}, null for a
 * live record: a deleted row is a tombstone) and the time its index point's removal finished ({@code pointRemovedAt},
 * null until then, and for a live record). Equality compares the bytes; {@code toString} shows ids, numbers, the type
 * and lengths only, never a stored value.
 */
public record RecordRow(UUID id, UUID ownerId, UUID versionId, int ordinal, int firstPage, String type, byte[] name,
                        byte[] nameFingerprint, byte[] fields, List<UUID> passageIds, Instant createdAt, long version,
                        Instant correctedAt, String extractedType, byte[] extractedNameFingerprint, Instant deletedAt,
                        Instant pointRemovedAt) {

    public RecordRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(type, "type");
        name = name.clone();
        nameFingerprint = nameFingerprint == null ? null : nameFingerprint.clone();
        fields = fields.clone();
        passageIds = List.copyOf(passageIds);
        extractedNameFingerprint = extractedNameFingerprint == null ? null : extractedNameFingerprint.clone();
    }

    /** A record as its extraction stores it: version 0, never corrected. */
    public RecordRow(UUID id, UUID ownerId, UUID versionId, int ordinal, int firstPage, String type, byte[] name,
                     byte[] nameFingerprint, byte[] fields, List<UUID> passageIds, Instant createdAt) {
        this(id, ownerId, versionId, ordinal, firstPage, type, name, nameFingerprint, fields, passageIds, createdAt, 0,
                null, null, null, null, null);
    }

    /** A live record (never deleted) as stored, with its row version and correction (Story 4.5 part A). */
    public RecordRow(UUID id, UUID ownerId, UUID versionId, int ordinal, int firstPage, String type, byte[] name,
                     byte[] nameFingerprint, byte[] fields, List<UUID> passageIds, Instant createdAt, long version,
                     Instant correctedAt, String extractedType, byte[] extractedNameFingerprint) {
        this(id, ownerId, versionId, ordinal, firstPage, type, name, nameFingerprint, fields, passageIds, createdAt,
                version, correctedAt, extractedType, extractedNameFingerprint, null, null);
    }

    /** Whether the GM deleted the record (Story 4.5 part B): the row is a tombstone. */
    public boolean deleted() {
        return deletedAt != null;
    }

    /** Whether the GM corrected the record (Story 4.5). */
    public boolean corrected() {
        return correctedAt != null;
    }

    /** The same row with these passage ids (a store's read: by passage id, or none for a list). */
    public RecordRow withPassages(List<UUID> newPassageIds) {
        return new RecordRow(id, ownerId, versionId, ordinal, firstPage, type, name, nameFingerprint, fields,
                newPassageIds, createdAt, version, correctedAt, extractedType, extractedNameFingerprint, deletedAt,
                pointRemovedAt);
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
    public byte[] extractedNameFingerprint() {
        return extractedNameFingerprint == null ? null : extractedNameFingerprint.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RecordRow that
                && id.equals(that.id) && ownerId.equals(that.ownerId) && versionId.equals(that.versionId)
                && ordinal == that.ordinal && firstPage == that.firstPage && type.equals(that.type)
                && Arrays.equals(name, that.name) && Arrays.equals(nameFingerprint, that.nameFingerprint)
                && Arrays.equals(fields, that.fields)
                && passageIds.equals(that.passageIds) && Objects.equals(createdAt, that.createdAt)
                && version == that.version && Objects.equals(correctedAt, that.correctedAt)
                && Objects.equals(extractedType, that.extractedType)
                && Arrays.equals(extractedNameFingerprint, that.extractedNameFingerprint)
                && Objects.equals(deletedAt, that.deletedAt) && Objects.equals(pointRemovedAt, that.pointRemovedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, versionId, ordinal, firstPage, type, Arrays.hashCode(name),
                Arrays.hashCode(nameFingerprint), Arrays.hashCode(fields), passageIds, createdAt, version, correctedAt,
                extractedType, Arrays.hashCode(extractedNameFingerprint), deletedAt, pointRemovedAt);
    }

    @Override
    public String toString() {
        return "RecordRow[id=" + id + ", ownerId=" + ownerId + ", versionId=" + versionId + ", ordinal=" + ordinal
                + ", firstPage=" + firstPage + ", type=" + type + ", name=" + name.length + " bytes, fields="
                + fields.length + " bytes, passages=" + passageIds.size() + ", version=" + version + ", corrected="
                + corrected() + ", deleted=" + deleted() + ", pointRemoved=" + (pointRemovedAt != null) + "]";
    }
}
