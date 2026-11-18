package io.github.gmshield.materials.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code passage} (Story 1.18): a passage of a version, its text and its place encrypted. Hibernate fills
 * {@code owner_id} from the owner of the work and filters every query by it. Its version is a plain id: the database's
 * key on (owner, version) keeps it within the same owner. Written once, never changed; a report carrying passages
 * removes the version's earlier ones first.
 */
@Entity(name = "Passage")
@Table(name = "passage")
class PassageEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Column(name = "ordinal", nullable = false, updatable = false)
    private int ordinal;

    @Column(name = "page", nullable = false, updatable = false)
    private int page;

    @Column(name = "text", nullable = false, updatable = false)
    private byte[] text;

    @Column(name = "place", nullable = false, updatable = false)
    private byte[] place;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PassageEntity() {
        // for Hibernate
    }

    PassageEntity(PassageRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.versionId = row.versionId();
        this.ordinal = row.ordinal();
        this.page = row.page();
        this.text = row.text().clone();
        this.place = row.place().clone();
        this.createdAt = row.createdAt();
    }

    PassageRow row() {
        return new PassageRow(id, ownerId, versionId, ordinal, page, text.clone(), place.clone(), createdAt);
    }
}
