package io.github.gmshield.knowledge.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Repository;

/**
 * {@code record} and {@code record_passage} through JPA (Story 4.2), as the library's stores: every read is JPQL, which
 * Hibernate filters by the owner of the work ({@code @TenantId}), and nothing is written by a bulk statement (a delete
 * loads the rows and removes them, links first). From Story 4.5 a correction locks its row and writes it through its
 * entity; a row whose version moved is a {@link KnowledgeConflict}, never the database's message. From Story 4.5 part B
 * every read leaves a deleted record (a tombstone) out, as {@link RecordStore} says, and a delete locks the row and marks
 * it through its entity. The entity manager is looked up on first use, so the unit tests, which start without a
 * database, can still create the application.
 *
 * <p>Story 5.1: an artifact is a row of {@code record} with a campaign and no version (V43). Every query of an extracted
 * record names a version or ({@link #EXTRACTED}) requires one, so none answers an artifact, even by its id; the artifact
 * queries name the campaign. From Story 5.2 part B every artifact query leaves out a deleting artifact (its
 * {@code deleted_at} set by the GM's delete), except the two {@code ALL} reaches a deletion's removal reads.
 */
@Repository
class JpaRecordStore implements RecordStore {

    private static final String LIVE = " and r.deletedAt is null";
    /** Story 5.1: an extracted record has a version; an artifact (a campaign's record) has none and is left out. */
    private static final String EXTRACTED = " and r.versionId is not null";
    private static final String OF_VERSION = "select r from Record r where r.versionId = :version" + LIVE
            + " order by r.firstPage, r.ordinal, r.id";
    private static final String ALL_OF_VERSION = "select r from Record r where r.versionId = :version";
    private static final String TOMBSTONES = "select r from Record r where r.versionId = :version"
            + " and r.deletedAt is not null order by r.firstPage, r.ordinal, r.id";
    private static final String IN_RANGE = "select r from Record r where r.versionId = :version"
            + " and r.firstPage between :first and :last";
    private static final String LINKS = "select l from RecordPassage l where l.recordId in :records"
            + " order by l.recordId, l.passageId";
    private static final String BY_ID = "select r from Record r where r.id = :id" + EXTRACTED;
    private static final String LIVE_BY_ID = BY_ID + LIVE;
    private static final String VERSION_OF = "select r.versionId from Record r where r.id = :id" + EXTRACTED + LIVE;
    private static final String OF_TYPE = "select r from Record r where r.versionId in :versions"
            + " and r.recordType = :type" + LIVE;
    private static final String LINKS_OF = "select l.passageId from RecordPassage l where l.recordId = :record"
            + " order by l.passageId";
    private static final String BY_IDS = "select r from Record r where r.id in :ids" + EXTRACTED;
    private static final String DELETED_BY_IDS = BY_IDS + " and r.deletedAt is not null and r.pointRemovedAt is null";
    private static final String NAMED = "select r from Record r where r.versionId in :versions"
            + " and r.nameFingerprint = :fingerprint" + LIVE + " order by r.id";
    private static final String NAMED_ANY = "select r from Record r where r.versionId in :versions"
            + " and r.recordType in :types and r.nameFingerprint in :fingerprints" + LIVE + " order by r.id";
    /** The most fingerprints one fingerprint-IN query binds (Story 4.6); more are asked in several queries. */
    static final int FINGERPRINTS_PER_QUERY = 500;
    private static final String COUNTS = "select r.recordType, count(r) from Record r where r.versionId in :versions"
            + LIVE + " group by r.recordType order by r.recordType";

    private static final String ARTIFACT = "select r from Record r where r.campaignId = :campaign and r.id = :id"
            + LIVE;
    /** A campaign's artifacts, newest first: one text per filter (Sonar S2077 wants no text assembled at run time). */
    private static final String ARTIFACTS = "select r from Record r where r.campaignId = :campaign" + LIVE
            + " order by r.id desc";
    private static final String ARTIFACTS_OF_TYPE = "select r from Record r where r.campaignId = :campaign"
            + " and r.recordType = :type" + LIVE + " order by r.id desc";
    private static final String ARTIFACTS_BEFORE = "select r from Record r where r.campaignId = :campaign"
            + " and r.id < :before" + LIVE + " order by r.id desc";
    private static final String ARTIFACTS_OF_TYPE_BEFORE = "select r from Record r where r.campaignId = :campaign"
            + " and r.recordType = :type and r.id < :before" + LIVE + " order by r.id desc";
    private static final String ARTIFACT_COUNT = "select count(r) from Record r where r.campaignId = :campaign" + LIVE;
    private static final String ARTIFACT_COUNT_OF_TYPE = "select count(r) from Record r where r.campaignId = :campaign"
            + " and r.recordType = :type" + LIVE;
    private static final String ARTIFACTS_IN = "select count(r) from Record r where r.campaignId in :campaigns" + LIVE;
    private static final String ARTIFACTS_OF_TYPE_IN = "select count(r) from Record r where r.campaignId in :campaigns"
            + " and r.recordType = :type" + LIVE;
    private static final String ARTIFACT_TYPES = "select r.recordType, count(r) from Record r"
            + " where r.campaignId = :campaign" + LIVE + " group by r.recordType order by r.recordType";
    /** Story 5.2 part B: the ids of the campaigns' artifacts, deleting ones too (a deletion's removal reads these). */
    private static final String ARTIFACT_IDS = "select r.id from Record r where r.campaignId in :campaigns"
            + " order by r.id";
    private static final String LIVE_ARTIFACT_IDS = "select r.id from Record r where r.campaignId in :campaigns"
            + LIVE + " order by r.id";
    private static final String ARTIFACTS_FOUND = "select r from Record r where r.id in :ids"
            + " and r.campaignId is not null order by r.id";
    private static final String LIVE_ARTIFACTS_FOUND = "select r from Record r where r.id in :ids"
            + " and r.campaignId is not null" + LIVE + " order by r.id";
    /** One live artifact by id, of any campaign (Story 5.2: its retype locks it, and from part B its delete). */
    private static final String ARTIFACT_BY_ID = "select r from Record r where r.id = :id and r.campaignId is not null"
            + LIVE;
    private static final String ARTIFACTS_BY_IDS = "select r from Record r where r.id in :ids"
            + " and r.campaignId is not null";

    private final ObjectProvider<EntityManagerFactory> factory;
    private volatile EntityManager shared;

    JpaRecordStore(ObjectProvider<EntityManagerFactory> factory) {
        this.factory = factory;
    }

    @Override
    public void insert(List<RecordRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        EntityManager em = em();
        // The records first, so each link's key on (owner, record) finds its record.
        rows.forEach(row -> em.persist(new RecordEntity(row)));
        em.flush();
        rows.forEach(row -> row.passageIds().forEach(passage ->
                em.persist(new RecordPassageEntity(row.ownerId(), row.id(), passage))));
        em.flush();
    }

    @Override
    public List<RecordRow> ofVersion(UUID versionId) {
        return withLinks(em().createQuery(OF_VERSION, RecordEntity.class).setParameter("version", versionId)
                .getResultList());
    }

    @Override
    public List<RecordRow> tombstones(UUID versionId) {
        return withLinks(em().createQuery(TOMBSTONES, RecordEntity.class).setParameter("version", versionId)
                .getResultList());
    }

    @Override
    public int deleteRange(UUID versionId, int firstPage, int lastPage) {
        return delete(em().createQuery(IN_RANGE, RecordEntity.class).setParameter("version", versionId)
                .setParameter("first", firstPage).setParameter("last", lastPage).getResultList());
    }

    @Override
    public int deleteOfVersion(UUID versionId) {
        return delete(em().createQuery(ALL_OF_VERSION, RecordEntity.class).setParameter("version", versionId)
                .getResultList());
    }

    @Override
    public Map<String, Long> countsByType(Collection<UUID> versionIds) {
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        em().createQuery(COUNTS, Object[].class).setParameter("versions", versionIds).getResultList()
                .forEach(row -> counts.put((String) row[0], (Long) row[1]));
        return counts;
    }

    @Override
    public Optional<UUID> versionOf(UUID id) {
        return em().createQuery(VERSION_OF, UUID.class).setParameter("id", id).getResultList().stream().findFirst();
    }

    @Override
    public Optional<RecordRow> find(UUID id) {
        List<RecordEntity> found = em().createQuery(LIVE_BY_ID, RecordEntity.class).setParameter("id", id)
                .getResultList();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(found.getFirst().row(passagesOf(id)));
    }

    @Override
    public List<RecordRow> ofTypeIn(Collection<UUID> versionIds, String type) {
        if (versionIds.isEmpty()) {
            return List.of();
        }
        return em().createQuery(OF_TYPE, RecordEntity.class).setParameter("versions", versionIds)
                .setParameter("type", type).getResultList().stream().map(entity -> entity.row(List.of())).toList();
    }

    @Override
    public List<UUID> passagesOf(UUID recordId) {
        return em().createQuery(LINKS_OF, UUID.class).setParameter("record", recordId).getResultList();
    }

    @Override
    public List<RecordRow> findAll(Collection<UUID> ids, Reach reach) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String query = switch (reach) {
            case LIVE -> BY_IDS + LIVE;
            case ALL -> BY_IDS;
        };
        return withLinks(em().createQuery(query, RecordEntity.class).setParameter("ids", ids).getResultList());
    }

    @Override
    public List<RecordRow> named(Collection<UUID> versionIds, byte[] nameFingerprint) {
        if (versionIds.isEmpty()) {
            return List.of();
        }
        return withLinks(em().createQuery(NAMED, RecordEntity.class).setParameter("versions", versionIds)
                .setParameter("fingerprint", nameFingerprint).getResultList());
    }

    @Override
    public List<RecordRow> namedAny(Collection<UUID> versionIds, Collection<String> recordTypes,
                                    Collection<byte[]> nameFingerprints, int limit) {
        if (versionIds.isEmpty() || recordTypes.isEmpty() || nameFingerprints.isEmpty() || limit < 1) {
            return List.of();
        }
        List<byte[]> fingerprints = List.copyOf(nameFingerprints);
        Map<UUID, RecordEntity> found = new TreeMap<>();
        for (int from = 0; from < fingerprints.size(); from += FINGERPRINTS_PER_QUERY) {
            List<byte[]> chunk = fingerprints.subList(from, Math.min(fingerprints.size(), from + FINGERPRINTS_PER_QUERY));
            em().createQuery(NAMED_ANY, RecordEntity.class).setParameter("versions", versionIds)
                    .setParameter("types", recordTypes).setParameter("fingerprints", chunk).setMaxResults(limit)
                    .getResultList().forEach(entity -> found.putIfAbsent(entity.id(), entity));
        }
        return withLinks(found.values().stream().limit(limit).toList());
    }

    @Override
    public Optional<RecordRow> correct(UUID id, long version, RecordCorrection correction) {
        Optional<RecordEntity> found = locked(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RecordEntity entity = found.get();
        if (entity.deleted()) {
            return Optional.empty();
        }
        if (entity.rowVersion() != version) {
            throw new KnowledgeConflict(KnowledgeConflict.Reason.STALE_VERSION);
        }
        entity.correct(correction);
        flushChecked();
        return Optional.of(entity.row(passagesOf(id)));
    }

    @Override
    public boolean carry(UUID id, RecordCorrection correction, String extractedType, byte[] extractedNameFingerprint) {
        Optional<RecordEntity> found = locked(id);
        found.ifPresent(entity -> {
            entity.carry(correction, extractedType, extractedNameFingerprint);
            flushChecked();
        });
        return found.isPresent();
    }

    @Override
    public boolean delete(UUID id, Instant at, boolean pointRemoved) {
        Optional<RecordEntity> found = locked(id).filter(entity -> !entity.deleted());
        found.ifPresent(entity -> {
            entity.delete(at, pointRemoved);
            flushChecked();
        });
        return found.isPresent();
    }

    @Override
    public int pointsRemoved(Collection<UUID> ids, Instant at) {
        if (ids.isEmpty()) {
            return 0;
        }
        List<RecordEntity> found = em().createQuery(DELETED_BY_IDS, RecordEntity.class).setParameter("ids", ids)
                .getResultList();
        found.forEach(entity -> entity.pointRemoved(at));
        flushChecked();
        return found.size();
    }

    /**
     * The record, locked for writing until the transaction ends. An entity the session already holds (read before the
     * lock) is read again under the lock, so its version is the locked row's; a version that moved is stale.
     */
    private Optional<RecordEntity> locked(UUID id) {
        try {
            EntityManager em = em();
            Optional<RecordEntity> found = em.createQuery(BY_ID, RecordEntity.class).setParameter("id", id)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList().stream().findFirst();
            found.ifPresent(entity -> em.refresh(entity, LockModeType.PESSIMISTIC_WRITE));
            return found;
        } catch (RuntimeException refused) {
            throw JpaKnowledgeStore.conflictOf(refused).orElseThrow(() -> refused);
        }
    }

    private void flushChecked() {
        try {
            em().flush();
        } catch (RuntimeException refused) {
            throw JpaKnowledgeStore.conflictOf(refused).orElseThrow(() -> refused);
        }
    }

    private List<RecordRow> withLinks(List<RecordEntity> found) {
        Map<UUID, List<UUID>> links = links(found);
        return found.stream().map(entity -> entity.row(links.getOrDefault(entity.id(), List.of()))).toList();
    }

    /** Removes the records' links, then the records themselves; answers how many records went. */
    private int delete(List<RecordEntity> found) {
        if (found.isEmpty()) {
            return 0;
        }
        EntityManager em = em();
        linkEntities(found).forEach(em::remove);
        em.flush();
        found.forEach(em::remove);
        em.flush();
        return found.size();
    }

    private Map<UUID, List<UUID>> links(List<RecordEntity> records) {
        Map<UUID, List<UUID>> links = new LinkedHashMap<>();
        linkEntities(records).forEach(link -> links.computeIfAbsent(link.recordId(), id -> new ArrayList<>())
                .add(link.passageId()));
        return links;
    }

    private List<RecordPassageEntity> linkEntities(List<RecordEntity> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        return em().createQuery(LINKS, RecordPassageEntity.class)
                .setParameter("records", records.stream().map(RecordEntity::id).toList()).getResultList();
    }

    @Override
    public void insertArtifact(ArtifactRow row) {
        EntityManager em = em();
        em.persist(new RecordEntity(row));
        flushChecked();
    }

    @Override
    public Optional<ArtifactRow> findArtifact(UUID campaignId, UUID id) {
        return em().createQuery(ARTIFACT, RecordEntity.class).setParameter("campaign", campaignId)
                .setParameter("id", id).getResultList().stream().findFirst().map(RecordEntity::artifact);
    }

    @Override
    public Optional<ArtifactRow> retypeArtifact(UUID id, String type) {
        Optional<RecordEntity> found = lockedArtifact(id);
        found.ifPresent(entity -> {
            entity.retype(type);
            flushChecked();
        });
        return found.map(RecordEntity::artifact);
    }

    @Override
    public boolean markArtifactDeleting(UUID id, Instant at) {
        Optional<RecordEntity> found = lockedArtifact(id);
        found.ifPresent(entity -> {
            entity.markDeleting(at);
            flushChecked();
        });
        return found.isPresent();
    }

    /**
     * The live artifact, locked for writing until the transaction ends (Story 5.2), read again under the lock as
     * {@link #locked} does; empty for a deleting one (Story 5.2 part B).
     */
    private Optional<RecordEntity> lockedArtifact(UUID id) {
        try {
            EntityManager em = em();
            Optional<RecordEntity> found = em.createQuery(ARTIFACT_BY_ID, RecordEntity.class).setParameter("id", id)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList().stream().findFirst();
            found.ifPresent(entity -> em.refresh(entity, LockModeType.PESSIMISTIC_WRITE));
            return found.filter(entity -> !entity.deleted());
        } catch (RuntimeException refused) {
            throw JpaKnowledgeStore.conflictOf(refused).orElseThrow(() -> refused);
        }
    }

    @Override
    public List<ArtifactRow> findArtifacts(Collection<UUID> ids, Reach reach) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String query = reach == Reach.ALL ? ARTIFACTS_FOUND : LIVE_ARTIFACTS_FOUND;
        return em().createQuery(query, RecordEntity.class).setParameter("ids", List.copyOf(ids))
                .getResultList().stream().map(RecordEntity::artifact).toList();
    }

    @Override
    public List<ArtifactRow> artifacts(UUID campaignId, String type, UUID before, int offset, int limit) {
        String query;
        if (type == null) {
            query = before == null ? ARTIFACTS : ARTIFACTS_BEFORE;
        } else {
            query = before == null ? ARTIFACTS_OF_TYPE : ARTIFACTS_OF_TYPE_BEFORE;
        }
        TypedQuery<RecordEntity> read = em().createQuery(query, RecordEntity.class).setParameter("campaign", campaignId);
        if (type != null) {
            read.setParameter("type", type);
        }
        if (before != null) {
            read.setParameter("before", before);
        }
        return read.setFirstResult(offset).setMaxResults(limit).getResultList().stream().map(RecordEntity::artifact)
                .toList();
    }

    @Override
    public long artifactCount(UUID campaignId, String type) {
        if (type == null) {
            return em().createQuery(ARTIFACT_COUNT, Long.class).setParameter("campaign", campaignId).getSingleResult();
        }
        return em().createQuery(ARTIFACT_COUNT_OF_TYPE, Long.class).setParameter("campaign", campaignId)
                .setParameter("type", type).getSingleResult();
    }

    @Override
    public long artifactsIn(Collection<UUID> campaignIds) {
        if (campaignIds.isEmpty()) {
            return 0;
        }
        return em().createQuery(ARTIFACTS_IN, Long.class).setParameter("campaigns", campaignIds).getSingleResult();
    }

    @Override
    public long artifactsOfTypeIn(Collection<UUID> campaignIds, String type) {
        if (campaignIds.isEmpty()) {
            return 0;
        }
        return em().createQuery(ARTIFACTS_OF_TYPE_IN, Long.class).setParameter("campaigns", campaignIds)
                .setParameter("type", type).getSingleResult();
    }

    @Override
    public Map<String, Long> artifactTypes(UUID campaignId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        em().createQuery(ARTIFACT_TYPES, Object[].class).setParameter("campaign", campaignId).getResultList()
                .forEach(row -> counts.put((String) row[0], (Long) row[1]));
        return counts;
    }

    @Override
    public List<UUID> artifactIdsOf(Collection<UUID> campaignIds, Reach reach) {
        if (campaignIds.isEmpty()) {
            return List.of();
        }
        String query = reach == Reach.ALL ? ARTIFACT_IDS : LIVE_ARTIFACT_IDS;
        return em().createQuery(query, UUID.class).setParameter("campaigns", campaignIds).getResultList();
    }

    @Override
    public int deleteArtifacts(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        EntityManager em = em();
        List<RecordEntity> found = em.createQuery(ARTIFACTS_BY_IDS, RecordEntity.class).setParameter("ids", ids)
                .getResultList();
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
