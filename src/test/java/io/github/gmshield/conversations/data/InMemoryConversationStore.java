package io.github.gmshield.conversations.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The conversation tables in memory, for tests that run without PostgreSQL. Like Hibernate's {@code @TenantId}, it sees
 * the rows of the {@link OwnerContext}'s owner only; like the tables, a (claim, batch) is unique. A write undoes itself
 * when its transaction rolls back. A conversation's state (Story 3.10 part B) is kept beside its row: {@link #deleting}
 * holds the ids of the deleting ones, and every read but the deletion's own skips them. A dismissed turn (Story 3.11)
 * is left out of {@link #newestTurns}, {@link #turnCount} and {@link #turnCounts}, as the JPA store leaves it out.
 * The saved list (Story 3.12) takes bookmarked, shown turns of conversations that are not deleting.
 */
public class InMemoryConversationStore implements ConversationStore {

    public final Map<UUID, ConversationRow> conversations = new ConcurrentHashMap<>();
    public final Map<UUID, TurnRow> turns = new ConcurrentHashMap<>();
    public final Map<UUID, EventBatchRow> batches = new ConcurrentHashMap<>();
    /** The conversations whose state is {@code deleting} (Story 3.10 part B). */
    public final Set<UUID> deleting = ConcurrentHashMap.newKeySet();
    /**
     * Story 3.12, for a race test: run once right after the next {@link #savedTurns} read, as another connection's
     * commit landing between that read and the reads that follow it in the same request.
     */
    public final AtomicReference<Runnable> afterSavedTurns = new AtomicReference<>();

    @Override
    public synchronized void insertConversation(ConversationRow row) {
        ownerFor(row.ownerId());
        if (!ConversationRow.QUESTIONS.equals(row.area()) && !ConversationRow.RULES.equals(row.area())
                || conversations.putIfAbsent(row.id(), row) != null) {
            throw new IllegalStateException("key or check refused");
        }
        RollbackUndo.onRollback(() -> conversations.remove(row.id()));
    }

    /** As the table's index: the most recently asked first, then by id, newest first. */
    private static final Comparator<ConversationRow> LISTED = Comparator.comparing(ConversationRow::lastAskedAt)
            .thenComparing(ConversationRow::id).reversed();

    @Override
    public Optional<ConversationRow> latestConversation(UUID campaignId, String area) {
        return listed(campaignId, area).findFirst();
    }

    @Override
    public List<ConversationRow> conversations(UUID campaignId, String area, int offset, int limit) {
        return listed(campaignId, area).skip(offset).limit(limit).toList();
    }

    @Override
    public long conversationCount(UUID campaignId, String area) {
        return listed(campaignId, area).count();
    }

    private Stream<ConversationRow> listed(UUID campaignId, String area) {
        return owned(conversations).filter(row -> row.campaignId().equals(campaignId) && row.area().equals(area)
                && !deleting.contains(row.id())).sorted(LISTED);
    }

    @Override
    public Map<UUID, TurnRow> firstTurns(Collection<UUID> conversationIds) {
        Map<UUID, TurnRow> first = new HashMap<>();
        owned(turns).filter(row -> conversationIds.contains(row.conversationId()))
                .sorted(Comparator.comparing(TurnRow::createdAt).thenComparing(TurnRow::id))
                .forEach(row -> first.putIfAbsent(row.conversationId(), row));
        return first;
    }

    @Override
    public Map<UUID, Long> turnCounts(Collection<UUID> conversationIds) {
        Map<UUID, Long> counts = new HashMap<>();
        owned(turns).filter(row -> conversationIds.contains(row.conversationId()) && row.dismissedAt() == null)
                .forEach(row -> counts.merge(row.conversationId(), 1L, Long::sum));
        return counts;
    }

    @Override
    public List<TurnRow> newestTurns(UUID conversationId, int limit) {
        List<TurnRow> all = turnsOf(conversationId).stream().filter(row -> row.dismissedAt() == null).toList();
        return new ArrayList<>(all.subList(Math.max(0, all.size() - limit), all.size()));
    }

    @Override
    public synchronized boolean markAsked(UUID id, Instant at) {
        Optional<ConversationRow> found = findConversation(id);
        found.ifPresent(before -> {
            ownerFor(before.ownerId());
            if (at.isAfter(before.lastAskedAt())) {
                conversations.put(id, before.askedAt(at));
                RollbackUndo.onRollback(() -> conversations.put(id, before));
            }
        });
        return found.isPresent();
    }

    @Override
    public synchronized Optional<ConversationRow> renameConversation(UUID id, long version, byte[] name) {
        Optional<ConversationRow> found = findConversation(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ConversationRow before = found.get();
        ownerFor(before.ownerId());
        if (before.version() != version) {
            throw new ConversationConflict();
        }
        ConversationRow renamed = before.renamed(name);
        conversations.put(id, renamed);
        RollbackUndo.onRollback(() -> conversations.put(id, before));
        return Optional.of(renamed);
    }

    @Override
    public Optional<ConversationRow> findConversation(UUID id) {
        return findConversationAnyState(id).filter(row -> !deleting.contains(row.id()));
    }

    @Override
    public Optional<ConversationRow> findConversationAnyState(UUID id) {
        return owned(conversations).filter(row -> row.id().equals(id)).findFirst();
    }

    @Override
    public synchronized boolean markDeleting(UUID id) {
        Optional<ConversationRow> found = findConversationAnyState(id);
        found.ifPresent(conversation -> {
            ownerFor(conversation.ownerId());
            if (deleting.add(id)) {
                RollbackUndo.onRollback(() -> deleting.remove(id));
            }
        });
        return found.isPresent();
    }

    @Override
    public long turnCount(UUID conversationId) {
        return owned(turns).filter(row -> row.conversationId().equals(conversationId) && row.dismissedAt() == null)
                .count();
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
    public synchronized boolean markDismissed(UUID turnId, Instant at) {
        Optional<TurnRow> found = findTurn(turnId);
        if (found.isEmpty() || found.get().dismissedAt() != null) {
            return false;
        }
        TurnRow before = found.get();
        ownerFor(before.ownerId());
        turns.put(turnId, before.dismissed(at));
        RollbackUndo.onRollback(() -> turns.put(turnId, before));
        return true;
    }

    @Override
    public synchronized boolean clearDismissed(UUID turnId) {
        Optional<TurnRow> found = findTurn(turnId);
        if (found.isEmpty() || found.get().dismissedAt() == null) {
            return false;
        }
        TurnRow before = found.get();
        ownerFor(before.ownerId());
        turns.put(turnId, before.dismissed(null));
        RollbackUndo.onRollback(() -> turns.put(turnId, before));
        return true;
    }

    @Override
    public synchronized boolean markBookmarked(UUID turnId, Instant at) {
        Optional<TurnRow> found = findTurn(turnId);
        if (found.isEmpty() || found.get().bookmarkedAt() != null) {
            return false;
        }
        TurnRow before = found.get();
        ownerFor(before.ownerId());
        turns.put(turnId, before.bookmarked(at));
        RollbackUndo.onRollback(() -> turns.put(turnId, before));
        return true;
    }

    @Override
    public synchronized boolean clearBookmarked(UUID turnId) {
        Optional<TurnRow> found = findTurn(turnId);
        if (found.isEmpty() || found.get().bookmarkedAt() == null) {
            return false;
        }
        TurnRow before = found.get();
        ownerFor(before.ownerId());
        turns.put(turnId, before.bookmarked(null));
        RollbackUndo.onRollback(() -> turns.put(turnId, before));
        return true;
    }

    /** As the JPA store's saved list: newest saved first, then by id, newest first. */
    private static final Comparator<TurnRow> SAVED = Comparator.comparing(TurnRow::bookmarkedAt)
            .thenComparing(TurnRow::id).reversed();

    @Override
    public Set<UUID> savedCampaigns() {
        return saved().map(row -> conversations.get(row.conversationId()).campaignId()).collect(Collectors.toSet());
    }

    @Override
    public List<TurnRow> savedTurns(Collection<UUID> campaignIds, int offset, int limit) {
        List<TurnRow> page = saved(campaignIds).sorted(SAVED).skip(offset).limit(limit).toList();
        Runnable meanwhile = afterSavedTurns.getAndSet(null);
        if (meanwhile != null) {
            meanwhile.run();
        }
        return page;
    }

    @Override
    public long savedCount(Collection<UUID> campaignIds) {
        return saved(campaignIds).count();
    }

    private Stream<TurnRow> saved() {
        return owned(turns).filter(row -> row.bookmarkedAt() != null && row.dismissedAt() == null
                && findConversation(row.conversationId()).isPresent());
    }

    private Stream<TurnRow> saved(Collection<UUID> campaignIds) {
        return saved().filter(row -> campaignIds.contains(conversations.get(row.conversationId()).campaignId()));
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

    // --- a system's deletion (Story 3.1 part B)

    @Override
    public List<ConversationRow> conversationsOfCampaigns(Collection<UUID> campaignIds) {
        return owned(conversations).filter(row -> campaignIds.contains(row.campaignId()))
                .sorted(Comparator.comparing(ConversationRow::createdAt).thenComparing(ConversationRow::id)).toList();
    }

    @Override
    public long countConversationsOfCampaigns(Collection<UUID> campaignIds) {
        return owned(conversations).filter(row -> campaignIds.contains(row.campaignId())
                && !deleting.contains(row.id())).count();
    }

    @Override
    public List<TurnRow> turnsOf(UUID conversationId) {
        return owned(turns).filter(row -> row.conversationId().equals(conversationId))
                .sorted(Comparator.comparing(TurnRow::createdAt).thenComparing(TurnRow::id)).toList();
    }

    @Override
    public synchronized boolean deleteTurn(UUID turnId) {
        Optional<TurnRow> found = findTurn(turnId);
        found.ifPresent(turn -> {
            ownerFor(turn.ownerId());
            for (EventBatchRow batch : owned(batches).filter(row -> row.turnId().equals(turnId)).toList()) {
                batches.remove(batch.id());
                RollbackUndo.onRollback(() -> batches.put(batch.id(), batch));
            }
            turns.remove(turnId);
            RollbackUndo.onRollback(() -> turns.put(turnId, turn));
        });
        return found.isPresent();
    }

    @Override
    public synchronized boolean deleteConversation(UUID id) {
        Optional<ConversationRow> found = findConversationAnyState(id);
        found.ifPresent(conversation -> {
            ownerFor(conversation.ownerId());
            if (turns.values().stream().anyMatch(turn -> turn.conversationId().equals(id))) {
                throw new IllegalStateException("a turn still names the conversation");
            }
            conversations.remove(id);
            boolean wasDeleting = deleting.remove(id);
            RollbackUndo.onRollback(() -> {
                conversations.put(id, conversation);
                if (wasDeleting) {
                    deleting.add(id);
                }
            });
        });
        return found.isPresent();
    }
}
