package io.github.gmshield.library.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.StaleStateException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Repository;

/**
 * The library tables through JPA. Every read is JPQL, which Hibernate filters by the owner of the work
 * ({@code @TenantId}); a read by id is JPQL too, never {@code find}. The entity manager is looked up on first
 * use, so the unit tests, which start without a database, can still create the application.
 *
 * <p>Story 3.1: a write that the key on {@code (owner_id, name_fingerprint)} refuses, or an update of a row whose
 * version moved, is a {@link LibraryConflict}, never the database's message; from Story 3.2 the campaign's key on
 * {@code (owner_id, system_id, name_fingerprint)} as well. Part B: every read takes ready systems, and
 * the campaigns of ready systems, only; the deletion's methods alone reach a deleting one.
 */
@Repository
class JpaLibraryStore implements LibraryStore {

    /** The unique key on a system's name fingerprint within its owner (V4). */
    static final String NAME_KEY = "system_owner_name_fingerprint_key";
    /** Story 3.1 part B: only a ready system, and only the campaigns of one, are read. */
    private static final String READY = SystemEntity.READY;
    private static final String OF_READY_SYSTEM = " exists (select r.id from LibrarySystem r where r.id = c.systemId"
            + " and r.state = 'ready')";
    private static final String SYSTEMS = "select s from LibrarySystem s where s.state = 'ready' order by s.id desc";
    private static final String SYSTEMS_BEFORE = "select s from LibrarySystem s where s.state = 'ready' and s.id < :before"
            + " order by s.id desc";
    /** The unique key on a campaign's name fingerprint within its owner and system (V23, Story 3.2). */
    static final String CAMPAIGN_NAME_KEY = "campaign_owner_system_name_fingerprint_key";
    /** Story 3.2: a list page's cursor when there is none, above every id. */
    private static final UUID TOP = new UUID(-1L, -1L);
    private static final String CAMPAIGNS = "select c from LibraryCampaign c where c.archivedAt is null"
            + " and c.id < :before and" + OF_READY_SYSTEM + " order by c.id desc";
    private static final String ARCHIVED_CAMPAIGNS = "select c from LibraryCampaign c where c.archivedAt is not null"
            + " and c.id < :before and" + OF_READY_SYSTEM + " order by c.id desc";
    private static final String CAMPAIGNS_OF = "select c from LibraryCampaign c where c.systemId = :system"
            + " and c.archivedAt is null and c.id < :before and" + OF_READY_SYSTEM + " order by c.id desc";
    private static final String ARCHIVED_CAMPAIGNS_OF = "select c from LibraryCampaign c where c.systemId = :system"
            + " and c.archivedAt is not null and c.id < :before and" + OF_READY_SYSTEM + " order by c.id desc";
    private static final String CAMPAIGN_IDS_OF = "select c.id from LibraryCampaign c where c.systemId = :system and"
            + OF_READY_SYSTEM + " order by c.id desc";
    private static final String CAMPAIGN_BY_ID = "select c from LibraryCampaign c where c.id = :id and" + OF_READY_SYSTEM;
    /** Story 3.2: the ready, not archived campaign chosen last, else the oldest. */
    private static final String CURRENT_CAMPAIGN = "select c from LibraryCampaign c where c.archivedAt is null and"
            + OF_READY_SYSTEM + " order by c.chosenAt desc nulls last, c.createdAt, c.id";
    private static final String CAMPAIGN_NAME_TAKEN = "select c.id from LibraryCampaign c where c.systemId = :system"
            + " and c.nameFingerprint = :fingerprint and c.id <> :except";
    private static final String WITHOUT_FINGERPRINT = "select c from LibraryCampaign c where c.nameFingerprint is null"
            + " and" + OF_READY_SYSTEM + " order by c.id";
    private static final String WITHOUT_FINGERPRINT_OF = "select c from LibraryCampaign c where c.systemId = :system"
            + " and c.nameFingerprint is null and" + OF_READY_SYSTEM + " order by c.id";

    private final ObjectProvider<EntityManagerFactory> factory;
    private volatile EntityManager shared;

    JpaLibraryStore(ObjectProvider<EntityManagerFactory> factory) {
        this.factory = factory;
    }

    @Override
    public void insertSystem(SystemRow row) {
        EntityManager em = em();
        em.persist(new SystemEntity(row));
        flushChecked(em);
    }

    @Override
    public Optional<SystemRow> updateSystem(SystemChange change) {
        Optional<SystemEntity> found = system(change.id());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SystemEntity entity = found.get();
        if (entity.version() != change.version()) {
            throw new LibraryConflict(LibraryConflict.Reason.STALE_VERSION);
        }
        entity.change(change);
        flushChecked(em());
        return Optional.of(entity.row());
    }

    @Override
    public boolean systemNameTaken(byte[] nameFingerprint, UUID except) {
        return !em().createQuery("select s.id from LibrarySystem s where s.nameFingerprint = :fingerprint"
                        + " and s.id <> :except", UUID.class)
                .setParameter("fingerprint", nameFingerprint).setParameter("except", except)
                .setMaxResults(1).getResultList().isEmpty();
    }

    @Override
    public List<SystemRow> systems(UUID before, int limit) {
        if (limit < 1) {
            return List.of();
        }
        TypedQuery<SystemEntity> query = em().createQuery(before == null ? SYSTEMS : SYSTEMS_BEFORE, SystemEntity.class)
                .setMaxResults(limit);
        if (before != null) {
            query.setParameter("before", before);
        }
        return query.getResultList().stream().map(SystemEntity::row).toList();
    }

    @Override
    public List<CampaignRow> campaigns(UUID systemId, boolean archived, UUID before, int limit) {
        if (limit < 1) {
            return List.of();
        }
        String text;
        if (systemId == null) {
            text = archived ? ARCHIVED_CAMPAIGNS : CAMPAIGNS;
        } else {
            text = archived ? ARCHIVED_CAMPAIGNS_OF : CAMPAIGNS_OF;
        }
        TypedQuery<CampaignEntity> query = em().createQuery(text, CampaignEntity.class)
                .setParameter("before", before == null ? TOP : before).setMaxResults(limit);
        if (systemId != null) {
            query.setParameter("system", systemId);
        }
        return query.getResultList().stream().map(CampaignEntity::row).toList();
    }

    @Override
    public List<UUID> campaignIdsOf(UUID systemId) {
        return em().createQuery(CAMPAIGN_IDS_OF, UUID.class).setParameter("system", systemId).getResultList();
    }

    @Override
    public void insertCampaign(CampaignRow row) {
        EntityManager em = em();
        em.persist(new CampaignEntity(row));
        flushChecked(em);
    }

    @Override
    public Optional<CampaignRow> updateCampaign(CampaignChange change) {
        Optional<CampaignEntity> found = campaign(change.id());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        CampaignEntity entity = found.get();
        if (entity.version() != change.version()) {
            throw new LibraryConflict(LibraryConflict.Reason.STALE_VERSION);
        }
        entity.change(change);
        flushChecked(em());
        return Optional.of(entity.row());
    }

    @Override
    public boolean chooseCampaign(UUID id, Instant at) {
        Optional<CampaignEntity> found = campaign(id).filter(entity -> !entity.archived());
        found.ifPresent(entity -> {
            entity.choose(at);
            em().flush();
        });
        return found.isPresent();
    }

    @Override
    public boolean campaignNameTaken(UUID systemId, byte[] nameFingerprint, UUID except) {
        return !em().createQuery(CAMPAIGN_NAME_TAKEN, UUID.class).setParameter("system", systemId)
                .setParameter("fingerprint", nameFingerprint).setParameter("except", except)
                .setMaxResults(1).getResultList().isEmpty();
    }

    @Override
    public List<CampaignRow> campaignsWithoutFingerprint(UUID systemId) {
        TypedQuery<CampaignEntity> query = em().createQuery(systemId == null ? WITHOUT_FINGERPRINT : WITHOUT_FINGERPRINT_OF,
                CampaignEntity.class);
        if (systemId != null) {
            query.setParameter("system", systemId);
        }
        return query.getResultList().stream().map(CampaignEntity::row).toList();
    }

    @Override
    public boolean fillCampaignFingerprint(UUID id, byte[] nameFingerprint) {
        Optional<CampaignEntity> found = campaign(id).filter(entity -> !entity.fingerprinted());
        found.ifPresent(entity -> {
            entity.fingerprint(nameFingerprint);
            flushChecked(em());
        });
        return found.isPresent();
    }

    @Override
    public Optional<SystemRow> findSystem(UUID id) {
        return system(id).map(SystemEntity::row);
    }

    @Override
    public Optional<CampaignRow> findCampaign(UUID id) {
        return campaign(id).map(CampaignEntity::row);
    }

    @Override
    public Optional<CampaignRow> currentCampaign() {
        return em().createQuery(CURRENT_CAMPAIGN, CampaignEntity.class)
                .setMaxResults(1)
                .getResultList().stream().findFirst().map(CampaignEntity::row);
    }

    @Override
    public boolean markDeleting(UUID id, Instant at) {
        Optional<SystemEntity> found = em().createQuery("select s from LibrarySystem s where s.id = :id"
                        + " and s.state = :state", SystemEntity.class)
                .setParameter("id", id).setParameter("state", READY).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList().stream().findFirst();
        found.ifPresent(entity -> {
            entity.markDeleting(at);
            em().flush();
        });
        return found.isPresent();
    }

    @Override
    public boolean deleting(UUID id) {
        return deletingSystem(id).isPresent();
    }

    @Override
    public List<UUID> campaignIdsOfDeleting(UUID systemId) {
        if (deletingSystem(systemId).isEmpty()) {
            return List.of();
        }
        return em().createQuery("select c.id from LibraryCampaign c where c.systemId = :system order by c.id desc",
                UUID.class).setParameter("system", systemId).getResultList();
    }

    @Override
    public boolean deleteDeleting(UUID id) {
        Optional<SystemEntity> system = deletingSystem(id);
        system.ifPresent(entity -> {
            EntityManager em = em();
            em.createQuery("select c from LibraryCampaign c where c.systemId = :system", CampaignEntity.class)
                    .setParameter("system", id).getResultList().forEach(em::remove);
            em.flush();
            em.remove(entity);
            em.flush();
        });
        return system.isPresent();
    }

    private Optional<SystemEntity> deletingSystem(UUID id) {
        return em().createQuery("select s from LibrarySystem s where s.id = :id and s.state = :state", SystemEntity.class)
                .setParameter("id", id).setParameter("state", SystemEntity.DELETING)
                .getResultList().stream().findFirst();
    }

    private Optional<CampaignEntity> campaign(UUID id) {
        return em().createQuery(CAMPAIGN_BY_ID, CampaignEntity.class).setParameter("id", id)
                .getResultList().stream().findFirst();
    }

    private Optional<SystemEntity> system(UUID id) {
        return em().createQuery("select s from LibrarySystem s where s.id = :id and s.state = :state", SystemEntity.class)
                .setParameter("id", id).setParameter("state", READY)
                .getResultList().stream().findFirst();
    }

    /** Writes a system or a campaign now, so that its key or its version refuses it here, as a {@link LibraryConflict}. */
    private static void flushChecked(EntityManager em) {
        try {
            em.flush();
        } catch (RuntimeException refused) {
            Optional<LibraryConflict> conflict = conflictOf(refused);
            if (conflict.isPresent()) {
                throw conflict.get();
            }
            throw refused;
        }
    }

    /** The conflict a failed write of a system stands for, if it stands for one. */
    static Optional<LibraryConflict> conflictOf(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof OptimisticLockException || cause instanceof StaleStateException) {
                return Optional.of(new LibraryConflict(LibraryConflict.Reason.STALE_VERSION));
            }
            if (cause instanceof ConstraintViolationException violation && NAME_KEY.equalsIgnoreCase(violation.getConstraintName())) {
                return Optional.of(new LibraryConflict(LibraryConflict.Reason.NAME_TAKEN));
            }
            if (cause instanceof ConstraintViolationException violation
                    && CAMPAIGN_NAME_KEY.equalsIgnoreCase(violation.getConstraintName())) {
                return Optional.of(new LibraryConflict(LibraryConflict.Reason.CAMPAIGN_NAME_TAKEN));
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
