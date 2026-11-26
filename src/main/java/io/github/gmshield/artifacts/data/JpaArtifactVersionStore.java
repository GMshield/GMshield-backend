package io.github.gmshield.artifacts.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Repository;

/**
 * {@code record_version} through JPA (Story 5.1), as the library's stores: every read is JPQL, which Hibernate filters by
 * the owner of the work ({@code @TenantId}), and nothing is written by a bulk statement (a delete loads the rows and
 * removes them). The entity manager is looked up on first use, so the unit tests, which start without a database, can
 * still create the application.
 */
@Repository
class JpaArtifactVersionStore implements ArtifactVersionStore {

    private static final String LATEST = "select v from RecordVersion v where v.recordId = :record"
            + " order by v.number desc";
    private static final String LATEST_OF = "select v from RecordVersion v where v.recordId in :records"
            + " and v.number = (select max(w.number) from RecordVersion w where w.recordId = v.recordId)";
    private static final String ONE = "select v from RecordVersion v where v.recordId = :record"
            + " and v.number = :number";
    private static final String ALL_OF = "select v from RecordVersion v where v.recordId in :records";

    private final ObjectProvider<EntityManagerFactory> factory;
    private volatile EntityManager shared;

    JpaArtifactVersionStore(ObjectProvider<EntityManagerFactory> factory) {
        this.factory = factory;
    }

    @Override
    public void insert(ArtifactVersionRow row) {
        EntityManager em = em();
        em.persist(new ArtifactVersionEntity(row));
        em.flush();
    }

    @Override
    public Optional<ArtifactVersionRow> latest(UUID recordId) {
        return em().createQuery(LATEST, ArtifactVersionEntity.class).setParameter("record", recordId)
                .setMaxResults(1).getResultList().stream().findFirst().map(ArtifactVersionEntity::row);
    }

    @Override
    public Map<UUID, ArtifactVersionRow> latest(Collection<UUID> recordIds) {
        if (recordIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ArtifactVersionRow> found = new HashMap<>();
        em().createQuery(LATEST_OF, ArtifactVersionEntity.class).setParameter("records", recordIds).getResultList()
                .forEach(entity -> {
                    ArtifactVersionRow row = entity.row();
                    found.put(row.recordId(), row);
                });
        return found;
    }

    @Override
    public Optional<ArtifactVersionRow> find(UUID recordId, int number) {
        return em().createQuery(ONE, ArtifactVersionEntity.class).setParameter("record", recordId)
                .setParameter("number", number).getResultList().stream().findFirst().map(ArtifactVersionEntity::row);
    }

    @Override
    public Optional<ArtifactVersionRow> lock(UUID recordId, int number) {
        return em().createQuery(ONE, ArtifactVersionEntity.class).setParameter("record", recordId)
                .setParameter("number", number).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList().stream()
                .findFirst().map(ArtifactVersionEntity::row);
    }

    @Override
    public int deleteOf(Collection<UUID> recordIds) {
        if (recordIds.isEmpty()) {
            return 0;
        }
        EntityManager em = em();
        List<ArtifactVersionEntity> found = em.createQuery(ALL_OF, ArtifactVersionEntity.class)
                .setParameter("records", recordIds).getResultList();
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
