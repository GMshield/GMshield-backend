package io.github.gmshield.library.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Repository;

/**
 * The library tables through JPA. Every read is JPQL, which Hibernate filters by the owner of the work
 * ({@code @TenantId}); a read by id is JPQL too, never {@code find}. The entity manager is looked up on first
 * use, so the unit tests, which start without a database, can still create the application.
 */
@Repository
class JpaLibraryStore implements LibraryStore {

    private final ObjectProvider<EntityManagerFactory> factory;
    private volatile EntityManager shared;

    JpaLibraryStore(ObjectProvider<EntityManagerFactory> factory) {
        this.factory = factory;
    }

    @Override
    public void insertSystem(SystemRow row) {
        EntityManager em = em();
        em.persist(new SystemEntity(row));
        em.flush();
    }

    @Override
    public void insertCampaign(CampaignRow row) {
        EntityManager em = em();
        em.persist(new CampaignEntity(row));
        em.flush();
    }

    @Override
    public Optional<SystemRow> findSystem(UUID id) {
        return em().createQuery("select s from LibrarySystem s where s.id = :id", SystemEntity.class)
                .setParameter("id", id)
                .getResultList().stream().findFirst().map(SystemEntity::row);
    }

    @Override
    public Optional<CampaignRow> findCampaign(UUID id) {
        return em().createQuery("select c from LibraryCampaign c where c.id = :id", CampaignEntity.class)
                .setParameter("id", id)
                .getResultList().stream().findFirst().map(CampaignEntity::row);
    }

    @Override
    public Optional<CampaignRow> firstCampaign() {
        return em().createQuery("select c from LibraryCampaign c order by c.createdAt, c.id", CampaignEntity.class)
                .setMaxResults(1)
                .getResultList().stream().findFirst().map(CampaignEntity::row);
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
