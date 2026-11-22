package io.github.gmshield.scope.service;

/**
 * A tier of a campaign's scope (Story 2.2): every allowed source and every passage served belongs to one. The order of
 * the constants is the tier order everywhere (Story 3.5): campaign, system, other.
 */
public enum Tier {

    /** The campaign's own materials ({@code material.campaign_id}). */
    CAMPAIGN("campaign"),
    /** The materials of the campaign's game system ({@code material_system}). */
    SYSTEM("system"),
    /**
     * Story 3.5: the owner's other {@code ready} materials, those of the GM's other campaigns and systems (archived
     * campaigns' included).
     */
    OTHER("other");

    private final String word;

    Tier(String word) {
        this.word = word;
    }

    /** The tier as the internal and public APIs write it. */
    public String word() {
        return word;
    }
}
