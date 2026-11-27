package io.github.gmshield.artifacts.data;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code artifact_share} as stored (Story 5.6 part A, V52): the artifact, the campaign it is shared with,
 * its owner and when it was written. It holds no stored value, so the generated {@code toString} names ids and a time
 * only.
 */
public record ArtifactShareRow(UUID artifactId, UUID campaignId, UUID ownerId, Instant createdAt) {

    public ArtifactShareRow {
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
