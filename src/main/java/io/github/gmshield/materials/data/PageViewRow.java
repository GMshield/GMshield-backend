package io.github.gmshield.materials.data;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code page_view} (Story 1.19): what a page of a version shows. A PDF page has its picture (a file of the
 * version, {@code pictureFileId}, with its {@code width} and {@code height} in pixels); a text page has none (all
 * three null). {@code content} is a vault ciphertext of the page's lines or text, as JSON. Equality compares the
 * bytes; {@code toString} shows ids, numbers and the length only.
 */
public record PageViewRow(UUID id, UUID ownerId, UUID versionId, int page, UUID pictureFileId, Integer width,
                          Integer height, byte[] content, Instant createdAt) {

    /** Whether the page has a picture. */
    public boolean hasPicture() {
        return pictureFileId != null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PageViewRow that
                && Objects.equals(id, that.id) && Objects.equals(ownerId, that.ownerId)
                && Objects.equals(versionId, that.versionId) && page == that.page
                && Objects.equals(pictureFileId, that.pictureFileId) && Objects.equals(width, that.width)
                && Objects.equals(height, that.height) && Arrays.equals(content, that.content)
                && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ownerId, versionId, page, pictureFileId, width, height, Arrays.hashCode(content),
                createdAt);
    }

    @Override
    public String toString() {
        return "PageViewRow[id=" + id + ", versionId=" + versionId + ", page=" + page + ", pictureFileId="
                + pictureFileId + ", content=" + (content == null ? 0 : content.length) + " bytes]";
    }
}
