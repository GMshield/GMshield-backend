package io.github.gmshield.conversations.data;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code turn_material_off} (Story 3.5): one material switched off in the scope settings frozen with a turn, written
 * once with the turn. Hibernate fills {@code owner_id} from the owner of the work and filters every query by it; the
 * table's primary key is (owner, turn, material). The material is a plain id kept by value.
 */
@Entity(name = "TurnMaterialOff")
@Table(name = "turn_material_off")
class TurnMaterialOffEntity {

    @EmbeddedId
    private Key key;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    protected TurnMaterialOffEntity() {
        // for Hibernate
    }

    TurnMaterialOffEntity(UUID turnId, UUID materialId, UUID ownerId) {
        this.key = new Key(turnId, materialId);
        this.ownerId = ownerId;
    }

    UUID turnId() {
        return key.turnId;
    }

    UUID materialId() {
        return key.materialId;
    }

    /** The turn and the material (the owner is the tenant). */
    @Embeddable
    static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "turn_id", nullable = false, updatable = false)
        private UUID turnId;

        @Column(name = "material_id", nullable = false, updatable = false)
        private UUID materialId;

        protected Key() {
            // for Hibernate
        }

        Key(UUID turnId, UUID materialId) {
            this.turnId = turnId;
            this.materialId = materialId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key that && turnId.equals(that.turnId) && materialId.equals(that.materialId);
        }

        @Override
        public int hashCode() {
            return 31 * turnId.hashCode() + materialId.hashCode();
        }
    }
}
