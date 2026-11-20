package io.github.gmshieldfixtures.scopereads.materials.service;

import java.util.List;
import java.util.UUID;

/** Stands for {@code materials.service.ScopeReads}: the library reads a campaign's scope is made of. */
public class ScopeReads {

    public List<UUID> readyOfCampaign(UUID campaignId) {
        return List.of(campaignId);
    }
}
