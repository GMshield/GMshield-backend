package io.github.gmshield.library.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * An edit of a campaign as it is stored (Story 3.2): the row, the version the edit was made on, and every stored value
 * after it: the name (a vault ciphertext) and its fingerprint (null on a row from before V23 that is only archived or
 * restored), and whether it is archived. Archiving also forgets when it
 * was chosen, so a restored campaign does not come back as the current one. Equality compares the bytes;
 * {@code toString} shows the id, lengths and the version only, never a stored value.
 */
public record CampaignChange(UUID id, long version, byte[] name, byte[] nameFingerprint, boolean archived, Instant at) {

    @Override
    public boolean equals(Object other) {
        return other instanceof CampaignChange that
                && Objects.equals(id, that.id) && version == that.version
                && Arrays.equals(name, that.name) && Arrays.equals(nameFingerprint, that.nameFingerprint)
                && archived == that.archived && Objects.equals(at, that.at);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, version, Arrays.hashCode(name), Arrays.hashCode(nameFingerprint), archived, at);
    }

    @Override
    public String toString() {
        return "CampaignChange[id=" + id + ", version=" + version + ", name=" + SystemRow.length(name)
                + " bytes, nameFingerprint=" + SystemRow.length(nameFingerprint) + " bytes, archived=" + archived + "]";
    }
}
