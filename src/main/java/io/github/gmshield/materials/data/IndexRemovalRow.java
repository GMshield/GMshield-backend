package io.github.gmshield.materials.data;

import java.util.UUID;

/** One row of {@code index_removal} (Story 2.1): the input of one {@code index.removal} job, the version whose points go. */
public record IndexRemovalRow(UUID jobId, UUID ownerId, UUID versionId) {
}
