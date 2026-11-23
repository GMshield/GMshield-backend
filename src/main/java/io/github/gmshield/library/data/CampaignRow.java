package io.github.gmshield.library.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code campaign} as stored: the name is a vault ciphertext. From Story 3.2 (V23) it has the keyed
 * fingerprint of its name (null on a row the backfill has not reached yet), the time it was archived (null while in
 * use), the time the GM last chose it (null: not since it was made or archived) and its version, which a rename, an
 * archive or a restore names. Equality compares the bytes; {@code toString} shows ids, lengths and the version only,
 * never a stored value.
 */
public record CampaignRow(UUID id, UUID ownerId, UUID systemId, byte[] name, byte[] nameFingerprint, Instant archivedAt,
                          Instant chosenAt, long version, Instant createdAt) {

    /** A new row with a name only and no fingerprint, never archived nor chosen, at version 0 (a row before V23). */
    public CampaignRow(UUID id, UUID ownerId, UUID systemId, byte[] name, Instant createdAt) {
        this(id, ownerId, systemId, name, null, null, null, 0, createdAt);
    }

    /** Whether the GM archived it. */
    public boolean archived() {
        return archivedAt != null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CampaignRow that
                && Objects.equals(id, that.id) && Objects.equals(ownerId, that.ownerId)
                && Objects.equals(systemId, that.systemId) && Arrays.equals(name, that.name)
                && Arrays.equals(nameFingerprint, that.nameFingerprint) && Objects.equals(archivedAt, that.archivedAt)
                && Objects.equals(chosenAt, that.chosenAt) && version == that.version
                && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, systemId, Arrays.hashCode(name), Arrays.hashCode(nameFingerprint), archivedAt,
                chosenAt, version, createdAt);
    }

    @Override
    public String toString() {
        return "CampaignRow[id=" + id + ", ownerId=" + ownerId + ", systemId=" + systemId + ", name="
                + SystemRow.length(name) + " bytes, nameFingerprint=" + SystemRow.length(nameFingerprint)
                + " bytes, archived=" + archived() + ", version=" + version + "]";
    }
}
