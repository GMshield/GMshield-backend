package io.github.gmshield.artifacts.data;

import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code artifact_index_removal} (Story 5.1 part B, V44): the input of one {@code index.artifact_removal} job,
 * the artifact whose points go. It names no row of the artifact, which is gone by then.
 */
public record ArtifactIndexRemovalRow(UUID jobId, UUID ownerId, UUID artifactId) {

    public ArtifactIndexRemovalRow {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(artifactId, "artifactId");
    }
}
