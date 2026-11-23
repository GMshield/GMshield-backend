package io.github.gmshield.scope.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A campaign's scope as {@link Scopes#resolve} found it for its owner (Story 2.2): the allowed sources of each tier,
 * each a material version's id (the {@code source} of the index's payload), and {@code pending}, the ids of the
 * in-scope passages not indexed (their index change has not ended, or its job failed), kept by tier (Story 2.3: the
 * question input groups them so); empty, not computed, in a scope from {@link Scopes#resolveSources}. From Story 3.5
 * there are three tiers (campaign, system, other), and each allowed source knows its material. Only {@link Scopes}
 * makes one, so a scope cannot be forged by another module. {@code toString} names ids and counts only.
 */
public final class ResolvedScope {

    private final UUID ownerId;
    private final UUID campaignId;
    private final ScopeSettings settings;
    private final Map<Tier, List<UUID>> sources = new EnumMap<>(Tier.class);
    private final Map<Tier, List<UUID>> pending = new EnumMap<>(Tier.class);
    private final Map<UUID, UUID> materialOf;

    /**
     * {@code sources} and {@code pending} by tier (a missing tier has none); {@code materialOf} the material of each
     * allowed source.
     */
    ResolvedScope(UUID ownerId, UUID campaignId, ScopeSettings settings, Map<Tier, List<UUID>> sources,
                  Map<Tier, List<UUID>> pending, Map<UUID, UUID> materialOf) {
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.campaignId = Objects.requireNonNull(campaignId, "campaignId");
        this.settings = Objects.requireNonNull(settings, "settings");
        for (Tier tier : Tier.values()) {
            this.sources.put(tier, List.copyOf(sources.getOrDefault(tier, List.of())));
            this.pending.put(tier, List.copyOf(pending.getOrDefault(tier, List.of())));
        }
        this.materialOf = Map.copyOf(materialOf);
    }

    /** The owner the scope was resolved for. */
    public UUID ownerId() {
        return ownerId;
    }

    public UUID campaignId() {
        return campaignId;
    }

    /** The settings it was resolved with. */
    public ScopeSettings settings() {
        return settings;
    }

    /** The allowed sources of a tier, oldest first. */
    public List<UUID> sources(Tier tier) {
        return sources.get(tier);
    }

    /** Every allowed source, of every tier. */
    public Set<UUID> allowedSources() {
        Set<UUID> all = new LinkedHashSet<>();
        for (Tier tier : Tier.values()) {
            all.addAll(sources.get(tier));
        }
        return Set.copyOf(all);
    }

    /** The tier of an allowed source; empty for a source outside the scope. */
    public Optional<Tier> tierOf(UUID source) {
        for (Tier tier : Tier.values()) {
            if (sources.get(tier).contains(source)) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }

    /** The material of an allowed source (Story 3.5); empty for a source outside the scope. */
    public Optional<UUID> materialOf(UUID source) {
        return tierOf(source).map(tier -> materialOf.get(source));
    }

    /**
     * The ids of the in-scope passages not indexed (their index change has not ended, or its job failed), by tier
     * (campaign, system, then other); within a tier, those of unended runs first, then those of failed runs, each by
     * source, then by ordinal.
     */
    public List<UUID> pending() {
        List<UUID> all = new ArrayList<>();
        for (Tier tier : Tier.values()) {
            all.addAll(pending.get(tier));
        }
        return List.copyOf(all);
    }

    /** The pending passages of a tier's sources, by source, then by ordinal (Story 2.3). */
    public List<UUID> pending(Tier tier) {
        return pending.get(tier);
    }

    @Override
    public String toString() {
        return "ResolvedScope[ownerId=" + ownerId + ", campaignId=" + campaignId + ", campaignSources="
                + sources.get(Tier.CAMPAIGN).size() + ", systemSources=" + sources.get(Tier.SYSTEM).size()
                + ", otherSources=" + sources.get(Tier.OTHER).size() + ", pending=" + pending().size() + "]";
    }
}
