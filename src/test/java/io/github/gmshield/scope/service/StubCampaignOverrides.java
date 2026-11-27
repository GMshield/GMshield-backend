package io.github.gmshield.scope.service;

import io.github.gmshield.shared.service.OwnerContext;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * {@link CampaignOverrides} for the scope's unit tests (Story 5.5 part A): the marks each test puts in, by owner and
 * campaign, answered as the {@code knowledge} module would (only the owner's, within the campaign asked). Whether a
 * side is live is the test's to decide: it puts in only the marks the port would answer.
 */
final class StubCampaignOverrides implements CampaignOverrides {

    private record Mark(UUID owner, UUID campaignId, UUID artifactId, UUID recordId) {
    }

    private final Map<UUID, Mark> byArtifact = new ConcurrentHashMap<>();

    /** Puts a mark: the owner's campaign's artifact overrides the record. */
    void put(UUID owner, UUID campaignId, UUID artifactId, UUID recordId) {
        byArtifact.put(artifactId, new Mark(owner, campaignId, artifactId, recordId));
    }

    @Override
    public Map<UUID, UUID> overriddenBy(UUID campaignId, Collection<UUID> recordIds) {
        UUID owner = OwnerContext.require();
        return byArtifact.values().stream().filter(mark -> mark.owner().equals(owner)
                        && mark.campaignId().equals(campaignId) && recordIds.contains(mark.recordId()))
                .collect(Collectors.toUnmodifiableMap(Mark::recordId, Mark::artifactId));
    }

    @Override
    public Map<UUID, UUID> overrides(UUID campaignId, Collection<UUID> artifactIds) {
        UUID owner = OwnerContext.require();
        return byArtifact.values().stream().filter(mark -> mark.owner().equals(owner)
                        && mark.campaignId().equals(campaignId) && artifactIds.contains(mark.artifactId()))
                .collect(Collectors.toUnmodifiableMap(Mark::artifactId, Mark::recordId));
    }
}
