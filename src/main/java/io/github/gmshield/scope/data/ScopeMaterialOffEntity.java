package io.github.gmshield.scope.data;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * {@code scope_material_off}: one material switched off in one campaign's scope. Hibernate fills {@code owner_id} from
 * the owner of the work and filters every query by it; the table's primary key is (owner, campaign, material). The
 * material is a plain id with no reference: a material that is gone is ignored when resolving and dropped on the next
 * change.
 */
@Entity(name = "ScopeMaterialOff")
@Table(name = "scope_material_off")
class ScopeMaterialOffEntity {

    @EmbeddedId
    private Key key;

    @TenantId
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    protected ScopeMaterialOffEntity() {
        // for Hibernate
    }

    ScopeMaterialOffEntity(UUID campaignId, UUID materialId, UUID ownerId) {
        this.key = new Key(campaignId, materialId);
        this.ownerId = ownerId;
    }

    UUID campaignId() {
        return key.campaignId;
    }

    UUID materialId() {
        return key.materialId;
    }

    /** The campaign and the material (the owner is the tenant). */
    @Embeddable
    static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "campaign_id", nullable = false, updatable = false)
        private UUID campaignId;

        @Column(name = "material_id", nullable = false, updatable = false)
        private UUID materialId;

        protected Key() {
            // for Hibernate
        }

        Key(UUID campaignId, UUID materialId) {
            this.campaignId = campaignId;
            this.materialId = materialId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key that && campaignId.equals(that.campaignId)
                    && materialId.equals(that.materialId);
        }

        @Override
        public int hashCode() {
            return 31 * campaignId.hashCode() + materialId.hashCode();
        }
    }
}
