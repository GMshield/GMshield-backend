package io.github.gmshield.knowledge.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.hibernate.StaleStateException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Repository;

/**
 * {@code record_type} through JPA (Story 4.1), as the library's stores: every read is JPQL, which Hibernate filters by
 * the owner of the work ({@code @TenantId}); a read by id is JPQL too, never {@code find}, and nothing is written by a
 * bulk statement. A write that the key on {@code (owner_id, system_id, core)} or on
 * {@code (owner_id, system_id, name_fingerprint)} refuses, or an update of a row whose version moved, is a
 * {@link KnowledgeConflict}, never the database's message. The entity manager is looked up on first use, so the unit
 * tests, which start without a database, can still create the application.
 */
@Repository
class JpaKnowledgeStore implements KnowledgeStore {

    static final String CORE_KEY = "record_type_owner_system_core_key";
    static final String NAME_KEY = "record_type_owner_system_name_fingerprint_key";
    static final String KEY_KEY = "record_type_owner_system_key_key";
    /** The core types first, in their fixed order (CoreRecordTypes), then the others oldest first. */
    private static final String OF_SYSTEM = "select r from RecordType r where r.systemId = :system order by case"
            + " when r.core = 'rule' then 0 when r.core = 'spell' then 1 when r.core = 'item' then 2"
            + " when r.core = 'creature' then 3 when r.core = 'character_sheet' then 4 when r.core = 'encounter' then 5"
            + " when r.core = 'dungeon' then 6 when r.core = 'note' then 7 else 8 end, r.id";
    private static final String COUNT_OF_SYSTEM = "select count(r) from RecordType r where r.systemId = :system";
    private static final String CORE_OF_SYSTEM = "select r.core from RecordType r where r.systemId = :system"
            + " and r.core is not null";
    private static final String BY_ID = "select r from RecordType r where r.id = :id";
    private static final String NAME_TAKEN = "select r.id from RecordType r where r.systemId = :system"
            + " and r.nameFingerprint = :fingerprint and r.id <> :except";
    private static final String ALL_OF_SYSTEM = "select r from RecordType r where r.systemId = :system";
    private static final String CORE_ROWS_OF_SYSTEM = "select r from RecordType r where r.systemId = :system"
            + " and r.core is not null order by r.id";
    private static final String KEY_TAKEN = "select r.id from RecordType r where r.systemId = :system and r.typeKey = :key";
    private static final String KEYS_OF_SYSTEM = "select coalesce(r.typeKey, r.core) from RecordType r"
            + " where r.systemId = :system";
    private static final String BY_KEY = "select r from RecordType r where r.systemId = :system"
            + " and (r.typeKey = :key or (r.typeKey is null and r.core = :key))";
    private static final String APPROVED_OF_SYSTEM = "select r from RecordType r where r.systemId = :system"
            + " and r.core is null order by r.id";
    private static final String NAMED_ANY = "select r from RecordType r where r.systemId = :system"
            + " and r.nameFingerprint in :fingerprints order by r.id";

    private final ObjectProvider<EntityManagerFactory> factory;
    private volatile EntityManager shared;

    JpaKnowledgeStore(ObjectProvider<EntityManagerFactory> factory) {
        this.factory = factory;
    }

    @Override
    public void insertRecordTypes(List<RecordTypeRow> rows) {
        EntityManager em = em();
        rows.forEach(row -> em.persist(new RecordTypeEntity(row)));
        flushChecked(em);
    }

    @Override
    public List<RecordTypeRow> recordTypes(UUID systemId, int offset, int limit) {
        if (limit < 1) {
            return List.of();
        }
        return em().createQuery(OF_SYSTEM, RecordTypeEntity.class).setParameter("system", systemId)
                .setFirstResult(Math.max(offset, 0)).setMaxResults(limit).getResultList().stream()
                .map(RecordTypeEntity::row).toList();
    }

    @Override
    public long recordTypeCount(UUID systemId) {
        return em().createQuery(COUNT_OF_SYSTEM, Long.class).setParameter("system", systemId).getSingleResult();
    }

    @Override
    public Set<String> coreKeys(UUID systemId) {
        return new HashSet<>(em().createQuery(CORE_OF_SYSTEM, String.class).setParameter("system", systemId)
                .getResultList());
    }

    @Override
    public Optional<RecordTypeRow> findRecordType(UUID id) {
        return entity(id, LockModeType.NONE).map(RecordTypeEntity::row);
    }

    @Override
    public boolean nameTaken(UUID systemId, byte[] nameFingerprint, UUID except) {
        return !em().createQuery(NAME_TAKEN, UUID.class).setParameter("system", systemId)
                .setParameter("fingerprint", nameFingerprint).setParameter("except", except)
                .setMaxResults(1).getResultList().isEmpty();
    }

    @Override
    public Optional<RecordTypeRow> rename(UUID id, long version, byte[] name, byte[] nameFingerprint) {
        Optional<RecordTypeEntity> found = entity(id, LockModeType.PESSIMISTIC_WRITE);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RecordTypeEntity entity = found.get();
        if (entity.version() != version) {
            throw new KnowledgeConflict(KnowledgeConflict.Reason.STALE_VERSION);
        }
        entity.rename(name, nameFingerprint);
        flushChecked(em());
        return Optional.of(entity.row());
    }

    @Override
    public boolean delete(UUID id) {
        Optional<RecordTypeEntity> found;
        try {
            found = entity(id, LockModeType.PESSIMISTIC_WRITE);
        } catch (RuntimeException refused) {
            // A rename committed since this transaction's first read of the row: the delete is stale.
            throw conflictOf(refused).orElseThrow(() -> refused);
        }
        if (found.isEmpty()) {
            return false;
        }
        EntityManager em = em();
        em.remove(found.get());
        flushChecked(em);
        return true;
    }

    @Override
    public void deleteOfSystem(UUID systemId) {
        EntityManager em = em();
        em.createQuery(ALL_OF_SYSTEM, RecordTypeEntity.class).setParameter("system", systemId).getResultList()
                .forEach(em::remove);
        em.flush();
    }

    @Override
    public void lockOntology(UUID systemId) {
        em().createQuery(CORE_ROWS_OF_SYSTEM, RecordTypeEntity.class).setParameter("system", systemId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
    }

    @Override
    public boolean keyTaken(UUID systemId, String key) {
        return !em().createQuery(KEY_TAKEN, UUID.class).setParameter("system", systemId).setParameter("key", key)
                .setMaxResults(1).getResultList().isEmpty();
    }

    @Override
    public Set<String> keys(UUID systemId) {
        return new HashSet<>(em().createQuery(KEYS_OF_SYSTEM, String.class).setParameter("system", systemId)
                .getResultList());
    }

    @Override
    public Optional<RecordTypeRow> findByKey(UUID systemId, String key) {
        return em().createQuery(BY_KEY, RecordTypeEntity.class).setParameter("system", systemId)
                .setParameter("key", key).setMaxResults(1).getResultList().stream().findFirst()
                .map(RecordTypeEntity::row);
    }

    @Override
    public List<RecordTypeRow> approvedRecordTypes(UUID systemId) {
        return em().createQuery(APPROVED_OF_SYSTEM, RecordTypeEntity.class).setParameter("system", systemId)
                .getResultList().stream().map(RecordTypeEntity::row).toList();
    }

    @Override
    public List<RecordTypeRow> recordTypesNamed(UUID systemId, Collection<byte[]> nameFingerprints) {
        List<byte[]> fingerprints = List.copyOf(nameFingerprints);
        Map<UUID, RecordTypeRow> found = new TreeMap<>();
        for (int from = 0; from < fingerprints.size(); from += JpaRecordStore.FINGERPRINTS_PER_QUERY) {
            List<byte[]> chunk = fingerprints.subList(from, Math.min(fingerprints.size(),
                    from + JpaRecordStore.FINGERPRINTS_PER_QUERY));
            em().createQuery(NAMED_ANY, RecordTypeEntity.class).setParameter("system", systemId)
                    .setParameter("fingerprints", chunk).getResultList().stream().map(RecordTypeEntity::row)
                    .forEach(row -> found.putIfAbsent(row.id(), row));
        }
        return List.copyOf(found.values());
    }

    private Optional<RecordTypeEntity> entity(UUID id, LockModeType lock) {
        return em().createQuery(BY_ID, RecordTypeEntity.class).setParameter("id", id).setLockMode(lock)
                .getResultList().stream().findFirst();
    }

    private static void flushChecked(EntityManager em) {
        try {
            em.flush();
        } catch (RuntimeException refused) {
            Optional<KnowledgeConflict> conflict = conflictOf(refused);
            if (conflict.isPresent()) {
                throw conflict.get();
            }
            throw refused;
        }
    }

    /** The conflict a failed write of a record type stands for, if it stands for one. */
    static Optional<KnowledgeConflict> conflictOf(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof OptimisticLockException || cause instanceof StaleStateException) {
                return Optional.of(new KnowledgeConflict(KnowledgeConflict.Reason.STALE_VERSION));
            }
            if (cause instanceof ConstraintViolationException violation && CORE_KEY.equalsIgnoreCase(violation.getConstraintName())) {
                return Optional.of(new KnowledgeConflict(KnowledgeConflict.Reason.CORE_TAKEN));
            }
            if (cause instanceof ConstraintViolationException violation
                    && (NAME_KEY.equalsIgnoreCase(violation.getConstraintName())
                    || KEY_KEY.equalsIgnoreCase(violation.getConstraintName()))) {
                return Optional.of(new KnowledgeConflict(KnowledgeConflict.Reason.NAME_TAKEN));
            }
        }
        return Optional.empty();
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
