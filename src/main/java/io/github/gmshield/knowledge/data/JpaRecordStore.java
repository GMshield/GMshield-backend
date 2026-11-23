package io.github.gmshield.knowledge.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Repository;

/**
 * {@code record} and {@code record_passage} through JPA (Story 4.2), as the library's stores: every read is JPQL, which
 * Hibernate filters by the owner of the work ({@code @TenantId}), and nothing is written by a bulk statement (a delete
 * loads the rows and removes them, links first). The entity manager is looked up on first use, so the unit tests, which
 * start without a database, can still create the application.
 */
@Repository
class JpaRecordStore implements RecordStore {

    private static final String OF_VERSION = "select r from Record r where r.versionId = :version"
            + " order by r.firstPage, r.ordinal, r.id";
    private static final String IN_RANGE = "select r from Record r where r.versionId = :version"
            + " and r.firstPage between :first and :last";
    private static final String LINKS = "select l from RecordPassage l where l.recordId in :records"
            + " order by l.recordId, l.passageId";
    private static final String COUNTS = "select r.recordType, count(r) from Record r where r.versionId in :versions"
            + " group by r.recordType order by r.recordType";

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
        List<RecordEntity> found = em().createQuery(OF_VERSION, RecordEntity.class).setParameter("version", versionId)
                .getResultList();
        Map<UUID, List<UUID>> links = links(found);
        return found.stream().map(entity -> entity.row(links.getOrDefault(entity.id(), List.of()))).toList();
    }

    @Override
    public int deleteRange(UUID versionId, int firstPage, int lastPage) {
        return delete(em().createQuery(IN_RANGE, RecordEntity.class).setParameter("version", versionId)
                .setParameter("first", firstPage).setParameter("last", lastPage).getResultList());
    }

    @Override
    public int deleteOfVersion(UUID versionId) {
        return delete(em().createQuery(OF_VERSION, RecordEntity.class).setParameter("version", versionId)
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
