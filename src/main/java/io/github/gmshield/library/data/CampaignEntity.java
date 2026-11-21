package io.github.gmshield.library.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.OptimisticLock;
import org.hibernate.annotations.TenantId;

/**
 * {@code campaign}: Hibernate fills {@code owner_id} from the owner of the work and filters every query by it.
 * Its system is a plain id: the database's key on (owner, system) keeps it within the same owner.
 *
 * <p>Story 3.2 (V23): its {@code version} is Hibernate's: a rename, an archive or a restore raises it, and an update of
 * a row whose version moved meanwhile fails. Choosing it ({@code chosen_at}) and filling a missing name fingerprint
 * never raise it, so neither makes an open rename stale.
 *
 * <p>Story 3.2 part B (V24): it has a {@code state}: every read of the store but the deletion's own takes
 * {@code ready} rows only.
 */
@Entity(name = "LibraryCampaign")
@Table(name = "campaign")
class CampaignEntity {

    static final String READY = "ready";
    static final String DELETING = "deleting";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "system_id", nullable = false, updatable = false)
    private UUID systemId;

    @Column(name = "name", nullable = false)
    private byte[] name;

    @OptimisticLock(excluded = true)
    @Column(name = "name_fingerprint")
    private byte[] nameFingerprint;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @OptimisticLock(excluded = true)
    @Column(name = "chosen_at")
    private Instant chosenAt;

    /** {@value #READY}, or {@value #DELETING} from the GM's delete until the row goes (Story 3.2 part B, V24). */
    @Column(name = "state", nullable = false)
    private String state = READY;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CampaignEntity() {
        // for Hibernate
    }

    CampaignEntity(CampaignRow row) {
        this.id = row.id();
        this.ownerId = row.ownerId();
        this.systemId = row.systemId();
        this.name = row.name().clone();
        this.nameFingerprint = SystemRow.copy(row.nameFingerprint());
        this.archivedAt = row.archivedAt();
        this.chosenAt = row.chosenAt();
        this.createdAt = row.createdAt();
        this.updatedAt = row.createdAt();
    }

    long version() {
        return version;
    }

    boolean archived() {
        return archivedAt != null;
    }

    boolean fingerprinted() {
        return nameFingerprint != null;
    }

    /**
     * Replaces the name and whether it is archived; Hibernate raises the version when it writes them. Archiving forgets
     * when it was chosen.
     */
    void change(CampaignChange change) {
        this.name = change.name().clone();
        this.nameFingerprint = SystemRow.copy(change.nameFingerprint());
        if (change.archived() && archivedAt == null) {
            this.archivedAt = change.at();
            this.chosenAt = null;
        } else if (!change.archived()) {
            this.archivedAt = null;
        }
        this.updatedAt = change.at();
    }

    /** The GM made it the current campaign at {@code at}; the version stays. */
    void choose(Instant at) {
        this.chosenAt = at;
    }

    /** Its name fingerprint, filled once (a row from before V23); the version stays. */
    void fingerprint(byte[] fingerprint) {
        this.nameFingerprint = fingerprint.clone();
    }

    /**
     * The campaign is being deleted (Story 3.2 part B): it leaves every read, and its name fingerprint becomes its own
     * id (16 bytes, never a keyed fingerprint's 32), so the name is free in its system at once.
     */
    void markDeleting(Instant at) {
        this.state = DELETING;
        ByteBuffer id16 = ByteBuffer.allocate(16);
        id16.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        this.nameFingerprint = id16.array();
        this.updatedAt = at;
    }

    CampaignRow row() {
        return new CampaignRow(id, ownerId, systemId, name.clone(), SystemRow.copy(nameFingerprint), archivedAt, chosenAt,
                version, createdAt);
    }
}
