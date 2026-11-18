package io.github.gmshield.materials.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code passage} (Story 1.18): a passage of a version, its place in the version's reading order
 * ({@code ordinal}, from 0) and its page; its text and its place (section path with boxes or range, as JSON) are vault
 * ciphertexts. Equality compares the bytes; {@code toString} shows ids, numbers and lengths only.
 */
public record PassageRow(UUID id, UUID ownerId, UUID versionId, int ordinal, int page, byte[] text, byte[] place,
                         Instant createdAt) {

    @Override
    public boolean equals(Object other) {
        return other instanceof PassageRow that
                && Objects.equals(id, that.id) && Objects.equals(ownerId, that.ownerId)
                && Objects.equals(versionId, that.versionId) && ordinal == that.ordinal && page == that.page
                && Arrays.equals(text, that.text) && Arrays.equals(place, that.place)
                && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, versionId, ordinal, page, Arrays.hashCode(text), Arrays.hashCode(place),
                createdAt);
    }

    @Override
    public String toString() {
        return "PassageRow[id=" + id + ", versionId=" + versionId + ", ordinal=" + ordinal + ", page=" + page
                + ", text=" + (text == null ? 0 : text.length) + " bytes]";
    }
}
