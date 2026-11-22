package io.github.gmshield.scope.data;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code scope_setting} and {@code scope_material_off} (Story 3.5), always within the owner of the work: every read sees
 * that owner's rows only. Writes join the caller's transaction, which must exist.
 */
public interface ScopeStore {

    /** The current owner's stored settings of a campaign; empty when it has none (it reads as the starting ones). */
    Optional<StoredScope> find(UUID campaignId);

    /**
     * Stores the current owner's campaign's settings on the version {@code version}: the row is locked (or, with none
     * yet, inserted, for version 0 only), its switches replaced, the materials off become exactly {@code materialsOff},
     * and its version rises by one. Throws {@link StaleScopeVersion} when the settings are not at {@code version}.
     */
    StoredScope change(UUID campaignId, long version, boolean campaignTier, boolean systemTier, boolean otherTier,
                       boolean modelKnowledge, Set<UUID> materialsOff, Instant now);

    /** Deletes the current owner's settings and switch rows of these campaigns; safe to repeat. */
    void deleteOfCampaigns(Collection<UUID> campaignIds);

    /** Deletes the current owner's switch rows of this material, in every campaign; safe to repeat. */
    void deleteOfMaterial(UUID materialId);
}
