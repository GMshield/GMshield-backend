package io.github.gmshield.conversations.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Repository;

/**
 * {@code search} and {@code search_result} through JPA, as {@link JpaConversationStore}: every read is JPQL, which
 * Hibernate filters by the owner of the work; nothing is read by {@code find} or written by a bulk statement. The
 * entity manager is looked up on first use.
 */
@Repository
class JpaSearchStore implements SearchStore {

    private static final String BY_ID = "select s from Search s where s.id = :id";

    private final ObjectProvider<EntityManagerFactory> factory;
    private volatile EntityManager shared;

    JpaSearchStore(ObjectProvider<EntityManagerFactory> factory) {
        this.factory = factory;
    }

    @Override
    public void insertSearch(SearchRow row) {
        EntityManager em = em();
        em.persist(new SearchEntity(row));
        em.flush();
    }

    @Override
    public Optional<SearchRow> findSearch(UUID id) {
        return entity(id).map(SearchEntity::row);
    }

    @Override
    public Optional<SearchRow> searchOfJob(UUID jobId) {
        return em().createQuery("select s from Search s where s.jobId = :job", SearchEntity.class)
                .setParameter("job", jobId).getResultList().stream().findFirst().map(SearchEntity::row);
    }

    @Override
    public boolean endSearch(UUID id, String state, String code) {
        Optional<SearchEntity> found = entity(id);
        found.ifPresent(entity -> {
            entity.end(state, code);
            em().flush();
        });
        return found.isPresent();
    }

    @Override
    public void insertResults(List<SearchResultRow> rows) {
        EntityManager em = em();
        rows.forEach(row -> em.persist(new SearchResultEntity(row)));
        em.flush();
    }

    @Override
    public List<SearchResultRow> results(UUID searchId) {
        return em().createQuery("select r from SearchResult r where r.searchId = :search order by r.resultRank",
                SearchResultEntity.class).setParameter("search", searchId).getResultList().stream()
                .map(SearchResultEntity::row).toList();
    }

    @Override
    public List<SearchRow> searchesAfterNewest(int skip) {
        return em().createQuery("select s from Search s order by s.createdAt desc, s.id desc", SearchEntity.class)
                .setFirstResult(skip).getResultList().stream().map(SearchEntity::row).toList();
    }

    @Override
    public List<SearchRow> searchesOfCampaigns(Collection<UUID> campaignIds) {
        if (campaignIds.isEmpty()) {
            return List.of();
        }
        return em().createQuery("select s from Search s where s.campaignId in :campaigns order by s.createdAt, s.id",
                        SearchEntity.class).setParameter("campaigns", campaignIds).getResultList().stream()
                .map(SearchEntity::row).toList();
    }

    @Override
    public boolean deleteSearch(UUID id) {
        EntityManager em = em();
        Optional<SearchEntity> found = entity(id);
        found.ifPresent(search -> {
            em.createQuery("select r from SearchResult r where r.searchId = :search", SearchResultEntity.class)
                    .setParameter("search", id).getResultList().forEach(em::remove);
            em.flush();
            em.remove(search);
            em.flush();
        });
        return found.isPresent();
    }

    private Optional<SearchEntity> entity(UUID id) {
        return em().createQuery(BY_ID, SearchEntity.class).setParameter("id", id).getResultList().stream()
                .findFirst();
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
