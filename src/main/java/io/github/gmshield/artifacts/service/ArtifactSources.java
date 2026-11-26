package io.github.gmshield.artifacts.service;

import io.github.gmshield.artifacts.data.ArtifactVersionRow;
import io.github.gmshield.artifacts.data.ArtifactVersionStore;
import io.github.gmshield.knowledge.service.ArtifactRecords;
import io.github.gmshield.library.service.SystemDeletion;
import io.github.gmshield.scope.service.CampaignArtifacts;
import io.github.gmshield.scope.service.ScopedRecordContent;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Vault;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The campaign's artifacts as the scope reads them (Story 5.1 part B): the {@code scope} module's
 * {@link CampaignArtifacts} port, so a campaign's artifacts are campaign-tier sources of its questions. It answers the
 * ids of a campaign's artifacts, and artifacts by id with their newest version decrypted, within that campaign of the
 * owner only; another campaign's, another owner's or an unknown id is left out. Each call reads in the caller's
 * transaction, or in a read-only one of its own. Nothing is logged here: the scope's lines count what it read. From
 * Story 5.2 part B a deleting artifact is left out of every answer, as one that is gone ({@link ArtifactRecords} reads
 * live artifacts only), and {@link #deleted} names the artifacts whose deletion is recorded
 * ({@link SystemDeletion#artifactDeletionsRecorded}), deleting or gone.
 */
@Service
class ArtifactSources implements CampaignArtifacts {

    private final ArtifactRecords records;
    private final ArtifactVersionStore versions;
    private final Vault vault;
    private final ObjectProvider<SystemDeletion> deletions;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    ArtifactSources(ArtifactRecords records, ArtifactVersionStore versions, Vault vault,
                    ObjectProvider<SystemDeletion> deletions, ObjectProvider<PlatformTransactionManager> transactions) {
        this.records = records;
        this.versions = versions;
        this.vault = vault;
        this.deletions = deletions;
        this.transactions = transactions;
    }

    @Override
    public Set<UUID> ids(UUID campaignId) {
        OwnerContext.require();
        return campaignId == null ? Set.of() : reading(() -> Set.copyOf(records.idsOf(List.of(campaignId))));
    }

    @Override
    public Set<UUID> live(UUID campaignId, Collection<UUID> ids) {
        OwnerContext.require();
        if (campaignId == null || ids.isEmpty()) {
            return Set.of();
        }
        return reading(() -> records.findAll(ids).stream().filter(identity -> campaignId.equals(identity.campaignId()))
                .map(ArtifactRecords.Identity::id).collect(Collectors.toUnmodifiableSet()));
    }

    @Override
    public Set<UUID> deleted(Collection<UUID> ids) {
        OwnerContext.require();
        return ids.isEmpty() ? Set.of() : deletions.getObject().artifactDeletionsRecorded(ids);
    }

    @Override
    public List<Artifact> read(UUID campaignId, Collection<UUID> ids) {
        UUID owner = OwnerContext.require();
        if (campaignId == null || ids.isEmpty()) {
            return List.of();
        }
        return reading(() -> {
            List<ArtifactRecords.Identity> found = records.findAll(ids).stream()
                    .filter(identity -> campaignId.equals(identity.campaignId())).toList();
            Map<UUID, ArtifactVersionRow> latest = versions.latest(found.stream().map(ArtifactRecords.Identity::id)
                    .toList());
            List<Artifact> read = new ArrayList<>(found.size());
            for (ArtifactRecords.Identity identity : found) {
                ArtifactVersionRow version = latest.get(identity.id());
                if (version == null) {
                    continue;
                }
                Artifacts.Artifact artifact = Artifacts.open(vault, identity, version, owner);
                read.add(new Artifact(artifact.id(), artifact.recordType(), artifact.name(), artifact.fields().stream()
                        .map(field -> new ScopedRecordContent.Field(field.name(), field.value())).toList(),
                        artifact.content(), artifact.version()));
            }
            return List.copyOf(read);
        });
    }

    private <T> T reading(Supplier<T> read) {
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }
}
