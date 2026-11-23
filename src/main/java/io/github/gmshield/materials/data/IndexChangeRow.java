package io.github.gmshield.materials.data;

import java.util.UUID;

/**
 * One row of {@code index_change} (Story 2.1): the input of one {@code index.change} job, and the index recipe its
 * result reported (null until it completes). A passages row ({@code records} false) is a run of the version's passages
 * by ordinal ({@code firstOrdinal} to {@code lastOrdinal}, inclusive). A records row (Story 4.2 part B) is a run of the
 * version's records by first page: there, {@code firstOrdinal} and {@code lastOrdinal} hold the run's first and last
 * page, inclusive. {@code retries}: how many times the retry sweep moved the row to a new job after its job failed (0 to
 * 3; a new set starts at 0).
 */
public record IndexChangeRow(UUID jobId, UUID ownerId, UUID versionId, int firstOrdinal, int lastOrdinal,
                             String recipe, int retries, boolean records) {

    /** The most retries of one run. */
    public static final int MAX_RETRIES = 3;

    /** A passages row. */
    public IndexChangeRow(UUID jobId, UUID ownerId, UUID versionId, int firstOrdinal, int lastOrdinal, String recipe,
                          int retries) {
        this(jobId, ownerId, versionId, firstOrdinal, lastOrdinal, recipe, retries, false);
    }

    /** A passages row of a new set: no retry yet. */
    public IndexChangeRow(UUID jobId, UUID ownerId, UUID versionId, int firstOrdinal, int lastOrdinal, String recipe) {
        this(jobId, ownerId, versionId, firstOrdinal, lastOrdinal, recipe, 0, false);
    }
}
