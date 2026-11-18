package io.github.gmshieldfixtures.scopereads.answers.service;

import io.github.gmshieldfixtures.scopereads.materials.service.ScopeReads;
import java.util.List;
import java.util.UUID;

/** The violation: another module reads a campaign's library items without going through scope (AD-9). */
public class DirectRead {

    public List<UUID> sources(ScopeReads reads, UUID campaignId) {
        return reads.readyOfCampaign(campaignId);
    }
}
