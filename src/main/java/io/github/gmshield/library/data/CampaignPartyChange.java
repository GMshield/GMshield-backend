package io.github.gmshield.library.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * A change of a campaign's party as it is stored (Story 7.1): the row, the version the change was made on, and both
 * values after it, each a vault ciphertext of its decimal text, null for not set. Equality compares the bytes;
 * {@code toString} shows the id, lengths and the version only, never a stored value.
 */
public record CampaignPartyChange(UUID id, long version, byte[] size, byte[] level, Instant at) {

    @Override
    public boolean equals(Object other) {
        return other instanceof CampaignPartyChange that
                && Objects.equals(id, that.id) && version == that.version
                && Arrays.equals(size, that.size) && Arrays.equals(level, that.level) && Objects.equals(at, that.at);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, version, Arrays.hashCode(size), Arrays.hashCode(level), at);
    }

    @Override
    public String toString() {
        return "CampaignPartyChange[id=" + id + ", version=" + version + ", size=" + SystemRow.length(size)
                + " bytes, level=" + SystemRow.length(level) + " bytes]";
    }
}
