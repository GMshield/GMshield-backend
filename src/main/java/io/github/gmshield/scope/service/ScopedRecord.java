package io.github.gmshield.scope.service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A record read within a campaign's scope (Story 4.3 part B, a search's record result): the record, its source (a
 * material version), its material and that material's name, the section path and page of its first passage (on its
 * first page), the ids of its passages on that page, its core type's key, its name and the tier of its source in that
 * scope. From Story 5.1 part B it may be one of the campaign's artifacts ({@link #artifact}): then
 * {@code artifactVersion} is its newest version's number, it has no version, material or passage, its section path is
 * empty, its page 1 and its tier the campaign's. {@code toString} names ids, the type, the page and the tier only, never
 * the name.
 */
public record ScopedRecord(UUID recordId, UUID versionId, UUID materialId, String materialName,
                           List<String> sectionPath, int page, List<UUID> passageIds, String recordType, String name,
                           Tier tier, Integer artifactVersion) {

    public ScopedRecord {
        sectionPath = List.copyOf(sectionPath);
        passageIds = List.copyOf(passageIds);
        Objects.requireNonNull(recordType, "recordType");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(tier, "tier");
    }

    /** An extracted record, placed at its first page. */
    public ScopedRecord(UUID recordId, UUID versionId, UUID materialId, String materialName, List<String> sectionPath,
                        int page, List<UUID> passageIds, String recordType, String name, Tier tier) {
        this(recordId, versionId, materialId, materialName, sectionPath, page, passageIds, recordType, name, tier, null);
    }

    /** A campaign's artifact as a campaign-tier record (Story 5.1 part B): page 1, no place and no passage. */
    static ScopedRecord artifact(UUID artifactId, String recordType, String name, int version) {
        return new ScopedRecord(artifactId, null, null, null, List.of(), 1, List.of(), recordType, name, Tier.CAMPAIGN,
                version);
    }

    /** Whether it is one of the campaign's artifacts (Story 5.1 part B). */
    public boolean artifact() {
        return artifactVersion != null;
    }

    @Override
    public String toString() {
        return "ScopedRecord[recordId=" + recordId + ", versionId=" + versionId + ", recordType=" + recordType
                + ", page=" + page + ", tier=" + tier + ", artifactVersion=" + artifactVersion + "]";
    }
}
