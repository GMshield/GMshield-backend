package io.github.gmshield.conversations.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.hibernate.StaleStateException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Repository;

/**
 * The conversation tables through JPA, as the library's stores: every read is JPQL, which Hibernate filters by the
 * owner of the work; nothing is read by {@code find}, and the bulk statements ({@link #markAsked}, and from Story
 * 3.10 part B {@link #markDeleting}) run only after the owner's row was read. The entity manager is looked up on first
 * use.
 *
 * <p>Story 3.10 part B: every read of a conversation takes {@code ready} rows only, but the deletion's own
 * ({@link #markDeleting}, {@link #findConversationAnyState}, {@link #conversationsOfCampaigns},
 * {@link #deleteConversation}) and the answer job's ({@link #findConversationAnyState}).
 *
 * <p>Story 3.11: {@code turn.dismissed_at} is written by bulk updates only ({@link #markDismissed},
 * {@link #clearDismissed}), and {@link #newestTurns}, {@link #turnCount} and {@link #turnCounts} take shown turns
 * only; {@link #firstTurns} (the default name), {@link #findTurn} and {@link #turnsOf} (the deletion) take them all.
 *
 * <p>Story 3.12: {@code turn.bookmarked_at} is written by bulk updates only ({@link #markBookmarked},
 * {@link #clearBookmarked}). The saved list ({@link #savedTurns}, {@link #savedCount}, {@link #savedCampaigns}) joins
 * each saved, shown turn to its {@code ready} conversation on the owner and the conversation's id; deleting a turn (by
 * any route) removes its bookmark with the row.
 *
 * <p>Story 5.3 part B: {@code turn.proposal_name} and {@code turn.proposal_type} are written by one bulk update only
 * ({@link #markProposed}).
 *
 * <p>Story 5.4 part A: {@code turn.saved_record_id} and {@code turn.save_declined_at} are written by bulk updates only
 * ({@link #markSaved}, {@link #markSaveDeclined}, {@link #clearSaveDeclined}), each conditional on the column it guards,
 * one text per case (Sonar S2077 wants no text assembled at run time).
 */
@Repository
class JpaConversationStore implements ConversationStore {

    /** A campaign's conversations of one area, the most recently asked first (Story 3.10). */
    private static final String LIST = "select c from Conversation c where c.campaignId = :campaign and c.area = :area"
            + " and c.state = 'ready' order by c.lastAskedAt desc, c.id desc";

    /** The current owner's listed saved turns (Story 3.12): bookmarked, shown, of a ready conversation. */
    private static final String SAVED = " from Turn t join Conversation c on c.ownerId = t.ownerId"
            + " and c.id = t.conversationId where t.bookmarkedAt is not null and t.dismissedAt is null"
            + " and c.state = 'ready'";
    private static final String SAVED_OF = SAVED + " and c.campaignId in :campaigns";
    private static final String SAVED_CAMPAIGNS = "select distinct c.campaignId" + SAVED;
    private static final String SAVED_TURNS = "select t" + SAVED_OF + " order by t.bookmarkedAt desc, t.id desc";
    private static final String SAVED_COUNT = "select count(t)" + SAVED_OF;

    /** A save of a never-saved turn, and of one whose saved artifact was deleted (Story 5.4 part A). */
    private static final String SAVE_FIRST = "update Turn t set t.savedRecordId = :record, t.saveDeclinedAt = :at"
            + " where t.id = :id and t.savedRecordId is null";
    private static final String SAVE_AGAIN = "update Turn t set t.savedRecordId = :record, t.saveDeclinedAt = :at"
            + " where t.id = :id and t.savedRecordId = :expected";
    /** "Salvar como artefato" (Story 5.4 part A): on a turn never saved, and on one whose saved artifact was deleted. */
    private static final String REOPEN_UNSAVED = "update Turn t set t.saveDeclinedAt = null, t.savedRecordId = null"
            + " where t.id = :id and t.savedRecordId is null and t.saveDeclinedAt is not null";
    private static final String REOPEN_GONE = "update Turn t set t.saveDeclinedAt = null, t.savedRecordId = null"
            + " where t.id = :id and t.savedRecordId = :stale";

    private final ObjectProvider<EntityManagerFactory> factory;
    private volatile EntityManager shared;

    JpaConversationStore(ObjectProvider<EntityManagerFactory> factory) {
        this.factory = factory;
    }

    @Override
    public void insertConversation(ConversationRow row) {
        EntityManager em = em();
        em.persist(new ConversationEntity(row));
        em.flush();
    }

    @Override
    public Optional<ConversationRow> latestConversation(UUID campaignId, String area) {
        return em().createQuery(LIST, ConversationEntity.class).setParameter("campaign", campaignId)
                .setParameter("area", area).setMaxResults(1).getResultList().stream().findFirst()
                .map(ConversationEntity::row);
    }

    @Override
    public List<ConversationRow> conversations(UUID campaignId, String area, int offset, int limit) {
        return em().createQuery(LIST, ConversationEntity.class).setParameter("campaign", campaignId)
                .setParameter("area", area).setFirstResult(offset).setMaxResults(limit).getResultList().stream()
                .map(ConversationEntity::row).toList();
    }

    @Override
    public long conversationCount(UUID campaignId, String area) {
        return em().createQuery("select count(c) from Conversation c where c.campaignId = :campaign"
                        + " and c.area = :area and c.state = 'ready'", Long.class).setParameter("campaign", campaignId)
                .setParameter("area", area).getSingleResult();
    }

    @Override
    public Map<UUID, TurnRow> firstTurns(Collection<UUID> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        List<TurnEntity> found = em().createQuery("select t from Turn t where t.conversationId in :conversations"
                        + " and t.createdAt = (select min(f.createdAt) from Turn f"
                        + " where f.conversationId = t.conversationId)", TurnEntity.class)
                .setParameter("conversations", conversationIds).getResultList();
        Map<UUID, TurnEntity> first = new HashMap<>();
        for (TurnEntity turn : found) {
            first.merge(turn.conversationId(), turn, (one, other) -> one.id().compareTo(other.id()) <= 0 ? one : other);
        }
        Map<UUID, Set<UUID>> off = materialsOff(first.values().stream().map(TurnEntity::id).toList());
        Map<UUID, TurnRow> rows = new HashMap<>();
        first.forEach((conversation, turn) -> rows.put(conversation, turn.row(off.getOrDefault(turn.id(), Set.of()))));
        return rows;
    }

    @Override
    public Map<UUID, Long> turnCounts(Collection<UUID> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> counts = new HashMap<>();
        em().createQuery("select t.conversationId, count(t) from Turn t where t.conversationId in :conversations"
                        + " and t.dismissedAt is null group by t.conversationId", Object[].class).setParameter("conversations", conversationIds)
                .getResultList().forEach(row -> counts.put((UUID) row[0], (Long) row[1]));
        return counts;
    }

    @Override
    public List<TurnRow> newestTurns(UUID conversationId, int limit) {
        List<TurnEntity> newest = new ArrayList<>(em().createQuery("select t from Turn t where t.conversationId"
                        + " = :conversation and t.dismissedAt is null order by t.createdAt desc, t.id desc",
                        TurnEntity.class)
                .setParameter("conversation", conversationId).setMaxResults(limit).getResultList());
        newest.sort(Comparator.comparing(TurnEntity::createdAt).thenComparing(TurnEntity::id));
        Map<UUID, Set<UUID>> off = materialsOff(newest.stream().map(TurnEntity::id).toList());
        return newest.stream().map(turn -> turn.row(off.getOrDefault(turn.id(), Set.of()))).toList();
    }

    @Override
    public boolean markAsked(UUID id, Instant at) {
        EntityManager em = em();
        if (conversation(id).isEmpty()) {
            return false;
        }
        // One bulk update: it never moves the time back, never touches the version, and never fails on a rename
        // committed meanwhile (it writes no versioned entity).
        em.createQuery("update Conversation c set c.lastAskedAt = :at where c.id = :id and c.lastAskedAt < :at")
                .setParameter("at", at).setParameter("id", id).executeUpdate();
        return true;
    }

    @Override
    public Optional<ConversationRow> renameConversation(UUID id, long version, byte[] name) {
        Optional<ConversationEntity> found = conversation(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ConversationEntity entity = found.get();
        if (entity.version() != version) {
            throw new ConversationConflict();
        }
        entity.rename(name);
        try {
            em().flush();
        } catch (OptimisticLockException | StaleStateException moved) {
            throw new ConversationConflict();
        }
        return Optional.of(entity.row());
    }

    @Override
    public Optional<ConversationRow> findConversation(UUID id) {
        return conversation(id).map(ConversationEntity::row);
    }

    @Override
    public Optional<ConversationRow> findConversationAnyState(UUID id) {
        return anyState(id).map(ConversationEntity::row);
    }

    @Override
    public boolean markDeleting(UUID id) {
        if (anyState(id).isEmpty()) {
            return false;
        }
        // One bulk update, after the owner's row was read, as markAsked: it never raises the version, so it never
        // fails on a rename committed meanwhile.
        em().createQuery("update Conversation c set c.state = 'deleting' where c.id = :id").setParameter("id", id)
                .executeUpdate();
        return true;
    }

    /** The owner's {@code ready} conversation with this id. */
    private Optional<ConversationEntity> conversation(UUID id) {
        return em().createQuery("select c from Conversation c where c.id = :id and c.state = 'ready'",
                        ConversationEntity.class).setParameter("id", id).getResultList().stream().findFirst();
    }

    /** The owner's conversation with this id, {@code ready} or {@code deleting}. */
    private Optional<ConversationEntity> anyState(UUID id) {
        return em().createQuery("select c from Conversation c where c.id = :id", ConversationEntity.class)
                .setParameter("id", id).getResultList().stream().findFirst();
    }

    @Override
    public long turnCount(UUID conversationId) {
        return em().createQuery("select count(t) from Turn t where t.conversationId = :conversation"
                        + " and t.dismissedAt is null", Long.class)
                .setParameter("conversation", conversationId).getSingleResult();
    }

    @Override
    public void insertTurn(TurnRow row) {
        EntityManager em = em();
        em.persist(new TurnEntity(row));
        em.flush();
        for (UUID materialId : row.materialsOff()) {
            em.persist(new TurnMaterialOffEntity(row.id(), materialId, row.ownerId()));
        }
        em.flush();
    }

    @Override
    public Optional<TurnRow> findTurn(UUID id) {
        return em().createQuery("select t from Turn t where t.id = :id", TurnEntity.class).setParameter("id", id)
                .getResultList().stream().findFirst().map(this::row);
    }

    @Override
    public boolean markStopped(UUID turnId, Instant at) {
        EntityManager em = em();
        Optional<TurnEntity> found = em.createQuery("select t from Turn t where t.id = :id", TurnEntity.class)
                .setParameter("id", turnId).getResultList().stream().findFirst();
        found.ifPresent(entity -> {
            entity.stop(at);
            em.flush();
        });
        return found.isPresent();
    }

    @Override
    public boolean markDismissed(UUID turnId, Instant at) {
        if (findTurn(turnId).isEmpty()) {
            return false;
        }
        // One bulk update, after the owner's row was read: a stop's flush never writes the column (it is not
        // updatable), so neither undoes the other; a dismissal already recorded keeps its time.
        return em().createQuery("update Turn t set t.dismissedAt = :at where t.id = :id and t.dismissedAt is null")
                .setParameter("at", at).setParameter("id", turnId).executeUpdate() > 0;
    }

    @Override
    public boolean clearDismissed(UUID turnId) {
        if (findTurn(turnId).isEmpty()) {
            return false;
        }
        return em().createQuery("update Turn t set t.dismissedAt = null where t.id = :id and t.dismissedAt is not null")
                .setParameter("id", turnId).executeUpdate() > 0;
    }

    @Override
    public boolean markBookmarked(UUID turnId, Instant at) {
        if (findTurn(turnId).isEmpty()) {
            return false;
        }
        // As markDismissed: one bulk update after the owner's row was read; a bookmark already recorded keeps its time.
        return em().createQuery("update Turn t set t.bookmarkedAt = :at where t.id = :id and t.bookmarkedAt is null")
                .setParameter("at", at).setParameter("id", turnId).executeUpdate() > 0;
    }

    @Override
    public boolean clearBookmarked(UUID turnId) {
        if (findTurn(turnId).isEmpty()) {
            return false;
        }
        return em().createQuery("update Turn t set t.bookmarkedAt = null where t.id = :id"
                + " and t.bookmarkedAt is not null").setParameter("id", turnId).executeUpdate() > 0;
    }

    @Override
    public boolean markProposed(UUID turnId, byte[] name, String type) {
        if (findTurn(turnId).isEmpty()) {
            return false;
        }
        // As markBookmarked: one bulk update after the owner's row was read (Story 5.3 part B).
        return em().createQuery("update Turn t set t.proposalName = :name, t.proposalType = :type where t.id = :id")
                .setParameter("name", name).setParameter("type", type).setParameter("id", turnId).executeUpdate() > 0;
    }

    @Override
    public boolean markSaved(UUID turnId, UUID expected, UUID recordId, Instant at) {
        if (findTurn(turnId).isEmpty()) {
            return false;
        }
        // As markBookmarked: one bulk update after the owner's row was read (Story 5.4 part A). A second save waits on
        // the first's row lock, then finds the column set and changes nothing.
        Query update = em().createQuery(expected == null ? SAVE_FIRST : SAVE_AGAIN)
                .setParameter("record", recordId).setParameter("at", at).setParameter("id", turnId);
        if (expected != null) {
            update.setParameter("expected", expected);
        }
        return update.executeUpdate() > 0;
    }

    @Override
    public boolean markSaveDeclined(UUID turnId, Instant at) {
        if (findTurn(turnId).isEmpty()) {
            return false;
        }
        return em().createQuery("update Turn t set t.saveDeclinedAt = :at where t.id = :id"
                + " and t.saveDeclinedAt is null").setParameter("at", at).setParameter("id", turnId)
                .executeUpdate() > 0;
    }

    @Override
    public boolean clearSaveDeclined(UUID turnId, UUID stale) {
        if (findTurn(turnId).isEmpty()) {
            return false;
        }
        Query update = em().createQuery(stale == null ? REOPEN_UNSAVED : REOPEN_GONE)
                .setParameter("id", turnId);
        if (stale != null) {
            update.setParameter("stale", stale);
        }
        return update.executeUpdate() > 0;
    }

    @Override
    public Set<UUID> savedCampaigns() {
        return Set.copyOf(em().createQuery(SAVED_CAMPAIGNS, UUID.class).getResultList());
    }

    @Override
    public List<TurnRow> savedTurns(Collection<UUID> campaignIds, int offset, int limit) {
        if (campaignIds.isEmpty()) {
            return List.of();
        }
        List<TurnEntity> saved = em().createQuery(SAVED_TURNS, TurnEntity.class)
                .setParameter("campaigns", campaignIds).setFirstResult(offset).setMaxResults(limit).getResultList();
        Map<UUID, Set<UUID>> off = materialsOff(saved.stream().map(TurnEntity::id).toList());
        return saved.stream().map(turn -> turn.row(off.getOrDefault(turn.id(), Set.of()))).toList();
    }

    @Override
    public long savedCount(Collection<UUID> campaignIds) {
        if (campaignIds.isEmpty()) {
            return 0;
        }
        return em().createQuery(SAVED_COUNT, Long.class).setParameter("campaigns", campaignIds)
                .getSingleResult();
    }

    @Override
    public Optional<TurnRow> turnOfJob(UUID jobId) {
        return em().createQuery("select t from Turn t where t.jobId = :job", TurnEntity.class)
                .setParameter("job", jobId).getResultList().stream().findFirst().map(this::row);
    }

    @Override
    public void insertBatch(EventBatchRow row) {
        EntityManager em = em();
        em.persist(new TurnEventBatchEntity(row));
        em.flush();
    }

    @Override
    public int lastBatch(UUID turnId, UUID claimId) {
        Integer last = em().createQuery("select max(b.batch) from TurnEventBatch b where b.turnId = :turn"
                        + " and b.claimId = :claim", Integer.class).setParameter("turn", turnId)
                .setParameter("claim", claimId).getSingleResult();
        return last == null ? 0 : last;
    }

    @Override
    public long lastSequence(UUID turnId) {
        Long last = em().createQuery("select max(b.lastSequence) from TurnEventBatch b where b.turnId = :turn",
                Long.class).setParameter("turn", turnId).getSingleResult();
        return last == null ? 0 : last;
    }

    @Override
    public boolean hasPostedBatch(UUID turnId) {
        return !em().createQuery("select b.id from TurnEventBatch b where b.turnId = :turn and b.claimId is not null"
                + " and b.lastSequence >= b.firstSequence",
                UUID.class).setParameter("turn", turnId).setMaxResults(1).getResultList().isEmpty();
    }

    @Override
    public List<EventBatchRow> batchesAfter(UUID turnId, long sequence) {
        return em().createQuery("select b from TurnEventBatch b where b.turnId = :turn and b.lastSequence > :after"
                        + " order by b.firstSequence", TurnEventBatchEntity.class).setParameter("turn", turnId)
                .setParameter("after", sequence).getResultList().stream().map(TurnEventBatchEntity::row).toList();
    }

    @Override
    public List<ConversationRow> conversationsOfCampaigns(Collection<UUID> campaignIds) {
        if (campaignIds.isEmpty()) {
            return List.of();
        }
        return em().createQuery("select c from Conversation c where c.campaignId in :campaigns order by c.createdAt, c.id",
                        ConversationEntity.class).setParameter("campaigns", campaignIds).getResultList().stream()
                .map(ConversationEntity::row).toList();
    }

    @Override
    public long countConversationsOfCampaigns(Collection<UUID> campaignIds) {
        if (campaignIds.isEmpty()) {
            return 0;
        }
        return em().createQuery("select count(c) from Conversation c where c.campaignId in :campaigns"
                + " and c.state = 'ready'", Long.class).setParameter("campaigns", campaignIds).getSingleResult();
    }

    @Override
    public List<TurnRow> turnsOf(UUID conversationId) {
        List<TurnEntity> turns = em().createQuery("select t from Turn t where t.conversationId = :conversation"
                        + " order by t.createdAt, t.id", TurnEntity.class).setParameter("conversation", conversationId)
                .getResultList();
        Map<UUID, Set<UUID>> off = materialsOff(turns.stream().map(TurnEntity::id).toList());
        return turns.stream().map(turn -> turn.row(off.getOrDefault(turn.id(), Set.of()))).toList();
    }

    @Override
    public boolean deleteTurn(UUID turnId) {
        EntityManager em = em();
        Optional<TurnEntity> found = em.createQuery("select t from Turn t where t.id = :id", TurnEntity.class)
                .setParameter("id", turnId).getResultList().stream().findFirst();
        found.ifPresent(turn -> {
            em.createQuery("select b from TurnEventBatch b where b.turnId = :turn", TurnEventBatchEntity.class)
                    .setParameter("turn", turnId).getResultList().forEach(em::remove);
            em.createQuery("select o from TurnMaterialOff o where o.key.turnId = :turn", TurnMaterialOffEntity.class)
                    .setParameter("turn", turnId).getResultList().forEach(em::remove);
            em.flush();
            em.remove(turn);
            em.flush();
        });
        return found.isPresent();
    }

    @Override
    public boolean deleteConversation(UUID id) {
        EntityManager em = em();
        Optional<ConversationEntity> found = anyState(id);
        found.ifPresent(conversation -> {
            em.remove(conversation);
            em.flush();
        });
        return found.isPresent();
    }

    /** The turn's row, with the materials switched off frozen with it. */
    private TurnRow row(TurnEntity turn) {
        return turn.row(materialsOff(List.of(turn.id())).getOrDefault(turn.id(), Set.of()));
    }

    /** The materials switched off frozen with each of these turns, by turn (a turn with none is absent). */
    private Map<UUID, Set<UUID>> materialsOff(Collection<UUID> turnIds) {
        if (turnIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<UUID>> off = new HashMap<>();
        em().createQuery("select o from TurnMaterialOff o where o.key.turnId in :turns", TurnMaterialOffEntity.class)
                .setParameter("turns", turnIds).getResultList()
                .forEach(row -> off.computeIfAbsent(row.turnId(), key -> new HashSet<>()).add(row.materialId()));
        return off;
    }

    /** The shared, transaction-bound entity manager: each transaction (or each call outside one) has its own session. */
    private EntityManager em() {
        EntityManager current = shared;
        if (current == null) {
            synchronized (this) {
                current = shared;
                if (current == null) {
                    current = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
                    shared = current;
                }
            }
        }
        return current;
    }
}
