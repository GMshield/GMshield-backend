package io.github.gmshield.jobs.data;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code job_output} (Story 1.15): a file a worker sent back under one claim of a job, stored in the file
 * store under the job's owner and {@code id}. States: {@code writing} (pieces arriving), {@code stored} (whole, while
 * the claim lived). Ids, sizes and times only: never content, a name or a hash.
 */
public record JobOutputRow(UUID id, UUID jobId, UUID ownerId, UUID claimId, String state, long sizeBytes, int pieceCount,
                           Instant createdAt, Instant updatedAt) {

    public static final String WRITING = "writing";
    public static final String STORED = "stored";
}
