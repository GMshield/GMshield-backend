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
 * makes one, so a scope cannot be forged by another module. From Story 5.1 part B it also holds the ids of the
 * campaign's artifacts when its campaign tier is on ({@link #artifacts}): they are no source of {@link #sources} or
 * {@link #allowedSources}, so no read of a material's passages or records ever reaches one, and only a question's input
 * lists them, among the campaign tier's sources ({@link Scopes#questionInput}). From Story 5.6 part A it holds, apart,
 * the ids of the live artifacts shared into the campaign when its system tier is on ({@link #shared}): the same rules,
 * among the system tier's sources. {@code toString} names ids and counts only.
 */
public final class ResolvedScope {

    private final UUID ownerId;
    private final UUID campaignId;
    private final ScopeSettings settings;
    private final Map<Tier, List<UUID>> sources = new EnumMap<>(Tier.class);
    private final Map<Tier, List<UUID>> pending = new EnumMap<>(Tier.class);
    private final Map<UUID, UUID> materialOf;
    private final Set<String> recordTypes;
    private final Set<UUID> artifacts;
    private final Set<UUID> shared;

    /**
     * {@code sources} and {@code pending} by tier (a missing tier has none); {@code materialOf} the material of each
     * allowed source; {@code recordTypes} the record type keys of the campaign's system (Story 4.4 part B);
     * {@code artifacts} the ids of the campaign's artifacts, none when its campaign tier is off (Story 5.1 part B);
     * {@code shared} the ids of the artifacts shared into the campaign, none when its system tier is off (Story 5.6
     * part A).
     */
    ResolvedScope(UUID ownerId, UUID campaignId, ScopeSettings settings, Map<Tier, List<UUID>> sources,
                  Map<Tier, List<UUID>> pending, Map<UUID, UUID> materialOf, Set<String> recordTypes,
                  Set<UUID> artifacts, Set<UUID> shared) {
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.campaignId = Objects.requireNonNull(campaignId, "campaignId");
        this.settings = Objects.requireNonNull(settings, "settings");
        for (Tier tier : Tier.values()) {
            this.sources.put(tier, List.copyOf(sources.getOrDefault(tier, List.of())));
            this.pending.put(tier, List.copyOf(pending.getOrDefault(tier, List.of())));
        }
        this.materialOf = Map.copyOf(materialOf);
        this.recordTypes = Set.copyOf(recordTypes);
        this.artifacts = Set.copyOf(artifacts);
        this.shared = Set.copyOf(shared);
    }

    /**
     * The ids of the campaign's artifacts (Story 5.1 part B), campaign-tier sources of a question only; none when the
     * campaign tier is off.
     */
    public Set<UUID> artifacts() {
        return artifacts;
    }

    /**
     * The ids of the live artifacts of the owner's other campaigns shared into the campaign (Story 5.6 part A),
     * system-tier sources of a question only; none when the system tier is off.
     */
    public Set<UUID> shared() {
        return shared;
    }

    /**
     * Whether a tier has a source a question may use (Story 5.1 part B): an allowed source, or for the campaign tier one
     * of the campaign's artifacts too, and (Story 5.6 part A) for the system tier an artifact shared into the campaign.
     */
    public boolean hasSources(Tier tier) {
        return !sources.get(tier).isEmpty() || tier == Tier.CAMPAIGN && !artifacts.isEmpty()
                || tier == Tier.SYSTEM && !shared.isEmpty();
    }

    /**
     * The record type keys of the campaign's system when the scope was resolved (Story 4.4 part B): the scope shows a
     * record only when its key is one of them, whatever its tier.
     */
    public Set<String> recordTypes() {
        return recordTypes;
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
                + ", otherSources=" + sources.get(Tier.OTHER).size() + ", pending=" + pending().size()
                + ", recordTypes=" + recordTypes.size() + ", artifacts=" + artifacts.size() + ", shared=" + shared.size()
                + "]";
    }
}
