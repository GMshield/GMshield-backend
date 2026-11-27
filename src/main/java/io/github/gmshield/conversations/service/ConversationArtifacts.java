package io.github.gmshield.conversations.service;

import io.github.gmshield.scope.service.Scopes;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What a conversation's deletion reads and takes of the artifacts saved from it (Story 5.4 part B): a port the
 * {@code artifacts} module implements, as it implements the {@code scope} module's {@code CampaignArtifacts}, so
 * {@code conversations} depends on no module that holds artifacts. Everything runs as the owner of the work: another
 * owner's artifact, another conversation's or an unknown id is never answered, and a deleting one (the GM deleted it,
 * and its rows are still being removed) is never answered either.
 */
public interface ConversationArtifacts {

    /**
     * The owner's live artifacts saved from the conversation {@code conversationId}, oldest first, each with its newest
     * version's name decrypted; none for a conversation with none, or another owner's.
     */
    List<SavedArtifact> saved(UUID conversationId);

    /**
     * Starts the recorded deletion of each of these ids that is one of {@link #saved} for the conversation
     * {@code conversationId} of the campaign {@code campaignId}, as the GM's delete of an artifact does; any other id
     * (another conversation's, another owner's, a deleting or unknown one) is ignored. Runs in the caller's
     * transaction, which must exist, so a refusal after it rolls these deletions back.
     */
    void deleteSaved(UUID campaignId, UUID conversationId, Set<UUID> ids);

    /**
     * An artifact saved from a conversation as its deletion's dialog lists it: its id, its newest version's name, its
     * record type's key, (Story 5.5 part A) whether it overrides a record and (Story 5.6 part B) whether it is shared
     * with at least one readable campaign. {@code toString} names the id and the type only.
     */
    record SavedArtifact(UUID id, String name, String recordType, boolean overrides, boolean shared) {

        public SavedArtifact {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(recordType, "recordType");
        }

        /** An artifact that overrides nothing and is not shared. */
        public SavedArtifact(UUID id, String name, String recordType) {
            this(id, name, recordType, false, false);
        }

        @Override
        public String toString() {
            return "SavedArtifact[id=" + id + ", recordType=" + Scopes.loggedType(recordType) + "]";
        }
    }
}
