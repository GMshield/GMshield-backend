package io.github.gmshield.scope.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A campaign's scope as {@link Scopes#resolve} found it for its owner (Story 2.2): the allowed sources of each tier,
 * each a material version's id (the {@code source} of the index's payload), and {@code pending}, the ids of the
 * in-scope passages not indexed (their index change has not ended, or its job failed), kept by tier (Story 2.3: the
 * question input groups them so); empty, not computed, in a scope from {@link Scopes#resolveSources}. Only
 * {@link Scopes} makes one, so a scope cannot be forged by another module. {@code toString} names ids and counts
 * only.
 */
public final class ResolvedScope {

    private final UUID ownerId;
    private final UUID campaignId;
    private final ScopeSettings settings;
    private final List<UUID> campaignSources;
    private final List<UUID> systemSources;
    private final List<UUID> campaignPending;
    private final List<UUID> systemPending;

    ResolvedScope(UUID ownerId, UUID campaignId, ScopeSettings settings, List<UUID> campaignSources,
                  List<UUID> systemSources, List<UUID> campaignPending, List<UUID> systemPending) {
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.campaignId = Objects.requireNonNull(campaignId, "campaignId");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.campaignSources = List.copyOf(campaignSources);
        this.systemSources = List.copyOf(systemSources);
        this.campaignPending = List.copyOf(campaignPending);
        this.systemPending = List.copyOf(systemPending);
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
        return switch (tier) {
            case CAMPAIGN -> campaignSources;
            case SYSTEM -> systemSources;
        };
    }

    /** Every allowed source, of every tier. */
    public Set<UUID> allowedSources() {
        Set<UUID> all = new LinkedHashSet<>(campaignSources);
        all.addAll(systemSources);
        return Set.copyOf(all);
    }

    /** The tier of an allowed source; empty for a source outside the scope. */
    public Optional<Tier> tierOf(UUID source) {
        if (campaignSources.contains(source)) {
            return Optional.of(Tier.CAMPAIGN);
        }
        return systemSources.contains(source) ? Optional.of(Tier.SYSTEM) : Optional.empty();
    }

    /**
     * The ids of the in-scope passages not indexed (their index change has not ended, or its job failed), by tier
     * (campaign, then system); within a tier, those of unended runs first, then those of failed runs, each by source,
     * then by ordinal.
     */
    public List<UUID> pending() {
        List<UUID> all = new ArrayList<>(campaignPending);
        all.addAll(systemPending);
        return List.copyOf(all);
    }

    /** The pending passages of a tier's sources, by source, then by ordinal (Story 2.3). */
    public List<UUID> pending(Tier tier) {
        return switch (tier) {
            case CAMPAIGN -> campaignPending;
            case SYSTEM -> systemPending;
        };
    }

    @Override
    public String toString() {
        return "ResolvedScope[ownerId=" + ownerId + ", campaignId=" + campaignId + ", campaignSources="
                + campaignSources.size() + ", systemSources=" + systemSources.size() + ", pending="
                + (campaignPending.size() + systemPending.size()) + "]";
    }
}
