package io.github.gmshield.conversations.data;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The conversation tables, always within the owner of the work: every read sees that owner's rows only, and a write
 * for another owner is refused. Writes join the caller's transaction, which must exist.
 */
public interface ConversationStore {

    void insertConversation(ConversationRow row);

    /** The current owner's conversation of a campaign. */
    Optional<ConversationRow> conversationOfCampaign(UUID campaignId);

    Optional<ConversationRow> findConversation(UUID id);

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
}
