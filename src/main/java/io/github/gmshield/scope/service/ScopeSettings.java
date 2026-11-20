package io.github.gmshield.scope.service;

/**
 * What a campaign's scope takes in (Story 2.2, FR-22): the campaign tier (the campaign's own materials), the system
 * tier (the materials of its game system), the other tier, and whether the model may answer from its own knowledge.
 * A parameter of every resolve, never stored here: Story 2.4 freezes it with a job, Story 3.5 stores it per campaign.
 * The other tier does not exist before Story 3.5: {@code otherTier = true} is refused.
 */
public record ScopeSettings(boolean campaignTier, boolean systemTier, boolean otherTier, boolean modelKnowledge) {

    /** Every campaign's scope until the GM can change it (Epic 3): campaign and system on, other and model off. */
    public static final ScopeSettings STARTING = new ScopeSettings(true, true, false, false);

    public ScopeSettings {
        if (otherTier) {
            throw new IllegalArgumentException("scope.other_tier_unavailable");
        }
    }
}
