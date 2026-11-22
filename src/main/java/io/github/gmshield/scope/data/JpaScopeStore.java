package io.github.gmshield.scope.data;

import io.github.gmshield.shared.service.OwnerContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.StaleStateException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Repository;

/**
 * The scope tables through JPA, as the library's stores: every read is JPQL, which Hibernate filters by the owner of
 * the work; nothing is read by {@code find} or written by a bulk statement. The entity manager is looked up on first
 * use.
 */
@Repository
class JpaScopeStore implements ScopeStore {

    private static final String SETTING = "select s from ScopeSetting s where s.campaignId = :campaign";
    private static final String OFF = "select o from ScopeMaterialOff o where o.key.campaignId = :campaign";

    private final ObjectProvider<EntityManagerFactory> factory;
    private volatile EntityManager shared;

    JpaScopeStore(ObjectProvider<EntityManagerFactory> factory) {
        this.factory = factory;
    }

    @Override
    public Optional<StoredScope> find(UUID campaignId) {
        return em().createQuery(SETTING, ScopeSettingEntity.class).setParameter("campaign", campaignId)
                .getResultList().stream().findFirst().map(this::stored);
    }

    @Override
    public StoredScope change(UUID campaignId, long version, boolean campaignTier, boolean systemTier,
                              boolean otherTier, boolean modelKnowledge, Set<UUID> materialsOff, Instant now) {
        EntityManager em = em();
        UUID owner = OwnerContext.require();
        Optional<ScopeSettingEntity> found = em.createQuery(SETTING, ScopeSettingEntity.class)
                .setParameter("campaign", campaignId).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList()
                .stream().findFirst();
        ScopeSettingEntity entity;
        if (found.isPresent()) {
            entity = found.get();
            if (entity.version() != version) {
                throw new StaleScopeVersion();
            }
            entity.change(campaignTier, systemTier, otherTier, modelKnowledge, now);
            flushChecked(em);
            // Every change raises the version once: when no column of the row changed (only the materials off, in the
            // same microsecond or under a fixed clock), the flush wrote nothing, so the increment is forced.
            if (entity.version() == version) {
                em.lock(entity, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
            }
        } else {
            if (version != 0) {
                throw new StaleScopeVersion();
            }
            entity = new ScopeSettingEntity(campaignId, owner, now);
            entity.change(campaignTier, systemTier, otherTier, modelKnowledge, now);
            em.persist(entity);
        }
        flushChecked(em);
        List<ScopeMaterialOffEntity> stored = em.createQuery(OFF, ScopeMaterialOffEntity.class)
                .setParameter("campaign", campaignId).getResultList();
        Set<UUID> kept = new HashSet<>();
        for (ScopeMaterialOffEntity off : stored) {
            if (materialsOff.contains(off.materialId())) {
                kept.add(off.materialId());
            } else {
                em.remove(off);
            }
        }
        em.flush();
        for (UUID materialId : materialsOff) {
            if (!kept.contains(materialId)) {
                em.persist(new ScopeMaterialOffEntity(campaignId, materialId, owner));
            }
        }
        em.flush();
        return new StoredScope(campaignId, entity.campaignTier(), entity.systemTier(), entity.otherTier(),
                entity.modelKnowledge(), materialsOff, entity.version());
    }

    @Override
    public void deleteOfCampaigns(Collection<UUID> campaignIds) {
        if (campaignIds.isEmpty()) {
            return;
        }
        EntityManager em = em();
        em.createQuery("select o from ScopeMaterialOff o where o.key.campaignId in :campaigns",
                ScopeMaterialOffEntity.class).setParameter("campaigns", campaignIds).getResultList().forEach(em::remove);
        em.flush();
        em.createQuery("select s from ScopeSetting s where s.campaignId in :campaigns", ScopeSettingEntity.class)
                .setParameter("campaigns", campaignIds).getResultList().forEach(em::remove);
        em.flush();
    }

    @Override
    public void deleteOfMaterial(UUID materialId) {
        EntityManager em = em();
        em.createQuery("select o from ScopeMaterialOff o where o.key.materialId = :material",
                ScopeMaterialOffEntity.class).setParameter("material", materialId).getResultList().forEach(em::remove);
        em.flush();
    }

    private StoredScope stored(ScopeSettingEntity entity) {
        Set<UUID> off = em().createQuery(OFF, ScopeMaterialOffEntity.class)
                .setParameter("campaign", entity.campaignId()).getResultList().stream()
                .map(ScopeMaterialOffEntity::materialId).collect(Collectors.toSet());
        return new StoredScope(entity.campaignId(), entity.campaignTier(), entity.systemTier(), entity.otherTier(),
                entity.modelKnowledge(), off, entity.version());
    }

    /** A flush the version or the key refuses (another change got there first) is a stale version. */
    private static void flushChecked(EntityManager em) {
        try {
            em.flush();
        } catch (RuntimeException refused) {
            for (Throwable cause = refused; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
                if (cause instanceof OptimisticLockException || cause instanceof StaleStateException
                        || cause instanceof ConstraintViolationException) {
                    throw new StaleScopeVersion();
                }
            }
            throw refused;
        }
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
