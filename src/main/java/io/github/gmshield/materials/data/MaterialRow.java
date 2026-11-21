package io.github.gmshield.materials.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code material} as stored: the name is a vault ciphertext. What the GM said about it (Story 1.14) may
 * be null: its kind, its language (null when left to detection) and the campaign it belongs to. From Story 3.3 its
 * {@code version}, which each change of where it belongs raises by one. Equality compares the bytes; {@code toString}
 * shows ids and lengths only, never a stored value or a detail.
 */
public record MaterialRow(UUID id, UUID ownerId, byte[] name, Instant createdAt, String kind, String language,
                          UUID campaignId, long version) {

    /** A material with no details, as Story 1.13 made them. */
    public MaterialRow(UUID id, UUID ownerId, byte[] name, Instant createdAt) {
        this(id, ownerId, name, createdAt, null, null, null);
    }

    /** A new material, at version 0 (Story 1.14). */
    public MaterialRow(UUID id, UUID ownerId, byte[] name, Instant createdAt, String kind, String language,
                       UUID campaignId) {
        this(id, ownerId, name, createdAt, kind, language, campaignId, 0);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MaterialRow that
                && Objects.equals(id, that.id) && Objects.equals(ownerId, that.ownerId)
                && Arrays.equals(name, that.name) && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(kind, that.kind) && Objects.equals(language, that.language)
                && Objects.equals(campaignId, that.campaignId) && version == that.version;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, Arrays.hashCode(name), createdAt, kind, language, campaignId, version);
    }

    @Override
    public String toString() {
        return "MaterialRow[id=" + id + ", ownerId=" + ownerId + ", name=" + (name == null ? 0 : name.length)
                + " bytes, version=" + version + "]";
    }
}
