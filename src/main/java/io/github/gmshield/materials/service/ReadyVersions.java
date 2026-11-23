package io.github.gmshield.materials.service;

import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.data.MaterialStore;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code ready} versions of a game system's materials (Story 4.2), for the {@code knowledge} module: a system's
 * record types count the records of these versions only, so a version the GM has not accepted counts nowhere. They are
 * the versions of the materials that belong to the system ({@code material_system}) and of those of its campaigns, each
 * once, for the owner of the work ({@link OwnerContext}); another owner's system has none.
 */
@Service
@NamedInterface("service")
public class ReadyVersions {

    private final MaterialStore materials;
    private final Library library;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    ReadyVersions(MaterialStore materials, Library library, ObjectProvider<PlatformTransactionManager> transactions) {
        this.materials = materials;
        this.library = library;
        this.transactions = transactions;
    }

    /**
     * The ids of the owner's {@code ready} versions of the materials of the system {@code systemId} and of its
     * campaigns; empty for an unknown system or another owner's.
     */
    public Optional<Set<UUID>> ofSystem(UUID systemId) {
        OwnerContext.require();
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> library.campaignIdsOf(systemId).map(campaigns -> {
            Set<UUID> ids = new LinkedHashSet<>();
            materials.readyOfSystem(systemId).forEach(version -> ids.add(version.id()));
            for (UUID campaign : campaigns) {
                materials.readyOfCampaign(campaign).forEach(version -> ids.add(version.id()));
            }
            return Set.copyOf(ids);
        }));
    }
}
