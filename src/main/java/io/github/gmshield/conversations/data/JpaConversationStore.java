package io.github.gmshield.conversations.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Repository;

/**
 * The conversation tables through JPA, as the library's stores: every read is JPQL, which Hibernate filters by the
 * owner of the work; nothing is read by {@code find} or written by a bulk statement. The entity manager is looked up
 * on first use.
 */
@Repository
class JpaConversationStore implements ConversationStore {

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
    public Optional<ConversationRow> conversationOfCampaign(UUID campaignId) {
        return em().createQuery("select c from Conversation c where c.campaignId = :campaign order by c.createdAt",
                        ConversationEntity.class).setParameter("campaign", campaignId).setMaxResults(1)
                .getResultList().stream().findFirst().map(ConversationEntity::row);
    }

    @Override
    public Optional<ConversationRow> findConversation(UUID id) {
        return em().createQuery("select c from Conversation c where c.id = :id", ConversationEntity.class)
                .setParameter("id", id).getResultList().stream().findFirst().map(ConversationEntity::row);
    }

    @Override
    public void insertTurn(TurnRow row) {
        EntityManager em = em();
        em.persist(new TurnEntity(row));
        em.flush();
    }

    @Override
    public Optional<TurnRow> findTurn(UUID id) {
        return em().createQuery("select t from Turn t where t.id = :id", TurnEntity.class).setParameter("id", id)
                .getResultList().stream().findFirst().map(TurnEntity::row);
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
    public Optional<TurnRow> turnOfJob(UUID jobId) {
        return em().createQuery("select t from Turn t where t.jobId = :job", TurnEntity.class)
                .setParameter("job", jobId).getResultList().stream().findFirst().map(TurnEntity::row);
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
    public List<TurnRow> turnsOf(UUID conversationId) {
        return em().createQuery("select t from Turn t where t.conversationId = :conversation order by t.createdAt, t.id",
                        TurnEntity.class).setParameter("conversation", conversationId).getResultList().stream()
                .map(TurnEntity::row).toList();
    }

    @Override
    public boolean deleteTurn(UUID turnId) {
        EntityManager em = em();
        Optional<TurnEntity> found = em.createQuery("select t from Turn t where t.id = :id", TurnEntity.class)
                .setParameter("id", turnId).getResultList().stream().findFirst();
        found.ifPresent(turn -> {
            em.createQuery("select b from TurnEventBatch b where b.turnId = :turn", TurnEventBatchEntity.class)
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
        Optional<ConversationEntity> found = em.createQuery("select c from Conversation c where c.id = :id",
                ConversationEntity.class).setParameter("id", id).getResultList().stream().findFirst();
        found.ifPresent(conversation -> {
            em.remove(conversation);
            em.flush();
        });
        return found.isPresent();
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
