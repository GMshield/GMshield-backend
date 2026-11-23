package io.github.gmshield.scope.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A record read within a campaign's scope (Story 4.3 part B, a search's record result): the record, its source (a
 * material version), its material and that material's name, the section path and page of its first passage (on its
 * first page), the ids of its passages on that page, its core type's key, its name and the tier of its source in that
 * scope. {@code toString} names ids, the type, the page and the tier only, never the name.
 */
public record ScopedRecord(UUID recordId, UUID versionId, UUID materialId, String materialName,
                           List<String> sectionPath, int page, List<UUID> passageIds, String recordType, String name,
                           Tier tier) {

    public ScopedRecord {
        sectionPath = List.copyOf(sectionPath);
        passageIds = List.copyOf(passageIds);
        Objects.requireNonNull(recordType, "recordType");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(tier, "tier");
    }

    @Override
    public String toString() {
        return "ScopedRecord[recordId=" + recordId + ", versionId=" + versionId + ", recordType=" + recordType
                + ", page=" + page + ", tier=" + tier + "]";
    }
}
