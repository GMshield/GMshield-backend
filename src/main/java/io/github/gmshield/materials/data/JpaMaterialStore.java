package io.github.gmshield.materials.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import java.time.Instant;
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
 * The materials tables through JPA. Every read is JPQL, which Hibernate filters by the owner of the work
 * ({@code @TenantId}); a change or a removal loads the entity that way first, never by {@code find} and never by a
 * bulk statement. The entity manager is looked up on first use, so the unit tests, which start without a database,
 * can still create the application.
 */
@Repository
class JpaMaterialStore implements MaterialStore {

    private final ObjectProvider<EntityManagerFactory> factory;
    private volatile EntityManager shared;

    JpaMaterialStore(ObjectProvider<EntityManagerFactory> factory) {
        this.factory = factory;
    }

    @Override
    public void insertMaterial(MaterialRow row) {
        EntityManager em = em();
        em.persist(new MaterialEntity(row));
        em.flush();
    }

    @Override
    public void insertVersion(VersionRow row) {
        EntityManager em = em();
        em.persist(new MaterialVersionEntity(row));
        em.flush();
    }

    @Override
    public void insertMaterialSystem(MaterialSystemRow row) {
        EntityManager em = em();
        em.persist(new MaterialSystemEntity(row));
        em.flush();
    }

    @Override
    public List<MaterialSystemRow> systemsOf(UUID materialId) {
        return systemEntitiesOf(materialId).stream().map(MaterialSystemEntity::row).toList();
    }

    /**
     * The newest version of a material is the one no other version of it follows (later, or as late with a greater id).
     * Two constant query texts rather than a null parameter, whose type PostgreSQL could not tell.
     */
    private static final String NEWEST = "select m, v from Material m join MaterialVersion v on v.materialId = m.id"
            + " where v.state in :states"
            + " and not exists (select n.id from MaterialVersion n where n.materialId = m.id"
            + " and (n.createdAt > v.createdAt or (n.createdAt = v.createdAt and n.id > v.id)))";
    private static final String NEWEST_IN = NEWEST + " order by m.id desc";
    private static final String NEWEST_IN_BEFORE = NEWEST + " and m.id < :before order by m.id desc";

    @Override
    public List<NewestVersion> newestIn(Collection<String> states, UUID before, int limit) {
        if (states.isEmpty() || limit < 1) {
            return List.of();
        }
        var query = em().createQuery(before == null ? NEWEST_IN : NEWEST_IN_BEFORE, Object[].class).setParameter("states", states).setMaxResults(limit);
        if (before != null) {
            query.setParameter("before", before);
        }
        return query.getResultList().stream()
                .map(pair -> new NewestVersion(((MaterialEntity) pair[0]).row(), ((MaterialVersionEntity) pair[1]).row()))
                .toList();
    }

    @Override
    public Optional<NewestVersion> newest(UUID materialId) {
        return material(materialId).flatMap(material -> em()
                .createQuery("select v from MaterialVersion v where v.materialId = :id order by v.createdAt desc, v.id desc",
                        MaterialVersionEntity.class)
                .setParameter("id", materialId).setMaxResults(1).getResultList().stream().findFirst()
                .map(version -> new NewestVersion(material.row(), version.row())));
    }

    @Override
    public Optional<MaterialRow> findMaterial(UUID id) {
        return material(id).map(MaterialEntity::row);
    }

    @Override
    public Optional<VersionRow> findVersion(UUID id) {
        return version(id).map(MaterialVersionEntity::row);
    }

    @Override
    public boolean changeVersion(UUID id, String state, String format, Long sizeBytes, Instant now) {
        Optional<MaterialVersionEntity> version = version(id);
        version.ifPresent(entity -> {
            entity.change(state, format, sizeBytes, now);
            em().flush();
        });
        return version.isPresent();
    }

    @Override
    public boolean changeVersionState(UUID id, String state, Instant now) {
        Optional<MaterialVersionEntity> version = version(id);
        version.ifPresent(entity -> {
            entity.changeState(state, now);
            em().flush();
        });
        return version.isPresent();
    }

    @Override
    public boolean recordIngestion(UUID id, String pipelineVersion, Integer pageTotal, Instant now) {
        Optional<MaterialVersionEntity> version = version(id);
        version.ifPresent(entity -> {
            entity.record(pipelineVersion, pageTotal, now);
            em().flush();
        });
        return version.isPresent();
    }

    @Override
    public Optional<VersionRow> lockVersion(UUID id) {
        return em().createQuery("select v from MaterialVersion v where v.id = :id", MaterialVersionEntity.class)
                .setParameter("id", id).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList().stream().findFirst()
                .map(MaterialVersionEntity::row);
    }

    @Override
    public boolean clearPipelineVersion(UUID id, Instant now) {
        Optional<MaterialVersionEntity> version = version(id);
        version.ifPresent(entity -> {
            entity.clearPipelineVersion(now);
            em().flush();
        });
        return version.isPresent();
    }

    @Override
    public boolean failVersion(UUID id, String code, Instant now) {
        Optional<MaterialVersionEntity> version = version(id);
        version.ifPresent(entity -> {
            entity.fail(code, now);
            em().flush();
        });
        return version.isPresent();
    }

    @Override
    public void insertStep(StepRow row) {
        EntityManager em = em();
        em.persist(new IngestionStepEntity(row));
        em.flush();
    }

    @Override
    public Optional<StepRow> findStepByJob(UUID jobId) {
        return em().createQuery("select s from IngestionStep s where s.jobId = :job", IngestionStepEntity.class)
                .setParameter("job", jobId).getResultList().stream().findFirst().map(IngestionStepEntity::row);
    }

    @Override
    public long countSteps(UUID versionId) {
        return em().createQuery("select count(s) from IngestionStep s where s.versionId = :version", Long.class)
                .setParameter("version", versionId).getSingleResult();
    }

    @Override
    public boolean endStep(UUID jobId, Instant now) {
        Optional<IngestionStepEntity> step = em()
                .createQuery("select s from IngestionStep s where s.jobId = :job", IngestionStepEntity.class)
                .setParameter("job", jobId).getResultList().stream().findFirst();
        step.ifPresent(entity -> {
            entity.end(now);
            em().flush();
        });
        return step.isPresent();
    }

    @Override
    public List<StepRow> unendedSteps(UUID versionId) {
        return em().createQuery("select s from IngestionStep s where s.versionId = :version and s.endedAt is null"
                        + " order by s.createdAt, s.id", IngestionStepEntity.class)
                .setParameter("version", versionId).getResultList().stream().map(IngestionStepEntity::row).toList();
    }

    @Override
    public void insertVersionFile(VersionFileRow row) {
        EntityManager em = em();
        em.persist(new VersionFileEntity(row));
        em.flush();
    }

    @Override
    public List<VersionFileRow> versionFiles(UUID versionId) {
        return versionFileEntities(versionId).stream().map(VersionFileEntity::row).toList();
    }

    @Override
    public List<VersionFileRow> deleteVersionFiles(UUID versionId) {
        List<VersionFileEntity> found = versionFileEntities(versionId);
        found.forEach(em()::remove);
        em().flush();
        return found.stream().map(VersionFileEntity::row).toList();
    }

    @Override
    public List<VersionFileRow> deleteVersionFiles(UUID versionId, Collection<String> roles) {
        List<VersionFileEntity> found = versionFileEntities(versionId).stream()
                .filter(file -> roles.contains(file.row().role())).toList();
        found.forEach(em()::remove);
        em().flush();
        return found.stream().map(VersionFileEntity::row).toList();
    }

    @Override
    public void insertPageLayer(PageRangeRow row) {
        EntityManager em = em();
        em.persist(new PageLayerEntity(row));
        em.flush();
    }

    @Override
    public void insertPageOutcome(PageRangeRow row) {
        EntityManager em = em();
        em.persist(new PageOutcomeEntity(row));
        em.flush();
    }

    /** A range overlaps another when it starts before the other ends and ends after the other starts. */
    private static final String LAYER_OVERLAP = "select count(r) from PageLayer r where r.versionId = :version"
            + " and r.firstPage <= :last and r.lastPage >= :first";
    private static final String OUTCOME_OVERLAP = "select count(r) from PageOutcome r where r.versionId = :version"
            + " and r.firstPage <= :last and r.lastPage >= :first";

    @Override
    public boolean layerOverlaps(UUID versionId, int firstPage, int lastPage) {
        return overlaps(LAYER_OVERLAP, versionId, firstPage, lastPage);
    }

    @Override
    public boolean outcomeOverlaps(UUID versionId, int firstPage, int lastPage) {
        return overlaps(OUTCOME_OVERLAP, versionId, firstPage, lastPage);
    }

    private boolean overlaps(String query, UUID versionId, int firstPage, int lastPage) {
        return em().createQuery(query, Long.class).setParameter("version", versionId).setParameter("first", firstPage)
                .setParameter("last", lastPage).getSingleResult() > 0;
    }

    @Override
    public List<PageRangeRow> outcomesWithin(UUID versionId, int firstPage, int lastPage) {
        return em().createQuery("select r from PageOutcome r where r.versionId = :version and r.firstPage <= :last"
                        + " and r.lastPage >= :first order by r.firstPage", PageOutcomeEntity.class)
                .setParameter("version", versionId).setParameter("first", firstPage).setParameter("last", lastPage)
                .getResultList().stream().map(PageOutcomeEntity::row).toList();
    }

    @Override
    public List<PageRangeRow> pageLayers(UUID versionId) {
        return layerEntities(versionId).stream().map(PageLayerEntity::row).toList();
    }

    @Override
    public Map<UUID, Long> pagesDone(Collection<UUID> versionIds) {
        Map<UUID, Long> done = new HashMap<>();
        if (versionIds.isEmpty()) {
            return done;
        }
        em().createQuery("select r.versionId, sum(r.lastPage - r.firstPage + 1) from PageOutcome r"
                        + " where r.versionId in :versions group by r.versionId", Object[].class)
                .setParameter("versions", versionIds).getResultList()
                .forEach(pair -> done.put((UUID) pair[0], ((Number) pair[1]).longValue()));
        return done;
    }

    @Override
    public int deletePageRanges(UUID versionId) {
        List<PageLayerEntity> layers = layerEntities(versionId);
        List<PageOutcomeEntity> outcomes = em().createQuery("select r from PageOutcome r where r.versionId = :version",
                PageOutcomeEntity.class).setParameter("version", versionId).getResultList();
        layers.forEach(em()::remove);
        outcomes.forEach(em()::remove);
        em().flush();
        return layers.size() + outcomes.size();
    }

    private List<PageLayerEntity> layerEntities(UUID versionId) {
        return em().createQuery("select r from PageLayer r where r.versionId = :version order by r.firstPage",
                PageLayerEntity.class).setParameter("version", versionId).getResultList();
    }

    private List<VersionFileEntity> versionFileEntities(UUID versionId) {
        return em().createQuery("select f from VersionFile f where f.versionId = :version"
                        + " order by f.role desc, f.firstPage, f.id", VersionFileEntity.class)
                .setParameter("version", versionId).getResultList();
    }

    @Override
    public boolean deleteVersion(UUID id) {
        Optional<MaterialVersionEntity> version = version(id);
        version.ifPresent(entity -> {
            em().remove(entity);
            em().flush();
        });
        return version.isPresent();
    }

    @Override
    public boolean deleteMaterial(UUID id) {
        Optional<MaterialEntity> material = material(id);
        material.ifPresent(entity -> {
            systemEntitiesOf(id).forEach(em()::remove);
            em().remove(entity);
            em().flush();
        });
        return material.isPresent();
    }

    private Optional<MaterialEntity> material(UUID id) {
        return em().createQuery("select m from Material m where m.id = :id", MaterialEntity.class)
                .setParameter("id", id).getResultList().stream().findFirst();
    }

    private List<MaterialSystemEntity> systemEntitiesOf(UUID materialId) {
        return em().createQuery("select s from MaterialSystem s where s.materialId = :id order by s.createdAt, s.id",
                MaterialSystemEntity.class).setParameter("id", materialId).getResultList();
    }

    private Optional<MaterialVersionEntity> version(UUID id) {
        return em().createQuery("select v from MaterialVersion v where v.id = :id", MaterialVersionEntity.class)
                .setParameter("id", id).getResultList().stream().findFirst();
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
