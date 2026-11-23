package io.github.gmshield.scope.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Where a cited passage stands within a campaign's scope (Story 2.5): the passage, its source (a material version), its
 * material and that material's name, its section path (outermost first), its page and (Story 3.7) the tier of its
 * source in that scope. {@code toString} names ids, the page and the tier only.
 */
public record CitedPlace(UUID passageId, UUID versionId, UUID materialId, String materialName,
                         List<String> sectionPath, int page, Tier tier) {

    public CitedPlace {
        sectionPath = List.copyOf(sectionPath);
        Objects.requireNonNull(tier, "tier");
    }

    @Override
    public String toString() {
        return "CitedPlace[passageId=" + passageId + ", versionId=" + versionId + ", page=" + page + ", tier=" + tier
                + "]";
    }
}
