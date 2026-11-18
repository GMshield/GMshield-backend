package io.github.gmshield.materials.data;

import java.util.UUID;

/**
 * One row of {@code index_change} (Story 2.1): the input of one {@code index.change} job, a run of the version's
 * passages by ordinal ({@code firstOrdinal} to {@code lastOrdinal}, inclusive), and the index recipe its result
 * reported (null until it completes).
 */
public record IndexChangeRow(UUID jobId, UUID ownerId, UUID versionId, int firstOrdinal, int lastOrdinal,
                             String recipe) {
}
