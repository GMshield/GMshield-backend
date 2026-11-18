package io.github.gmshield.materials.data;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code upload}: a tus upload of {@code length} bytes, of which {@code offset} are verified and stored as
 * {@code pieceCount} encrypted pieces. {@code format} is decided from the first part's content. The lease
 * ({@code busyToken}, {@code busyUntil}) lets one request at a time write. States: {@code open}, {@code complete},
 * {@code removing} and, from Story 1.21, {@code failed}. Holds no content. From Story 1.21, a member of an image set
 * has its {@code position} in the set and its file name encrypted ({@code name}, bound to the row); a failed member
 * has its {@code failureCode}. All three are null outside a set.
 */
public record UploadRow(UUID id, UUID ownerId, UUID versionId, long length, long offset, int pieceCount,
                        String format, Instant expiresAt, UUID busyToken, Instant busyUntil, String state,
                        Instant createdAt, Instant updatedAt, Integer position, byte[] name, String failureCode) {

    public static final String OPEN = "open";
    public static final String COMPLETE = "complete";
    public static final String REMOVING = "removing";
    public static final String FAILED = "failed";
    /** The entity and field a member's name is bound to in the vault. */
    public static final String ENTITY = "upload";
    public static final String FIELD_NAME = "name";

    /** An upload that is not a member of a set. */
    public UploadRow(UUID id, UUID ownerId, UUID versionId, long length, long offset, int pieceCount, String format,
                     Instant expiresAt, UUID busyToken, Instant busyUntil, String state, Instant createdAt,
                     Instant updatedAt) {
        this(id, ownerId, versionId, length, offset, pieceCount, format, expiresAt, busyToken, busyUntil, state,
                createdAt, updatedAt, null, null, null);
    }

    /** Whether this upload is a member of an image set (Story 1.21). */
    public boolean member() {
        return position != null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UploadRow row && id.equals(row.id) && ownerId.equals(row.ownerId)
                && versionId.equals(row.versionId) && length == row.length && offset == row.offset
                && pieceCount == row.pieceCount && java.util.Objects.equals(format, row.format)
                && java.util.Objects.equals(expiresAt, row.expiresAt) && java.util.Objects.equals(busyToken, row.busyToken)
                && java.util.Objects.equals(busyUntil, row.busyUntil) && state.equals(row.state)
                && java.util.Objects.equals(createdAt, row.createdAt) && java.util.Objects.equals(updatedAt, row.updatedAt)
                && java.util.Objects.equals(position, row.position) && java.util.Arrays.equals(name, row.name)
                && java.util.Objects.equals(failureCode, row.failureCode);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(id, state, offset, position);
    }

    @Override
    public String toString() {
        return "UploadRow[id=" + id + ", versionId=" + versionId + ", state=" + state + ", position=" + position + "]";
    }
}
