package io.github.gmshieldfixtures.scopereads.scope.service;

import io.github.gmshieldfixtures.scopereads.materials.service.ScopeReads;
import java.util.List;
import java.util.UUID;

/** Allowed: the scope module, the one resolver, uses it. */
public class ScopeUse {

    public List<UUID> resolve(ScopeReads reads, UUID campaignId) {
        return reads.readyOfCampaign(campaignId);
    }
}
