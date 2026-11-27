package io.github.gmshield.knowledge.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Repository;

/**
 * {@code record_relation} through JPA (Story 5.5 part A), as the library's stores: every read is JPQL, which Hibernate
 * filters by the owner of the work ({@code @TenantId}), and every write goes through an entity. The entity manager is
 * looked up on first use, so the unit tests, which start without a database, can still create the application.
 */
@Repository
class JpaRecordRelationStore implements RecordRelationStore {

    private static final String FROM = "select l from RecordRelation l where l.fromRecordId = :from and l.kind = :kind";
    private static final String FROM_ALL = "select l from RecordRelation l where l.fromRecordId in :from"
            + " and l.kind = :kind";
    private static final String TO = "select l from RecordRelation l where l.toRecordId in :to and l.kind = :kind";
    private static final String BY_ID = "select l from RecordRelation l where l.id = :id";
    private static final String RECORDS = "select r from Record r where r.id in :ids order by r.id";

    private final ObjectProvider<EntityManagerFactory> factory;
    private volatile EntityManager shared;

    JpaRecordRelationStore(ObjectProvider<EntityManagerFactory> factory) {
        this.factory = factory;
    }

    @Override
    public void insert(RecordRelationRow row) {
        EntityManager em = em();
        em.persist(new RecordRelationEntity(row));
        flushChecked(em);
    }

    @Override
    public Optional<RecordRelationRow> from(UUID fromRecordId, String kind) {
        return em().createQuery(FROM, RecordRelationEntity.class).setParameter("from", fromRecordId)
                .setParameter("kind", kind).getResultList().stream().findFirst().map(RecordRelationEntity::row);
    }

    @Override
    public List<RecordRelationRow> fromAll(Collection<UUID> fromRecordIds, String kind) {
        if (fromRecordIds.isEmpty()) {
            return List.of();
        }
        return em().createQuery(FROM_ALL, RecordRelationEntity.class).setParameter("from", List.copyOf(fromRecordIds))
                .setParameter("kind", kind).getResultList().stream().map(RecordRelationEntity::row).toList();
    }

    @Override
    public List<RecordRelationRow> to(Collection<UUID> toRecordIds, String kind) {
        if (toRecordIds.isEmpty()) {
            return List.of();
        }
        return em().createQuery(TO, RecordRelationEntity.class).setParameter("to", List.copyOf(toRecordIds))
                .setParameter("kind", kind).getResultList().stream().map(RecordRelationEntity::row).toList();
    }

    @Override
    public boolean retarget(UUID id, UUID toRecordId) {
        EntityManager em = em();
        Optional<RecordRelationEntity> found = em.createQuery(BY_ID, RecordRelationEntity.class).setParameter("id", id)
                .getResultList().stream().findFirst();
        found.ifPresent(entity -> {
            entity.retarget(toRecordId);
            flushChecked(em);
        });
        return found.isPresent();
    }

    @Override
    public boolean deleteFrom(UUID fromRecordId, String kind) {
        EntityManager em = em();
        List<RecordRelationEntity> found = em.createQuery(FROM, RecordRelationEntity.class)
                .setParameter("from", fromRecordId).setParameter("kind", kind).getResultList();
        found.forEach(em::remove);
        em.flush();
        return !found.isEmpty();
    }

    @Override
    public void lockRecords(Collection<UUID> recordIds) {
        if (recordIds.isEmpty()) {
            return;
        }
        try {
            em().createQuery(RECORDS, RecordEntity.class).setParameter("ids", List.copyOf(recordIds))
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        } catch (RuntimeException refused) {
            throw JpaKnowledgeStore.conflictOf(refused).orElseThrow(() -> refused);
        }
    }

    private static void flushChecked(EntityManager em) {
        try {
            em.flush();
        } catch (RuntimeException refused) {
            throw JpaKnowledgeStore.conflictOf(refused).orElseThrow(() -> refused);
        }
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
