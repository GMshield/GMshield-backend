package io.github.gmshield.scope.service;

import java.util.Set;
import java.util.UUID;

/**
 * What a campaign's scope takes in (Story 2.2, FR-22): the campaign tier (the campaign's own materials), the system
 * tier (the materials of its game system), the other tier (Story 3.5: the owner's other materials), whether the model
 * may answer from its own knowledge, and (Story 3.5) the ids of the materials switched off, each left out of whichever
 * tier it falls in. A material is on unless it is listed here, so a newly attached material starts on; an id whose
 * material is gone or in no tier changes nothing. A parameter of every resolve: Story 3.5 stores it per campaign
 * ({@link CampaignScopes}) and a question freezes it on its turn. {@code toString} counts the ids, never lists them.
 */
public record ScopeSettings(boolean campaignTier, boolean systemTier, boolean otherTier, boolean modelKnowledge,
                            Set<UUID> materialsOff) {

    /** Every campaign's scope until the GM changes it: campaign and system on, other and model off, every material on. */
    public static final ScopeSettings STARTING = new ScopeSettings(true, true, false, false);

    public ScopeSettings {
        materialsOff = materialsOff == null ? Set.of() : Set.copyOf(materialsOff);
    }

    /** These switches, with every material on. */
    public ScopeSettings(boolean campaignTier, boolean systemTier, boolean otherTier, boolean modelKnowledge) {
        this(campaignTier, systemTier, otherTier, modelKnowledge, Set.of());
    }

    /** Whether a tier is switched on. */
    public boolean on(Tier tier) {
        return switch (tier) {
            case CAMPAIGN -> campaignTier;
            case SYSTEM -> systemTier;
            case OTHER -> otherTier;
        };
    }

    /** Whether this material is switched on (it is unless listed as off). */
    public boolean materialOn(UUID materialId) {
        return !materialsOff.contains(materialId);
    }

    @Override
    public String toString() {
        return "ScopeSettings[campaignTier=" + campaignTier + ", systemTier=" + systemTier + ", otherTier=" + otherTier
                + ", modelKnowledge=" + modelKnowledge + ", materialsOff=" + materialsOff.size() + "]";
    }
}
