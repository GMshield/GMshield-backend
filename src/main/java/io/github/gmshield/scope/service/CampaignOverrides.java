package io.github.gmshield.scope.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * What the scope reads of a campaign's overrides (Story 5.5 part A): a port the {@code knowledge} module implements,
 * which owns the relations between records, so {@code scope} depends on no module that holds them. An override links a
 * live artifact of the campaign to the live extracted record it replaces in that campaign only. Everything runs as the
 * owner of the work, in the caller's transaction (or a read of its own), within one campaign of the owner: an artifact
 * of another campaign, a deleting one, another owner's, a tombstoned or unknown record is never answered. Whether the
 * record is in a scope is the scope's own check ({@link Scopes}), never the port's.
 */
public interface CampaignOverrides {

    /**
     * For each of these record ids that names a live extracted record a live artifact of the owner's campaign
     * {@code campaignId} overrides, that artifact's id; any other id is left out.
     */
    Map<UUID, UUID> overriddenBy(UUID campaignId, Collection<UUID> recordIds);

    /**
     * For each of these ids that names a live artifact of the owner's campaign {@code campaignId} that overrides a live
     * extracted record, that record's id; any other id is left out.
     */
    Map<UUID, UUID> overrides(UUID campaignId, Collection<UUID> artifactIds);
}
