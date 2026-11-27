package io.github.gmshield.scope.service;

import io.github.gmshield.shared.service.OwnerContext;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * {@link CampaignArtifacts} for the scope's unit tests (Story 5.1 part B): the artifacts each test puts in, by owner and
 * campaign, answered as the {@code artifacts} module would (only the owner's, within the campaign asked).
 */
final class StubCampaignArtifacts implements CampaignArtifacts {

    private record Held(UUID owner, UUID campaignId, Artifact artifact) {
    }

    private final Map<UUID, Held> held = new ConcurrentHashMap<>();
    /** The campaigns each artifact is shared into (Story 5.6 part A). */
    private final Map<UUID, Set<UUID>> sharedInto = new ConcurrentHashMap<>();

    /** Puts an artifact of the owner's campaign. */
    void put(UUID owner, UUID campaignId, Artifact artifact) {
        held.put(artifact.id(), new Held(owner, campaignId, artifact));
    }

    /** Shares the artifact {@code artifactId} into the campaign {@code campaignId} (Story 5.6 part A). */
    void share(UUID artifactId, UUID campaignId) {
        sharedInto.computeIfAbsent(artifactId, id -> ConcurrentHashMap.newKeySet()).add(campaignId);
    }

    /** Story 5.2 part B: no artifact is deleted in the scope's unit tests. */
    @Override
    public Set<UUID> deleted(Collection<UUID> ids) {
        OwnerContext.require();
        return Set.of();
    }

    @Override
    public Set<UUID> live(UUID campaignId, Collection<UUID> ids) {
        UUID owner = OwnerContext.require();
        return ids.stream().distinct().map(held::get)
                .filter(one -> one != null && one.owner().equals(owner) && one.campaignId().equals(campaignId))
                .map(one -> one.artifact().id()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<UUID> ids(UUID campaignId) {
        UUID owner = OwnerContext.require();
        return held.values().stream().filter(one -> one.owner().equals(owner) && one.campaignId().equals(campaignId))
                .map(one -> one.artifact().id()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<UUID> shared(UUID campaignId) {
        UUID owner = OwnerContext.require();
        return held.values().stream().filter(one -> one.owner().equals(owner) && sharedInto
                .getOrDefault(one.artifact().id(), Set.of()).contains(campaignId))
                .map(one -> one.artifact().id()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public List<Artifact> readShared(UUID campaignId, Collection<UUID> ids) {
        Set<UUID> shared = shared(campaignId);
        return ids.stream().distinct().filter(shared::contains).map(held::get).map(Held::artifact).toList();
    }

    @Override
    public List<Artifact> read(UUID campaignId, Collection<UUID> ids) {
        UUID owner = OwnerContext.require();
        return ids.stream().distinct().map(held::get)
                .filter(one -> one != null && one.owner().equals(owner) && one.campaignId().equals(campaignId))
                .map(Held::artifact).toList();
    }
}
