package io.github.gmshield.artifacts.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code artifact_share} (Story 5.6 part A, V52): written once, deleted whole. Hibernate fills {@code owner_id} from the
 * owner of the work and filters every query by it. The artifact and the campaign are plain ids: the database's keys on
 * (owner, record) and (owner, campaign) keep them within the same owner, and the artifact's removes the rows with it.
 */
@Entity(name = "ArtifactShare")
@Table(name = "artifact_share")
@IdClass(ArtifactShareEntity.Key.class)
class ArtifactShareEntity {

    @Id
    @Column(name = "artifact_id", nullable = false, updatable = false)
    private UUID artifactId;

    @Id
    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ArtifactShareEntity() {
        // for Hibernate
    }

    ArtifactShareEntity(ArtifactShareRow row) {
        this.artifactId = row.artifactId();
        this.campaignId = row.campaignId();
        this.ownerId = row.ownerId();
        this.createdAt = row.createdAt();
    }

    ArtifactShareRow row() {
        return new ArtifactShareRow(artifactId, campaignId, ownerId, createdAt);
    }

    /** The share's identity: its artifact and its campaign. */
    static final class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID artifactId;
        private UUID campaignId;

        Key() {
            // for Hibernate
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key that && Objects.equals(artifactId, that.artifactId)
                    && Objects.equals(campaignId, that.campaignId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(artifactId, campaignId);
        }
    }
}
