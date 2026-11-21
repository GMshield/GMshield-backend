package io.github.gmshield.materials.data;

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
 * The index tables through JPA, as {@link JpaMaterialStore}: every read is JPQL, which Hibernate filters by the owner
 * of the work; a change or a removal loads the entity that way first, never by {@code find} and never by a bulk
 * statement. The entity manager is looked up on first use.
 */
@Repository
class JpaIndexStore implements IndexStore {

    private final ObjectProvider<EntityManagerFactory> factory;
    private volatile EntityManager shared;

    JpaIndexStore(ObjectProvider<EntityManagerFactory> factory) {
        this.factory = factory;
    }

    @Override
    public void insertChange(IndexChangeRow row) {
        EntityManager em = em();
        em.persist(new IndexChangeEntity(row));
        em.flush();
    }

    @Override
    public Optional<IndexChangeRow> findChange(UUID jobId) {
        return change(jobId).map(IndexChangeEntity::row);
    }

    @Override
    public List<IndexChangeRow> changes(UUID versionId) {
        return changeEntities(versionId).stream().map(IndexChangeEntity::row).toList();
    }

    @Override
    public boolean recordChangeRecipe(UUID jobId, String recipe) {
        Optional<IndexChangeEntity> found = change(jobId);
        found.ifPresent(entity -> {
            entity.recordRecipe(recipe);
            em().flush();
        });
        return found.isPresent();
    }

    @Override
    public List<IndexChangeRow> retryable(Collection<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return List.of();
        }
        return em().createQuery("select c from IndexChange c where c.jobId in :ids and c.retries < :most"
                        + " order by c.versionId, c.firstOrdinal", IndexChangeEntity.class)
                .setParameter("ids", List.copyOf(jobIds)).setParameter("most", (short) IndexChangeRow.MAX_RETRIES)
                .getResultList().stream().map(IndexChangeEntity::row).toList();
    }

    @Override
    public Optional<IndexChangeRow> moveChange(UUID jobId, UUID newJobId) {
        Optional<IndexChangeEntity> found = change(jobId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        IndexChangeRow before = found.get().row();
        IndexChangeRow moved = new IndexChangeRow(newJobId, before.ownerId(), before.versionId(), before.firstOrdinal(),
                before.lastOrdinal(), null, before.retries() + 1);
        EntityManager em = em();
        em.remove(found.get());
        em.flush();
        em.persist(new IndexChangeEntity(moved));
        em.flush();
        return Optional.of(moved);
    }

    @Override
    public int deleteChanges(UUID versionId) {
        List<IndexChangeEntity> found = changeEntities(versionId);
        found.forEach(em()::remove);
        em().flush();
        return found.size();
    }

    @Override
    public boolean hasRemoval(UUID versionId) {
        return !em().createQuery("select r.jobId from IndexRemoval r where r.versionId = :version", UUID.class)
                .setParameter("version", versionId).setMaxResults(1).getResultList().isEmpty();
    }

    @Override
    public void insertRemoval(IndexRemovalRow row) {
        EntityManager em = em();
        em.persist(new IndexRemovalEntity(row));
        em.flush();
    }

    @Override
    public Optional<IndexRemovalRow> findRemoval(UUID jobId) {
        return em().createQuery("select r from IndexRemoval r where r.jobId = :job", IndexRemovalEntity.class)
                .setParameter("job", jobId).getResultList().stream().findFirst().map(IndexRemovalEntity::row);
    }

    @Override
    public List<IndexRemovalRow> removals(Collection<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return List.of();
        }
        return em().createQuery("select r from IndexRemoval r where r.jobId in :ids order by r.jobId",
                        IndexRemovalEntity.class)
                .setParameter("ids", List.copyOf(jobIds)).getResultList().stream().map(IndexRemovalEntity::row).toList();
    }

    @Override
    public Optional<IndexRemovalRow> moveRemoval(UUID jobId, UUID newJobId) {
        Optional<IndexRemovalEntity> found = em().createQuery("select r from IndexRemoval r where r.jobId = :job",
                IndexRemovalEntity.class).setParameter("job", jobId).getResultList().stream().findFirst();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        IndexRemovalRow before = found.get().row();
        IndexRemovalRow moved = new IndexRemovalRow(newJobId, before.ownerId(), before.versionId());
        EntityManager em = em();
        em.remove(found.get());
        em.flush();
        em.persist(new IndexRemovalEntity(moved));
        em.flush();
        return Optional.of(moved);
    }

    private Optional<IndexChangeEntity> change(UUID jobId) {
        return em().createQuery("select c from IndexChange c where c.jobId = :job", IndexChangeEntity.class)
                .setParameter("job", jobId).getResultList().stream().findFirst();
    }

    private List<IndexChangeEntity> changeEntities(UUID versionId) {
        return em().createQuery("select c from IndexChange c where c.versionId = :version"
                + " order by c.firstOrdinal", IndexChangeEntity.class).setParameter("version", versionId)
                .getResultList();
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
