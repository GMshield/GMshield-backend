package io.github.gmshield.conversations.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Repository;

/**
 * {@code turn_reference} through JPA, as {@link JpaConversationStore}: every read is JPQL, which Hibernate filters by
 * the owner of the work. The entity manager is looked up on first use.
 */
@Repository
class JpaTurnReferenceStore implements TurnReferenceStore {

    private final ObjectProvider<EntityManagerFactory> factory;
    private volatile EntityManager shared;

    JpaTurnReferenceStore(ObjectProvider<EntityManagerFactory> factory) {
        this.factory = factory;
    }

    @Override
    public void insertReferences(List<TurnReferenceRow> rows) {
        EntityManager em = em();
        rows.forEach(row -> em.persist(new TurnReferenceEntity(row)));
        em.flush();
    }

    @Override
    public List<TurnReferenceRow> references(UUID turnId) {
        return em().createQuery("select r from TurnReference r where r.turnId = :turn order by r.number",
                TurnReferenceEntity.class).setParameter("turn", turnId).getResultList().stream()
                .map(TurnReferenceEntity::row).toList();
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
