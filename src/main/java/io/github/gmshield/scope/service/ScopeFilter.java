package io.github.gmshield.scope.service;

import java.util.UUID;

/**
 * A narrowing of a campaign's scope (Story 3.9, a search's filters): only the sources of {@code tier}, and only the
 * version of the material {@code materialId}; either may be null (no narrowing by it). A filter only ever takes sources
 * out of what the settings allow: a tier switched off, a material switched off or one in no tier leaves nothing, never
 * more. {@code toString} names the tier and whether a material is set.
 */
public record ScopeFilter(Tier tier, UUID materialId) {

    /** No narrowing: every allowed source. */
    public static final ScopeFilter NONE = new ScopeFilter(null, null);

    /** Whether a source of {@code tier} and {@code material} stays in the narrowed scope. */
    boolean keeps(Tier sourceTier, UUID sourceMaterial) {
        return (tier == null || tier == sourceTier) && (materialId == null || materialId.equals(sourceMaterial));
    }

    @Override
    public String toString() {
        return "ScopeFilter[tier=" + tier + ", material=" + (materialId != null) + "]";
    }
}
