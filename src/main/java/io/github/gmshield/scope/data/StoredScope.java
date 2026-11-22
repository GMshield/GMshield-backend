package io.github.gmshield.scope.data;

import java.util.Set;
import java.util.UUID;

/**
 * A campaign's stored scope settings (Story 3.5): its three tier switches, model knowledge, the ids of the materials
 * switched off and its version. {@code toString} counts the ids.
 */
public record StoredScope(UUID campaignId, boolean campaignTier, boolean systemTier, boolean otherTier,
                          boolean modelKnowledge, Set<UUID> materialsOff, long version) {

    public StoredScope {
        materialsOff = Set.copyOf(materialsOff);
    }

    @Override
    public String toString() {
        return "StoredScope[campaignId=" + campaignId + ", version=" + version + ", materialsOff="
                + materialsOff.size() + "]";
    }
}
