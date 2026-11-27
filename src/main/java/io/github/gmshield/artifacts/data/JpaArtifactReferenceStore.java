package io.github.gmshield.artifacts.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.stereotype.Repository;

/**
 * {@code artifact_reference} through JPA (Story 5.4 part A), as {@link JpaArtifactVersionStore}: every read is JPQL,
 * which Hibernate filters by the owner of the work ({@code @TenantId}), and a delete loads the rows and removes them.
 * The entity manager is looked up on first use.
 */
@Repository
class JpaArtifactReferenceStore implements ArtifactReferenceStore {

    private static final String OF = "select r from ArtifactReference r where r.recordId = :record order by r.number";
    private static final String ALL_OF = "select r from ArtifactReference r where r.recordId in :records";

    private final ObjectProvider<EntityManagerFactory> factory;
    private volatile EntityManager shared;

    JpaArtifactReferenceStore(ObjectProvider<EntityManagerFactory> factory) {
        this.factory = factory;
    }

    @Override
    public void insert(List<ArtifactReferenceRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        EntityManager em = em();
        rows.forEach(row -> em.persist(new ArtifactReferenceEntity(row)));
        em.flush();
    }

    @Override
    public List<ArtifactReferenceRow> of(UUID recordId) {
        return em().createQuery(OF, ArtifactReferenceEntity.class).setParameter("record", recordId).getResultList()
                .stream().map(ArtifactReferenceEntity::row).toList();
    }

    @Override
    public int deleteOf(Collection<UUID> recordIds) {
        if (recordIds.isEmpty()) {
            return 0;
        }
        EntityManager em = em();
        List<ArtifactReferenceEntity> found = em.createQuery(ALL_OF, ArtifactReferenceEntity.class)
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
