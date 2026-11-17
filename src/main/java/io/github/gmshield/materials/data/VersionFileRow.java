package io.github.gmshield.materials.data;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code version_file} (Story 1.15): a file of the encrypted file store, under the owner and
 * {@code fileId}, that belongs to a version: the cleaned original whole ({@value #ORIGINAL}) or a batch of its pages
 * ({@value #BATCH}), with the pages it holds, its size and its piece count.
 */
public record VersionFileRow(UUID id, UUID ownerId, UUID versionId, UUID fileId, String role, int firstPage,
                             int lastPage, long sizeBytes, int pieceCount, Instant createdAt) {

    public static final String ORIGINAL = "original";
    public static final String BATCH = "batch";
}
