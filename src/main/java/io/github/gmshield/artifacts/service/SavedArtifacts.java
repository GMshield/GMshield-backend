package io.github.gmshield.artifacts.service;

import io.github.gmshield.artifacts.data.ArtifactVersionRow;
import io.github.gmshield.artifacts.data.ArtifactVersionStore;
import io.github.gmshield.conversations.service.ConversationArtifacts;
import io.github.gmshield.knowledge.service.ArtifactRecords;
import io.github.gmshield.knowledge.service.RecordRelations;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Vault;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
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
 */
@Service
class SavedArtifacts implements ConversationArtifacts {

    private final ArtifactRecords records;
    private final RecordRelations relations;
    private final ArtifactVersionStore versions;
    private final Vault vault;
    private final ObjectProvider<Artifacts> artifacts;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    SavedArtifacts(ArtifactRecords records, RecordRelations relations, ArtifactVersionStore versions, Vault vault,
                   ObjectProvider<Artifacts> artifacts, ObjectProvider<PlatformTransactionManager> transactions) {
        this.records = records;
        this.relations = relations;
        this.versions = versions;
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
            Set<UUID> overriding = relations.overriding(found.stream().map(ArtifactRecords.Identity::id).toList());
            List<SavedArtifact> saved = new ArrayList<>(found.size());
            for (ArtifactRecords.Identity identity : found) {
                ArtifactVersionRow version = latest.get(identity.id());
                if (version == null) {
                    continue;
                }
                saved.add(new SavedArtifact(identity.id(), vault.decryptText(Artifacts.binding(Artifacts.FIELD_NAME,
                        identity.id(), owner), version.name()), identity.recordType(),
                        overriding.contains(identity.id())));
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

    private <T> T reading(Supplier<T> read) {
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }
}
