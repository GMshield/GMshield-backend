package io.github.gmshield.artifacts.data;

import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Repository;

/**
 * {@code record_version} through JPA (Story 5.1), as the library's stores: every read is JPQL, which Hibernate filters by
 * the owner of the work ({@code @TenantId}), and nothing is written by a bulk statement (a delete loads the rows and
 * removes them). The entity manager is looked up on first use, so the unit tests, which start without a database, can
 * still create the application. Story 5.2: an insert of a number the record already has (the key
 * {@value #KEY}) is {@link VersionTaken}.
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
    /** A record's versions, newest first: one text per filter (Sonar S2077 wants no text assembled at run time). */
    private static final String PAGE = "select v from RecordVersion v where v.recordId = :record"
            + " order by v.number desc";
    private static final String PAGE_BEFORE = "select v from RecordVersion v where v.recordId = :record"
            + " and v.number < :before order by v.number desc";
    private static final String COUNT = "select count(v) from RecordVersion v where v.recordId = :record";
    /** The table's key on (record, number) (V43). */
    static final String KEY = "record_version_pkey";

    private final ObjectProvider<EntityManagerFactory> factory;
    private volatile EntityManager shared;

    JpaArtifactVersionStore(ObjectProvider<EntityManagerFactory> factory) {
        this.factory = factory;
    }

    @Override
    public void insert(ArtifactVersionRow row) {
        EntityManager em = em();
        try {
            em.persist(new ArtifactVersionEntity(row));
            em.flush();
        } catch (RuntimeException refused) {
            if (taken(refused)) {
                throw new VersionTaken(refused);
            }
            throw refused;
        }
    }

    @Override
    public List<ArtifactVersionRow> page(UUID recordId, Integer before, int offset, int limit) {
        TypedQuery<ArtifactVersionEntity> read = em().createQuery(before == null ? PAGE : PAGE_BEFORE,
                ArtifactVersionEntity.class).setParameter("record", recordId);
        if (before != null) {
            read.setParameter("before", before);
        }
        return read.setFirstResult(offset).setMaxResults(limit).getResultList().stream()
                .map(ArtifactVersionEntity::row).toList();
    }

    @Override
    public long count(UUID recordId) {
        return em().createQuery(COUNT, Long.class).setParameter("record", recordId).getSingleResult();
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

    /** Whether a failed insert stands for a number its record already has. */
    private static boolean taken(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof EntityExistsException) {
                return true;
            }
            if (cause instanceof ConstraintViolationException violation
                    && KEY.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
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
