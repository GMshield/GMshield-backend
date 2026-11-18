package io.github.gmshield.scope.service;

/** A tier of a campaign's scope (Story 2.2): every allowed source and every passage served belongs to one. */
public enum Tier {

    /** The campaign's own materials ({@code material.campaign_id}). */
    CAMPAIGN("campaign"),
    /** The materials of the campaign's game system ({@code material_system}). */
    SYSTEM("system");

    private final String word;

    Tier(String word) {
        this.word = word;
    }

    /** The tier as the internal API writes it. */
    public String word() {
        return word;
    }
}
