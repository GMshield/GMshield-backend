package io.github.gmshield.materials.data;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code version_file} (Story 1.15): a file of the encrypted file store, under the owner and
 * {@code fileId}, that belongs to a version: the cleaned original whole ({@value #ORIGINAL}), a batch of its pages
 * ({@value #BATCH}), from Story 1.16, the extracted text of some of its pages ({@value #TEXT}) or, from Story 1.19,
 * the WebP picture of one page ({@value #PICTURE}), with the pages it holds, its size and its piece count.
 */
public record VersionFileRow(UUID id, UUID ownerId, UUID versionId, UUID fileId, String role, int firstPage,
                             int lastPage, long sizeBytes, int pieceCount, Instant createdAt) {

    public static final String ORIGINAL = "original";
    public static final String BATCH = "batch";
    public static final String TEXT = "text";
    public static final String PICTURE = "picture";
}
