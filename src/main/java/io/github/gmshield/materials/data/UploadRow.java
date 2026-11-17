package io.github.gmshield.materials.data;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code upload}: a tus upload of {@code length} bytes, of which {@code offset} are verified and stored as
 * {@code pieceCount} encrypted pieces. {@code format} is decided from the first part's content. The lease
 * ({@code busyToken}, {@code busyUntil}) lets one request at a time write. States: {@code open}, {@code complete},
 * {@code removing}. Holds no content and no name.
 */
public record UploadRow(UUID id, UUID ownerId, UUID versionId, long length, long offset, int pieceCount,
                        String format, Instant expiresAt, UUID busyToken, Instant busyUntil, String state,
                        Instant createdAt, Instant updatedAt) {

    public static final String OPEN = "open";
    public static final String COMPLETE = "complete";
    public static final String REMOVING = "removing";
}
