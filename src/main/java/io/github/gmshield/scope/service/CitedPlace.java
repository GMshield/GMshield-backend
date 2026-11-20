package io.github.gmshield.scope.service;

import java.util.List;
import java.util.UUID;

/**
 * Where a cited passage stands within a campaign's scope (Story 2.5): the passage, its source (a material version), its
 * material and that material's name, its section path (outermost first) and its page. {@code toString} names ids and
 * the page only.
 */
public record CitedPlace(UUID passageId, UUID versionId, UUID materialId, String materialName,
                         List<String> sectionPath, int page) {

    public CitedPlace {
        sectionPath = List.copyOf(sectionPath);
    }

    @Override
    public String toString() {
        return "CitedPlace[passageId=" + passageId + ", versionId=" + versionId + ", page=" + page + "]";
    }
}
