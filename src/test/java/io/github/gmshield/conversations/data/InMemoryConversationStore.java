package io.github.gmshield.conversations.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The conversation tables in memory, for tests that run without PostgreSQL. Like Hibernate's {@code @TenantId}, it sees
 * the rows of the {@link OwnerContext}'s owner only; like the tables, a (claim, batch) is unique. A write undoes itself
 * when its transaction rolls back.
 */
public class InMemoryConversationStore implements ConversationStore {

    public final Map<UUID, ConversationRow> conversations = new ConcurrentHashMap<>();
    public final Map<UUID, TurnRow> turns = new ConcurrentHashMap<>();
    public final Map<UUID, EventBatchRow> batches = new ConcurrentHashMap<>();

    @Override
    public synchronized void insertConversation(ConversationRow row) {
        ownerFor(row.ownerId());
        if (conversations.values().stream().anyMatch(other -> other.ownerId().equals(row.ownerId())
                && other.campaignId().equals(row.campaignId())) || conversations.putIfAbsent(row.id(), row) != null) {
            throw new IllegalStateException("key refused");
        }
        RollbackUndo.onRollback(() -> conversations.remove(row.id()));
    }

    @Override
    public Optional<ConversationRow> conversationOfCampaign(UUID campaignId) {
        return owned(conversations).filter(row -> row.campaignId().equals(campaignId)).findFirst();
    }

    @Override
    public Optional<ConversationRow> findConversation(UUID id) {
        return owned(conversations).filter(row -> row.id().equals(id)).findFirst();
    }

    @Override
    public synchronized void insertTurn(TurnRow row) {
        ownerFor(row.ownerId());
        ConversationRow conversation = conversations.get(row.conversationId());
        if (conversation == null || !conversation.ownerId().equals(row.ownerId())
                || turns.putIfAbsent(row.id(), row) != null) {
            throw new IllegalStateException("key or reference refused");
        }
        RollbackUndo.onRollback(() -> turns.remove(row.id()));
    }

    @Override
    public Optional<TurnRow> findTurn(UUID id) {
        return owned(turns).filter(row -> row.id().equals(id)).findFirst();
    }

    @Override
    public synchronized boolean markStopped(UUID turnId, Instant at) {
        Optional<TurnRow> found = findTurn(turnId);
        if (found.isEmpty()) {
            return false;
        }
        TurnRow before = found.get();
        ownerFor(before.ownerId());
        turns.put(turnId, before.stopped(at));
        RollbackUndo.onRollback(() -> turns.put(turnId, before));
        return true;
    }

    @Override
    public Optional<TurnRow> turnOfJob(UUID jobId) {
        return owned(turns).filter(row -> row.jobId().equals(jobId)).findFirst();
    }

    @Override
    public synchronized void insertBatch(EventBatchRow row) {
        ownerFor(row.ownerId());
        TurnRow turn = turns.get(row.turnId());
        boolean repeated = row.claimId() != null && batches.values().stream().anyMatch(other ->
                row.claimId().equals(other.claimId()) && row.batch().equals(other.batch()));
        if (turn == null || !turn.ownerId().equals(row.ownerId()) || repeated
                || row.lastSequence() < row.firstSequence() - 1 || batches.putIfAbsent(row.id(), row) != null) {
            throw new IllegalStateException("key, reference or check refused");
        }
        RollbackUndo.onRollback(() -> batches.remove(row.id()));
    }

    @Override
    public int lastBatch(UUID turnId, UUID claimId) {
        return owned(batches).filter(row -> row.turnId().equals(turnId) && claimId.equals(row.claimId()))
                .mapToInt(EventBatchRow::batch).max().orElse(0);
    }

    @Override
    public long lastSequence(UUID turnId) {
        return owned(batches).filter(row -> row.turnId().equals(turnId)).mapToLong(EventBatchRow::lastSequence).max()
                .orElse(0);
    }

    @Override
    public boolean hasPostedBatch(UUID turnId) {
        return owned(batches).anyMatch(row -> row.turnId().equals(turnId) && row.claimId() != null
                && row.lastSequence() >= row.firstSequence());
    }

    @Override
    public List<EventBatchRow> batchesAfter(UUID turnId, long sequence) {
        return owned(batches).filter(row -> row.turnId().equals(turnId) && row.lastSequence() > sequence)
                .sorted(Comparator.comparingLong(EventBatchRow::firstSequence)).toList();
    }

    /** Every turn of every owner whose job is this one (tests). */
    public Optional<TurnRow> anyTurnOfJob(UUID jobId) {
        return turns.values().stream().filter(row -> row.jobId().equals(jobId)).findFirst();
    }

    private static <T> Stream<T> owned(Map<UUID, T> rows) {
        UUID owner = OwnerContext.require();
        return rows.values().stream().filter(row -> owner.equals(ownerOf(row)));
    }

    private static UUID ownerOf(Object row) {
        return switch (row) {
            case ConversationRow conversation -> conversation.ownerId();
            case TurnRow turn -> turn.ownerId();
            case EventBatchRow batch -> batch.ownerId();
            default -> throw new IllegalArgumentException("row");
        };
    }

    /** The current owner, who must be the row's; a write outside a transaction is refused, as JPA refuses it. */
    private static void ownerFor(UUID rowOwner) {
        if (!OwnerContext.require().equals(rowOwner)) {
            throw new IllegalStateException("another owner's row");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("no transaction");
        }
    }
}
