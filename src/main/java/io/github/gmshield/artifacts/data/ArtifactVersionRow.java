package io.github.gmshield.artifacts.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code record_version} as stored (Story 5.1): the artifact's record, its owner, the version's number (from
 * 1), its name, fields (a JSON array of {@code {name, value}}) and content as vault ciphertexts (the content null when
 * the GM wrote none), and when it was written. Equality compares the bytes; {@code toString} shows ids, the number and
 * lengths only, never a stored value (the name and the fields included).
 */
public record ArtifactVersionRow(UUID recordId, UUID ownerId, int number, byte[] name, byte[] fields, byte[] content,
                                 Instant createdAt) {

    public ArtifactVersionRow {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (number < 1) {
            throw new IllegalArgumentException("artifacts.version_number_invalid");
        }
        name = name.clone();
        fields = fields.clone();
        content = content == null ? null : content.clone();
    }

    @Override
    public byte[] name() {
        return name.clone();
    }

    @Override
    public byte[] fields() {
        return fields.clone();
    }

    @Override
    public byte[] content() {
        return content == null ? null : content.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ArtifactVersionRow that
                && recordId.equals(that.recordId) && ownerId.equals(that.ownerId) && number == that.number
                && Arrays.equals(name, that.name) && Arrays.equals(fields, that.fields)
                && Arrays.equals(content, that.content) && createdAt.equals(that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(recordId, ownerId, number, Arrays.hashCode(name), Arrays.hashCode(fields),
                Arrays.hashCode(content), createdAt);
    }

    @Override
    public String toString() {
        return "ArtifactVersionRow[recordId=" + recordId + ", ownerId=" + ownerId + ", number=" + number + ", name="
                + name.length + " bytes, fields=" + fields.length + " bytes, content="
                + (content == null ? "none" : content.length + " bytes") + "]";
    }
}
