package io.github.gmshield.library.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code roll} (Story 6.4, V56): Hibernate fills {@code owner_id} from the owner of the work and filters every query by
 * it. Its Shield is a plain id: the database's key on (owner, shield) keeps it within the same owner. A stored roll is
 * never edited, so it has no version; it is only read, counted and deleted here (its insert is
 * {@link JpaLibraryStore#insertRoll}'s one statement).
 */
@Entity(name = "LibraryRoll")
@Table(name = "roll")
class RollEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "shield_id", nullable = false, updatable = false)
    private UUID shieldId;

    @Column(name = "roll_key", nullable = false, updatable = false)
    private UUID rollKey;

    @Column(name = "content", nullable = false, updatable = false)
    private byte[] content;

    @Column(name = "rolled_at", nullable = false, updatable = false)
    private Instant rolledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RollEntity() {
        // for Hibernate
    }

    RollRow row() {
        return new RollRow(id, ownerId, shieldId, rollKey, SystemRow.copy(content), rolledAt, createdAt);
    }
}
