package io.github.gmshield.artifacts.data;

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
 * {@code artifact_index} and {@code artifact_index_removal} through JPA (Story 5.1 part B), as the materials' index
 * store: every read is JPQL, which Hibernate filters by the owner of the work ({@code @TenantId}), and nothing is written
 * by a bulk statement. The entity manager is looked up on first use, so the unit tests, which start without a database,
 * can still create the application.
 */
@Repository
class JpaArtifactIndexStore implements ArtifactIndexStore {

    private static final String BY_JOB = "select i from ArtifactIndex i where i.jobId = :job";
    private static final String OF_VERSION = "select i from ArtifactIndex i where i.recordId = :record"
            + " and i.number = :number order by i.createdAt, i.jobId";
    private static final String OF_RECORDS = "select i from ArtifactIndex i where i.recordId in :records"
            + " order by i.recordId, i.number, i.jobId";
    private static final String RETRYABLE = "select i from ArtifactIndex i where i.jobId in :jobs"
            + " and i.retries < :most order by i.recordId, i.number";
    private static final String NOT_INDEXED = "select v.recordId, v.number from RecordVersion v"
            + " where v.number = (select max(w.number) from RecordVersion w where w.recordId = v.recordId)"
            + " and not exists (select i.jobId from ArtifactIndex i where i.recordId = v.recordId"
            + " and i.number = v.number and i.recipe = :recipe) order by v.recordId";
    private static final String REMOVAL_BY_JOB = "select r from ArtifactIndexRemoval r where r.jobId = :job";
    private static final String REMOVALS = "select r from ArtifactIndexRemoval r where r.jobId in :jobs"
            + " order by r.artifactId";

    private final ObjectProvider<EntityManagerFactory> factory;
    private volatile EntityManager shared;

    JpaArtifactIndexStore(ObjectProvider<EntityManagerFactory> factory) {
        this.factory = factory;
    }

    @Override
    public void insert(ArtifactIndexRow row) {
        EntityManager em = em();
        em.persist(new ArtifactIndexEntity(row));
        em.flush();
    }

    @Override
    public Optional<ArtifactIndexRow> find(UUID jobId) {
        return entity(jobId).map(ArtifactIndexEntity::row);
    }

    @Override
    public List<ArtifactIndexRow> ofVersion(UUID recordId, int number) {
        return em().createQuery(OF_VERSION, ArtifactIndexEntity.class).setParameter("record", recordId)
                .setParameter("number", number).getResultList().stream().map(ArtifactIndexEntity::row).toList();
    }

    @Override
    public List<ArtifactIndexRow> ofRecords(Collection<UUID> recordIds) {
        if (recordIds.isEmpty()) {
            return List.of();
        }
        return em().createQuery(OF_RECORDS, ArtifactIndexEntity.class).setParameter("records", List.copyOf(recordIds))
                .getResultList().stream().map(ArtifactIndexEntity::row).toList();
    }

    @Override
    public boolean recordRecipe(UUID jobId, String recipe) {
        Optional<ArtifactIndexEntity> found = entity(jobId);
        found.ifPresent(entity -> {
            entity.recordRecipe(recipe);
            em().flush();
        });
        return found.isPresent();
    }

    @Override
    public List<ArtifactIndexRow> retryable(Collection<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return List.of();
        }
        return em().createQuery(RETRYABLE, ArtifactIndexEntity.class).setParameter("jobs", List.copyOf(jobIds))
                .setParameter("most", (short) ArtifactIndexRow.MAX_RETRIES).getResultList().stream()
                .map(ArtifactIndexEntity::row).toList();
    }

    @Override
    public Optional<ArtifactIndexRow> move(UUID jobId, UUID newJobId) {
        Optional<ArtifactIndexEntity> found = entity(jobId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ArtifactIndexRow before = found.get().row();
        ArtifactIndexRow moved = new ArtifactIndexRow(newJobId, before.ownerId(), before.recordId(), before.number(),
                null, before.retries() + 1, before.createdAt());
        EntityManager em = em();
        em.remove(found.get());
        em.flush();
        em.persist(new ArtifactIndexEntity(moved));
        em.flush();
        return Optional.of(moved);
    }

    @Override
    public int deleteOfVersion(UUID recordId, int number) {
        EntityManager em = em();
        List<ArtifactIndexEntity> found = em.createQuery(OF_VERSION, ArtifactIndexEntity.class)
                .setParameter("record", recordId).setParameter("number", number).getResultList();
        found.forEach(em::remove);
        em.flush();
        return found.size();
    }

    @Override
    public int deleteOf(Collection<UUID> recordIds) {
        if (recordIds.isEmpty()) {
            return 0;
        }
        EntityManager em = em();
        List<ArtifactIndexEntity> found = em.createQuery(OF_RECORDS, ArtifactIndexEntity.class)
                .setParameter("records", List.copyOf(recordIds)).getResultList();
        found.forEach(em::remove);
        em.flush();
        return found.size();
    }

    @Override
    public List<VersionKey> notIndexedUnder(String recipe) {
        return em().createQuery(NOT_INDEXED, Object[].class).setParameter("recipe", recipe).getResultList().stream()
                .map(row -> new VersionKey((UUID) row[0], (Integer) row[1])).toList();
    }

    @Override
    public void insertRemoval(ArtifactIndexRemovalRow row) {
        EntityManager em = em();
        em.persist(new ArtifactIndexRemovalEntity(row));
        em.flush();
    }

    @Override
    public Optional<ArtifactIndexRemovalRow> findRemoval(UUID jobId) {
        return removal(jobId).map(ArtifactIndexRemovalEntity::row);
    }

    @Override
    public List<ArtifactIndexRemovalRow> removals(Collection<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return List.of();
        }
        return em().createQuery(REMOVALS, ArtifactIndexRemovalEntity.class).setParameter("jobs", List.copyOf(jobIds))
                .getResultList().stream().map(ArtifactIndexRemovalEntity::row).toList();
    }

    @Override
    public Optional<ArtifactIndexRemovalRow> moveRemoval(UUID jobId, UUID newJobId) {
        Optional<ArtifactIndexRemovalEntity> found = removal(jobId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ArtifactIndexRemovalRow before = found.get().row();
        ArtifactIndexRemovalRow moved = new ArtifactIndexRemovalRow(newJobId, before.ownerId(), before.artifactId());
        EntityManager em = em();
        em.remove(found.get());
        em.flush();
        em.persist(new ArtifactIndexRemovalEntity(moved));
        em.flush();
        return Optional.of(moved);
    }

    private Optional<ArtifactIndexEntity> entity(UUID jobId) {
        return em().createQuery(BY_JOB, ArtifactIndexEntity.class).setParameter("job", jobId).getResultList().stream()
                .findFirst();
    }

    private Optional<ArtifactIndexRemovalEntity> removal(UUID jobId) {
        return em().createQuery(REMOVAL_BY_JOB, ArtifactIndexRemovalEntity.class).setParameter("job", jobId)
                .getResultList().stream().findFirst();
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
