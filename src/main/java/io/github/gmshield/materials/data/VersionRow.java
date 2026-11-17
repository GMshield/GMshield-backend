package io.github.gmshield.materials.data;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code material_version}: its state ({@code uploading}, {@code waiting}, ... {@code discarded}), and,
 * once its file is whole and verified, the file's format and size in bytes (null until then).
 */
public record VersionRow(UUID id, UUID ownerId, UUID materialId, String state, String format, Long sizeBytes,
                         Instant createdAt, Instant updatedAt) {
}
