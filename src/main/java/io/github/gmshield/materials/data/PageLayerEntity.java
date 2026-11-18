package io.github.gmshield.materials.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code page_layer} (Story 1.16): a page's layer, for a range of pages of a version. Hibernate fills {@code owner_id} from
 * the owner of the work and filters every query by it. Its version is a plain id: the database's key on
 * (owner, version) keeps it within the same owner. Written once, never changed.
 */
@Entity(name = "PageLayer")
@Table(name = "page_layer")
class PageLayerEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Column(name = "first_page", nullable = false, updatable = false)
    private int firstPage;

    @Column(name = "last_page", nullable = false, updatable = false)
    private int lastPage;

    @Column(name = "layer", nullable = false, updatable = false)
    private String layer;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PageLayerEntity() {
        // for Hibernate
    }

    PageLayerEntity(PageRangeRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.versionId = row.versionId();
        this.firstPage = row.firstPage();
        this.lastPage = row.lastPage();
        this.layer = row.value();
        this.createdAt = row.createdAt();
    }

    PageRangeRow row() {
        return new PageRangeRow(id, ownerId, versionId, firstPage, lastPage, layer, createdAt);
    }
}
