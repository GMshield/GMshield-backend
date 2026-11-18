package io.github.gmshield.materials.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code page_view} (Story 1.19): what a page of a version shows, its lines or text encrypted. Hibernate fills
 * {@code owner_id} from the owner of the work and filters every query by it. Its version and its picture are plain
 * ids: the database's keys on (owner, version) and (owner, picture) keep them within the same owner. Written once,
 * never changed; a report carrying a page's view removes the page's earlier one first.
 */
@Entity(name = "PageView")
@Table(name = "page_view")
class PageViewEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Column(name = "page", nullable = false, updatable = false)
    private int page;

    @Column(name = "picture_file_id", updatable = false)
    private UUID pictureFileId;

    @Column(name = "width", updatable = false)
    private Integer width;

    @Column(name = "height", updatable = false)
    private Integer height;

    @Column(name = "content", nullable = false, updatable = false)
    private byte[] content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PageViewEntity() {
        // for Hibernate
    }

    PageViewEntity(PageViewRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.versionId = row.versionId();
        this.page = row.page();
        this.pictureFileId = row.pictureFileId();
        this.width = row.width();
        this.height = row.height();
        this.content = row.content().clone();
        this.createdAt = row.createdAt();
    }

    PageViewRow row() {
        return new PageViewRow(id, ownerId, versionId, page, pictureFileId, width, height, content.clone(), createdAt);
    }
}
