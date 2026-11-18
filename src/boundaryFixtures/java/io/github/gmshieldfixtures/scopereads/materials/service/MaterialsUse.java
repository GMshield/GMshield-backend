package io.github.gmshieldfixtures.scopereads.materials.service;

import java.util.List;
import java.util.UUID;

/** Allowed: its own module uses it. */
public class MaterialsUse {

    public List<UUID> sources(ScopeReads reads, UUID campaignId) {
        return reads.readyOfCampaign(campaignId);
    }
}
