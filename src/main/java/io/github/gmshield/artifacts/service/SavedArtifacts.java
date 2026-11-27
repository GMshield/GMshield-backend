package io.github.gmshield.artifacts.service;

import io.github.gmshield.artifacts.data.ArtifactShareRow;
import io.github.gmshield.artifacts.data.ArtifactShareStore;
import io.github.gmshield.artifacts.data.ArtifactVersionRow;
import io.github.gmshield.artifacts.data.ArtifactVersionStore;
import io.github.gmshield.conversations.service.ConversationArtifacts;
import io.github.gmshield.knowledge.service.ArtifactRecords;
import io.github.gmshield.knowledge.service.RecordRelations;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Vault;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The artifacts saved from a conversation (Story 5.4 part B): the {@code conversations} module's
 * {@link ConversationArtifacts} port, so a conversation's deletion dialog lists them and its delete takes the ticked
 * ones. It reads the conversation's live artifacts only ({@link ArtifactRecords#ofConversation}: the owner's, not
 * deleting), oldest first, each with its newest version's name decrypted; a delete starts each one's recorded deletion
 * exactly as the GM's delete of an artifact does ({@link Artifacts#delete}), in the caller's transaction. Nothing is
 * logged here but what {@link Artifacts#delete} logs: the conversation's lines count the rest.
 *
 * <p>Story 5.6 part B: each one says whether it is shared, with at least one campaign it is shared with still readable
 * ({@link Library#places}: archived or not, never one being deleted), as {@link Artifacts.Artifact#sharedWith} counts
 * them; one batch of shares and one of campaigns for the whole list.
 */
@Service
class SavedArtifacts implements ConversationArtifacts {

    private final ArtifactRecords records;
    private final RecordRelations relations;
    private final ArtifactVersionStore versions;
    private final ArtifactShareStore shares;
    private final Library library;
    private final Vault vault;
    private final ObjectProvider<Artifacts> artifacts;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    SavedArtifacts(ArtifactRecords records, RecordRelations relations, ArtifactVersionStore versions,
                   ArtifactShareStore shares, Library library, Vault vault, ObjectProvider<Artifacts> artifacts,
                   ObjectProvider<PlatformTransactionManager> transactions) {
        this.records = records;
        this.relations = relations;
        this.versions = versions;
        this.shares = shares;
        this.library = library;
        this.vault = vault;
        this.artifacts = artifacts;
        this.transactions = transactions;
    }

    @Override
    public List<SavedArtifact> saved(UUID conversationId) {
        UUID owner = OwnerContext.require();
        if (conversationId == null) {
            return List.of();
        }
        return reading(() -> {
            List<ArtifactRecords.Identity> found = records.ofConversation(conversationId);
            Map<UUID, ArtifactVersionRow> latest = versions.latest(found.stream().map(ArtifactRecords.Identity::id)
                    .toList());
            // Story 5.5 part A: the dialog's row says when its artifact overrides a record.
            List<UUID> ids = found.stream().map(ArtifactRecords.Identity::id).toList();
            Set<UUID> overriding = relations.overriding(ids);
            Set<UUID> shared = shared(ids);
            List<SavedArtifact> saved = new ArrayList<>(found.size());
            for (ArtifactRecords.Identity identity : found) {
                ArtifactVersionRow version = latest.get(identity.id());
                if (version == null) {
                    continue;
                }
                saved.add(new SavedArtifact(identity.id(), vault.decryptText(Artifacts.binding(Artifacts.FIELD_NAME,
                        identity.id(), owner), version.name()), identity.recordType(),
                        overriding.contains(identity.id()), shared.contains(identity.id())));
            }
            return List.copyOf(saved);
        });
    }

    @Override
    public void deleteSaved(UUID campaignId, UUID conversationId, Set<UUID> ids) {
        OwnerContext.require();
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("artifacts.transaction_required");
        }
        if (ids.isEmpty()) {
            return;
        }
        // Only the conversation's own live artifacts: any other id is ignored, so nothing else is ever deleted.
        for (ArtifactRecords.Identity identity : records.ofConversation(conversationId)) {
            if (ids.contains(identity.id()) && campaignId.equals(identity.campaignId())) {
                artifacts.getObject().delete(campaignId, identity.id());
            }
        }
    }

    /**
     * Those of these artifacts shared with at least one readable campaign (Story 5.6 part B). Runs in the caller's
     * transaction.
     */
    private Set<UUID> shared(List<UUID> ids) {
        List<ArtifactShareRow> found = ids.isEmpty() ? List.of() : shares.ofAll(ids);
        if (found.isEmpty()) {
            return Set.of();
        }
        Set<UUID> readable = library.places(found.stream().map(ArtifactShareRow::campaignId)
                .collect(Collectors.toSet()), List.of()).campaigns().keySet();
        return found.stream().filter(row -> readable.contains(row.campaignId())).map(ArtifactShareRow::artifactId)
                .collect(Collectors.toUnmodifiableSet());
    }

    private <T> T reading(Supplier<T> read) {
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }
}
