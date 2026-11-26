package io.github.gmshield.artifacts.data;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code artifact_index} (Story 5.1 part B, V44): the input of one {@code index.artifact} job, the artifact
 * version it indexes (its record and number), the index recipe its result reported (null until it completes), how many
 * times a failed job of it was retried, and when it was written. Ids, numbers and the recipe only.
 */
public record ArtifactIndexRow(UUID jobId, UUID ownerId, UUID recordId, int number, String recipe, int retries,
                               Instant createdAt) {

    /** The most times a failed job of one row is retried with a new one, as an index change's (V20). */
    public static final int MAX_RETRIES = 3;

    public ArtifactIndexRow {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (number < 1 || retries < 0 || retries > MAX_RETRIES) {
            throw new IllegalArgumentException("artifacts.index_row_invalid");
        }
    }
}
