package io.github.gmshield.conversations.data;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The conversation tables, always within the owner of the work: every read sees that owner's rows only, and a write
 * for another owner is refused. Writes join the caller's transaction, which must exist.
 */
public interface ConversationStore {

    void insertConversation(ConversationRow row);

    /**
     * The current owner's conversation of a campaign in {@code area} whose newest question was asked last (Story 3.10);
     * empty when it has none there.
     */
    Optional<ConversationRow> latestConversation(UUID campaignId, String area);

    /**
     * The current owner's conversations of a campaign in {@code area} (Story 3.10), the most recently asked first (then
     * by id, newest first), skipping the first {@code offset}, at most {@code limit}.
     */
    List<ConversationRow> conversations(UUID campaignId, String area, int offset, int limit);

    /** How many conversations the current owner's campaign has in {@code area} (Story 3.10). */
    long conversationCount(UUID campaignId, String area);

    /**
     * The first turn of each of these conversations of the current owner (Story 3.10: the oldest, then by id), by
     * conversation; a conversation with no turn, or another owner's, is absent.
     */
    Map<UUID, TurnRow> firstTurns(Collection<UUID> conversationIds);

    /** How many turns each of these conversations of the current owner has (Story 3.10); one with none is absent. */
    Map<UUID, Long> turnCounts(Collection<UUID> conversationIds);

    /** The current owner's newest {@code limit} turns of a conversation (Story 3.10), oldest first. */
    List<TurnRow> newestTurns(UUID conversationId, int limit);

    /**
     * A question was asked in the owner's conversation at {@code at} (Story 3.10): its {@code last_asked_at} moves
     * there (never back); its version stays. False for an unknown conversation, another owner's or (Story 3.10 part B)
     * a deleting one.
     */
    boolean markAsked(UUID id, Instant at);

    /**
     * Renames the owner's conversation (Story 3.10) on {@code version} to the ciphertext {@code name}, raising its
     * version by one; empty for an unknown conversation or another owner's. Throws {@link ConversationConflict} when the
     * row is no longer at {@code version}; nothing changes then.
     */
    Optional<ConversationRow> renameConversation(UUID id, long version, byte[] name);

    /** The current owner's {@code ready} conversation (Story 3.10 part B: a deleting one is not found). */
    Optional<ConversationRow> findConversation(UUID id);

    /**
     * The current owner's conversation, {@code ready} or {@code deleting} (Story 3.10 part B): for its deletion, and for
     * an answer job's call already under way when the conversation turned deleting.
     */
    Optional<ConversationRow> findConversationAnyState(UUID id);

    /**
     * The current owner's conversation becomes {@code deleting} (Story 3.10 part B), its version unchanged; true also
     * when it was already, false for an unknown conversation or another owner's.
     */
    boolean markDeleting(UUID id);

    /** How many turns the current owner's conversation has (Story 3.2); 0 for an unknown one or another owner's. */
    long turnCount(UUID conversationId);

    void insertTurn(TurnRow row);

    Optional<TurnRow> findTurn(UUID id);

    /** Sets the owner's turn's {@code stopped_at} (Story 2.6); false for an unknown turn or another owner's. */
    boolean markStopped(UUID turnId, Instant at);

    /** The current owner's turn answered by this job. */
    Optional<TurnRow> turnOfJob(UUID jobId);

    void insertBatch(EventBatchRow row);

    /** The highest batch number of this claim stored for the turn; 0 when none is. */
    int lastBatch(UUID turnId, UUID claimId);

    /** The highest sequence stored for the turn; 0 when none is. */
    long lastSequence(UUID turnId);

    /** Whether the turn has a batch the worker posted with at least one event (a final event and usage alone are not). */
    boolean hasPostedBatch(UUID turnId);

    /** The turn's batches with an event after {@code sequence}, by first sequence. */
    List<EventBatchRow> batchesAfter(UUID turnId, long sequence);

    // --- a system's deletion (Story 3.1 part B)

    /** The current owner's conversations of these campaigns, oldest first, deleting ones included. */
    List<ConversationRow> conversationsOfCampaigns(Collection<UUID> campaignIds);

    /** How many {@code ready} conversations the current owner's campaigns have (Story 3.10 part B). */
    long countConversationsOfCampaigns(Collection<UUID> campaignIds);

    /** The current owner's turns of a conversation, oldest first. */
    List<TurnRow> turnsOf(UUID conversationId);

    /**
     * Deletes the current owner's turn with its event batches (its references must be gone); false when there is no
     * such turn.
     */
    boolean deleteTurn(UUID turnId);

    /**
     * Deletes the current owner's conversation, {@code ready} or {@code deleting}, which must have no turn left; false
     * when there is none.
     */
    boolean deleteConversation(UUID id);
}
