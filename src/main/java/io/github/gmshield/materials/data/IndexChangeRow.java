package io.github.gmshield.materials.data;

import java.util.UUID;

/**
 * One row of {@code index_change} (Story 2.1): the input of one {@code index.change} job, a run of the version's
 * passages by ordinal ({@code firstOrdinal} to {@code lastOrdinal}, inclusive), and the index recipe its result
 * reported (null until it completes). {@code retries}: how many times the retry sweep moved the row to a new job after
 * its job failed (0 to 3; a new set starts at 0).
 */
public record IndexChangeRow(UUID jobId, UUID ownerId, UUID versionId, int firstOrdinal, int lastOrdinal,
                             String recipe, int retries) {

    /** The most retries of one run. */
    public static final int MAX_RETRIES = 3;

    /** A row of a new set: no retry yet. */
    public IndexChangeRow(UUID jobId, UUID ownerId, UUID versionId, int firstOrdinal, int lastOrdinal, String recipe) {
        this(jobId, ownerId, versionId, firstOrdinal, lastOrdinal, recipe, 0);
    }
}
