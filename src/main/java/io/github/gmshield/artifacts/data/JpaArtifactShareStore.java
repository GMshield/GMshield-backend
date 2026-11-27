package io.github.gmshield.artifacts.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Repository;

/**
 * {@code artifact_share} through JPA (Story 5.6 part A), as {@link JpaArtifactReferenceStore}: every read is JPQL,
 * which Hibernate filters by the owner of the work ({@code @TenantId}), and a delete loads the rows and removes them.
 * The entity manager is looked up on first use, so the unit tests, which start without a database, can still create
 * the application.
 */
@Repository
class JpaArtifactShareStore implements ArtifactShareStore {

    private static final String OF = "select s from ArtifactShare s where s.artifactId = :artifact"
            + " order by s.createdAt, s.campaignId";
    private static final String INTO = "select s from ArtifactShare s where s.campaignId = :campaign"
            + " order by s.createdAt, s.artifactId";
    private static final String ONE = "select s from ArtifactShare s where s.artifactId = :artifact"
            + " and s.campaignId = :campaign";
    private static final String ALL_OF = "select s from ArtifactShare s where s.artifactId in :artifacts";
    private static final String ALL_INTO = "select s from ArtifactShare s where s.campaignId in :campaigns";

    private final ObjectProvider<EntityManagerFactory> factory;
    private volatile EntityManager shared;

    JpaArtifactShareStore(ObjectProvider<EntityManagerFactory> factory) {
        this.factory = factory;
    }

    @Override
    public void insert(ArtifactShareRow row) {
        EntityManager em = em();
        em.persist(new ArtifactShareEntity(row));
        em.flush();
    }

    @Override
    public List<ArtifactShareRow> of(UUID artifactId) {
        return em().createQuery(OF, ArtifactShareEntity.class).setParameter("artifact", artifactId).getResultList()
                .stream().map(ArtifactShareEntity::row).toList();
    }

    @Override
    public List<ArtifactShareRow> into(UUID campaignId) {
        return em().createQuery(INTO, ArtifactShareEntity.class).setParameter("campaign", campaignId).getResultList()
                .stream().map(ArtifactShareEntity::row).toList();
    }

    @Override
    public List<ArtifactShareRow> ofAll(Collection<UUID> artifactIds) {
        if (artifactIds.isEmpty()) {
            return List.of();
        }
        return em().createQuery(ALL_OF, ArtifactShareEntity.class).setParameter("artifacts", List.copyOf(artifactIds))
                .getResultList().stream().map(ArtifactShareEntity::row).toList();
    }

    @Override
    public boolean delete(UUID artifactId, UUID campaignId) {
        EntityManager em = em();
        List<ArtifactShareEntity> found = em.createQuery(ONE, ArtifactShareEntity.class)
                .setParameter("artifact", artifactId).setParameter("campaign", campaignId).getResultList();
        found.forEach(em::remove);
        em.flush();
        return !found.isEmpty();
    }

    @Override
    public int deleteOf(Collection<UUID> artifactIds) {
        if (artifactIds.isEmpty()) {
            return 0;
        }
        EntityManager em = em();
        List<ArtifactShareEntity> found = em.createQuery(ALL_OF, ArtifactShareEntity.class)
                .setParameter("artifacts", List.copyOf(artifactIds)).getResultList();
        found.forEach(em::remove);
        em.flush();
        return found.size();
    }

    @Override
    public int deleteInto(Collection<UUID> campaignIds) {
        if (campaignIds.isEmpty()) {
            return 0;
        }
        EntityManager em = em();
        List<ArtifactShareEntity> found = em.createQuery(ALL_INTO, ArtifactShareEntity.class)
                .setParameter("campaigns", List.copyOf(campaignIds)).getResultList();
        found.forEach(em::remove);
        em.flush();
        return found.size();
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
