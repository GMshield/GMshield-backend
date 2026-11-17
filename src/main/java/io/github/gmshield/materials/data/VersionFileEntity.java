package io.github.gmshield.materials.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code version_file} (Story 1.15): Hibernate fills {@code owner_id} from the owner of the work and filters every
 * query by it. Its version is a plain id: the database's key on (owner, version) keeps it within the same owner.
 * Written once, never changed.
 */
@Entity(name = "VersionFile")
@Table(name = "version_file")
class VersionFileEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Column(name = "file_id", nullable = false, updatable = false)
    private UUID fileId;

    @Column(name = "role", nullable = false, updatable = false)
    private String role;

    @Column(name = "first_page", nullable = false, updatable = false)
    private int firstPage;

    @Column(name = "last_page", nullable = false, updatable = false)
    private int lastPage;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "piece_count", nullable = false, updatable = false)
    private int pieceCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected VersionFileEntity() {
        // for Hibernate
    }

    VersionFileEntity(VersionFileRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.versionId = row.versionId();
        this.fileId = row.fileId();
        this.role = row.role();
        this.firstPage = row.firstPage();
        this.lastPage = row.lastPage();
        this.sizeBytes = row.sizeBytes();
        this.pieceCount = row.pieceCount();
        this.createdAt = row.createdAt();
    }

    VersionFileRow row() {
        return new VersionFileRow(id, ownerId, versionId, fileId, role, firstPage, lastPage, sizeBytes, pieceCount,
                createdAt);
    }
}
