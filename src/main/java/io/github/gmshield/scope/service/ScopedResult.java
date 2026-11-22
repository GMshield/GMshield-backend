package io.github.gmshield.scope.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A search result read within a campaign's scope (Story 3.9): the passage, its source (a material version), its material
 * and that material's name, its section path (outermost first), its page, its text and the tier of its source in that
 * scope. {@code toString} names ids, the page and the tier only.
 */
public record ScopedResult(UUID passageId, UUID versionId, UUID materialId, String materialName,
                           List<String> sectionPath, int page, String text, Tier tier) {

    public ScopedResult {
        sectionPath = List.copyOf(sectionPath);
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(tier, "tier");
    }

    @Override
    public String toString() {
        return "ScopedResult[passageId=" + passageId + ", versionId=" + versionId + ", page=" + page + ", tier=" + tier
                + "]";
    }
}
