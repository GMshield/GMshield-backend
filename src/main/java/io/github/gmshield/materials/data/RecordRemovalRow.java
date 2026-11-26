package io.github.gmshield.materials.data;

import java.util.UUID;

/**
 * One row of {@code record_removal} (Story 4.5 part B): the input of one {@code index.record_removal} job, the record
 * whose point goes and the version whose points hold it.
 */
public record RecordRemovalRow(UUID jobId, UUID ownerId, UUID versionId, UUID recordId) {
}
