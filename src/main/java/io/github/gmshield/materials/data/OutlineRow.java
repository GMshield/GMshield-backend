package io.github.gmshield.materials.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code version_outline} (Story 1.18): the outline of a version's sections, a vault ciphertext of its
 * JSON. Equality compares the bytes; {@code toString} shows ids and the length only.
 */
public record OutlineRow(UUID id, UUID ownerId, UUID versionId, byte[] outline, Instant createdAt) {

    @Override
    public boolean equals(Object other) {
        return other instanceof OutlineRow that
                && Objects.equals(id, that.id) && Objects.equals(ownerId, that.ownerId)
                && Objects.equals(versionId, that.versionId) && Arrays.equals(outline, that.outline)
                && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, versionId, Arrays.hashCode(outline), createdAt);
    }

    @Override
    public String toString() {
        return "OutlineRow[id=" + id + ", versionId=" + versionId + ", outline="
                + (outline == null ? 0 : outline.length) + " bytes]";
    }
}
