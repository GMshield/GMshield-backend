package io.github.gmshield.knowledge.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
 * {@code proposal}, {@code term} and {@code term_alias} through JPA (Story 4.4), as the other knowledge stores: every
 * read is JPQL, which Hibernate filters by the owner of the work ({@code @TenantId}); a read by id is JPQL too, never
 * {@code find}, and nothing is written by a bulk statement. A write that a name key refuses is a
 * {@link KnowledgeConflict} ({@code NAME_TAKEN}), and a decision on a proposal whose version moved (or that is no longer
 * pending) is one too ({@code STALE_VERSION}), never the database's message.
 */
@Repository
class JpaOntologyStore implements OntologyStore {

    static final Set<String> NAME_KEYS = Set.of("proposal_owner_system_material_name_key",
            "term_owner_system_name_fingerprint_key", "term_alias_owner_system_name_fingerprint_key");
    private static final String PROPOSAL = "select p from Proposal p where p.id = :id";
    private static final String PROPOSALS = "select p from Proposal p where p.id in :ids";
    private static final String PROPOSAL_NAMED = "select p from Proposal p where p.systemId = :system"
            + " and p.materialId = :material and p.nameFingerprint = :fingerprint";
    private static final String PENDING_OF_SYSTEM = "select p from Proposal p where p.systemId = :system"
            + " and p.state = 'pending' order by p.id";
    private static final String PENDING_COUNT_OF_SYSTEM = "select count(p) from Proposal p where p.systemId = :system"
            + " and p.state = 'pending'";
    private static final String PENDING_OF_MATERIAL = "select p from Proposal p where p.materialId = :material"
            + " and p.state = 'pending' order by p.id";
    private static final String PENDING_COUNT_OF_MATERIAL = "select count(p) from Proposal p"
            + " where p.materialId = :material and p.state = 'pending'";
    private static final String OF_VERSION = "select p from Proposal p where p.versionId = :version";
    private static final String OF_MATERIAL = "select p from Proposal p where p.materialId = :material";
    private static final String OF_SYSTEM = "select p from Proposal p where p.systemId = :system";
    private static final String ALIASES_OF_PROPOSALS = "select a from TermAlias a where a.proposalId in :ids";
    private static final String TERM = "select t from Term t where t.id = :id";
    private static final String TERMS_OF_SYSTEM = "select t from Term t where t.systemId = :system order by t.id";
    private static final String TERM_COUNT = "select count(t) from Term t where t.systemId = :system";
    private static final String TERM_NAMED = "select t.id from Term t where t.systemId = :system"
            + " and t.nameFingerprint = :fingerprint";
    private static final String ALIAS_NAMED = "select a.id from TermAlias a where a.systemId = :system"
            + " and a.nameFingerprint = :fingerprint";
    private static final String ALIASES_OF_TERMS = "select a from TermAlias a where a.termId in :terms order by a.id";
    private static final String ALIASES_OF_SYSTEM = "select a from TermAlias a where a.systemId = :system";
    private static final String TERMS_BY_IDS = "select t from Term t where t.id in :ids order by t.id";
    private static final String TERMS_NAMED_ANY = "select t from Term t where t.systemId = :system"
            + " and t.nameFingerprint in :fingerprints order by t.id";
    private static final String ALIASES_NAMED_ANY = "select a from TermAlias a where a.systemId = :system"
            + " and a.nameFingerprint in :fingerprints order by a.id";

    private final ObjectProvider<EntityManagerFactory> factory;
    private volatile EntityManager shared;

    JpaOntologyStore(ObjectProvider<EntityManagerFactory> factory) {
        this.factory = factory;
    }

    @Override
    public void insertProposal(ProposalRow row) {
        EntityManager em = em();
        em.persist(new ProposalEntity(row));
        flushChecked(em);
    }

    @Override
    public Optional<ProposalRow> findProposal(UUID id) {
        return proposal(id, LockModeType.NONE).map(ProposalEntity::row);
    }

    @Override
    public List<ProposalRow> findProposals(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return em().createQuery(PROPOSALS, ProposalEntity.class).setParameter("ids", Set.copyOf(ids)).getResultList()
                .stream().map(ProposalEntity::row).toList();
    }

    @Override
    public Optional<ProposalRow> findProposal(UUID systemId, UUID materialId, byte[] nameFingerprint) {
        return em().createQuery(PROPOSAL_NAMED, ProposalEntity.class).setParameter("system", systemId)
                .setParameter("material", materialId).setParameter("fingerprint", nameFingerprint).setMaxResults(1)
                .getResultList().stream().findFirst().map(ProposalEntity::row);
    }

    @Override
    public List<ProposalRow> pendingOfSystem(UUID systemId, int offset, int limit) {
        if (limit < 1) {
            return List.of();
        }
        return em().createQuery(PENDING_OF_SYSTEM, ProposalEntity.class).setParameter("system", systemId)
                .setFirstResult(Math.max(offset, 0)).setMaxResults(limit).getResultList().stream()
                .map(ProposalEntity::row).toList();
    }

    @Override
    public long pendingCountOfSystem(UUID systemId) {
        return em().createQuery(PENDING_COUNT_OF_SYSTEM, Long.class).setParameter("system", systemId).getSingleResult();
    }

    @Override
    public List<ProposalRow> pendingOfMaterial(UUID materialId, int offset, int limit) {
        if (limit < 1) {
            return List.of();
        }
        return em().createQuery(PENDING_OF_MATERIAL, ProposalEntity.class).setParameter("material", materialId)
                .setFirstResult(Math.max(offset, 0)).setMaxResults(limit).getResultList().stream()
                .map(ProposalEntity::row).toList();
    }

    @Override
    public long pendingCountOfMaterial(UUID materialId) {
        return em().createQuery(PENDING_COUNT_OF_MATERIAL, Long.class).setParameter("material", materialId)
                .getSingleResult();
    }

    @Override
    public Optional<ProposalRow> replaceExamples(UUID id, UUID versionId, byte[] examples) {
        Optional<ProposalEntity> found = proposal(id, LockModeType.PESSIMISTIC_WRITE)
                .filter(entity -> ProposalRow.PENDING.equals(entity.state()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        found.get().examples(versionId, examples);
        flushChecked(em());
        return Optional.of(found.get().row());
    }

    @Override
    public Optional<ProposalRow> decide(UUID id, long version, String state, Instant decidedAt) {
        Optional<ProposalEntity> found;
        try {
            found = proposal(id, LockModeType.PESSIMISTIC_WRITE);
        } catch (RuntimeException refused) {
            throw conflictOf(refused).orElseThrow(() -> refused);
        }
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ProposalEntity entity = found.get();
        if (entity.version() != version || !ProposalRow.PENDING.equals(entity.state())) {
            throw new KnowledgeConflict(KnowledgeConflict.Reason.STALE_VERSION);
        }
        entity.decide(state, decidedAt);
        flushChecked(em());
        return Optional.of(entity.row());
    }

    @Override
    public int clearVersion(UUID versionId) {
        EntityManager em = em();
        int removed = 0;
        for (ProposalEntity entity : em.createQuery(OF_VERSION, ProposalEntity.class).setParameter("version", versionId)
                .getResultList()) {
            if (ProposalRow.PENDING.equals(entity.state())) {
                em.remove(entity);
                removed++;
            } else {
                entity.detachVersion();
            }
        }
        em.flush();
        return removed;
    }

    @Override
    public int clearMaterial(UUID materialId) {
        EntityManager em = em();
        List<ProposalEntity> going = new ArrayList<>();
        for (ProposalEntity entity : em.createQuery(OF_MATERIAL, ProposalEntity.class)
                .setParameter("material", materialId).getResultList()) {
            if (ProposalRow.APPROVED.equals(entity.state())) {
                entity.detachMaterial();
            } else {
                going.add(entity);
            }
        }
        if (!going.isEmpty()) {
            em.createQuery(ALIASES_OF_PROPOSALS, TermAliasEntity.class)
                    .setParameter("ids", going.stream().map(entity -> entity.row().id()).toList()).getResultList()
                    .forEach(TermAliasEntity::dropProposal);
            // The aliases first: their proposal key names the proposals that go.
            em.flush();
            going.forEach(em::remove);
        }
        em.flush();
        return going.size();
    }

    @Override
    public void deleteProposalsOfSystem(UUID systemId) {
        EntityManager em = em();
        em.createQuery(OF_SYSTEM, ProposalEntity.class).setParameter("system", systemId).getResultList()
                .forEach(em::remove);
        em.flush();
    }

    @Override
    public void insertTerm(TermRow row) {
        EntityManager em = em();
        em.persist(new TermEntity(row));
        flushChecked(em);
    }

    @Override
    public Optional<TermRow> findTerm(UUID id) {
        return em().createQuery(TERM, TermEntity.class).setParameter("id", id).getResultList().stream().findFirst()
                .map(TermEntity::row);
    }

    @Override
    public List<TermRow> termsOf(UUID systemId, int offset, int limit) {
        if (limit < 1) {
            return List.of();
        }
        return em().createQuery(TERMS_OF_SYSTEM, TermEntity.class).setParameter("system", systemId)
                .setFirstResult(Math.max(offset, 0)).setMaxResults(limit).getResultList().stream()
                .map(TermEntity::row).toList();
    }

    @Override
    public long termCount(UUID systemId) {
        return em().createQuery(TERM_COUNT, Long.class).setParameter("system", systemId).getSingleResult();
    }

    @Override
    public void insertAlias(TermAliasRow row) {
        EntityManager em = em();
        em.persist(new TermAliasEntity(row));
        flushChecked(em);
    }

    @Override
    public List<TermAliasRow> aliasesOf(Collection<UUID> termIds) {
        if (termIds.isEmpty()) {
            return List.of();
        }
        return em().createQuery(ALIASES_OF_TERMS, TermAliasEntity.class).setParameter("terms", Set.copyOf(termIds))
                .getResultList().stream().map(TermAliasEntity::row).toList();
    }

    @Override
    public List<TermRow> findTerms(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return em().createQuery(TERMS_BY_IDS, TermEntity.class).setParameter("ids", Set.copyOf(ids)).getResultList()
                .stream().map(TermEntity::row).toList();
    }

    @Override
    public List<TermRow> termsNamed(UUID systemId, Collection<byte[]> nameFingerprints) {
        Map<UUID, TermRow> found = new TreeMap<>();
        for (List<byte[]> chunk : chunks(nameFingerprints)) {
            em().createQuery(TERMS_NAMED_ANY, TermEntity.class).setParameter("system", systemId)
                    .setParameter("fingerprints", chunk).getResultList().stream().map(TermEntity::row)
                    .forEach(row -> found.putIfAbsent(row.id(), row));
        }
        return List.copyOf(found.values());
    }

    @Override
    public List<TermAliasRow> aliasesNamed(UUID systemId, Collection<byte[]> nameFingerprints) {
        Map<UUID, TermAliasRow> found = new TreeMap<>();
        for (List<byte[]> chunk : chunks(nameFingerprints)) {
            em().createQuery(ALIASES_NAMED_ANY, TermAliasEntity.class).setParameter("system", systemId)
                    .setParameter("fingerprints", chunk).getResultList().stream().map(TermAliasEntity::row)
                    .forEach(row -> found.putIfAbsent(row.id(), row));
        }
        return List.copyOf(found.values());
    }

    /** The fingerprints in lists of at most {@link JpaRecordStore#FINGERPRINTS_PER_QUERY}, one query each. */
    private static List<List<byte[]>> chunks(Collection<byte[]> nameFingerprints) {
        List<byte[]> all = List.copyOf(nameFingerprints);
        List<List<byte[]>> chunks = new ArrayList<>();
        for (int from = 0; from < all.size(); from += JpaRecordStore.FINGERPRINTS_PER_QUERY) {
            chunks.add(all.subList(from, Math.min(all.size(), from + JpaRecordStore.FINGERPRINTS_PER_QUERY)));
        }
        return chunks;
    }

    @Override
    public boolean termNameTaken(UUID systemId, byte[] nameFingerprint) {
        return named(TERM_NAMED, systemId, nameFingerprint) || named(ALIAS_NAMED, systemId, nameFingerprint);
    }

    @Override
    public void deleteTermsOfSystem(UUID systemId) {
        EntityManager em = em();
        em.createQuery(ALIASES_OF_SYSTEM, TermAliasEntity.class).setParameter("system", systemId).getResultList()
                .forEach(em::remove);
        em.flush();
        em.createQuery(TERMS_OF_SYSTEM, TermEntity.class).setParameter("system", systemId).getResultList()
                .forEach(em::remove);
        em.flush();
    }

    private boolean named(String query, UUID systemId, byte[] nameFingerprint) {
        return !em().createQuery(query, UUID.class).setParameter("system", systemId)
                .setParameter("fingerprint", nameFingerprint).setMaxResults(1).getResultList().isEmpty();
    }

    private Optional<ProposalEntity> proposal(UUID id, LockModeType lock) {
        return em().createQuery(PROPOSAL, ProposalEntity.class).setParameter("id", id).setLockMode(lock)
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

    /** The conflict a failed write stands for, if it stands for one. */
    static Optional<KnowledgeConflict> conflictOf(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof OptimisticLockException || cause instanceof StaleStateException) {
                return Optional.of(new KnowledgeConflict(KnowledgeConflict.Reason.STALE_VERSION));
            }
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null
                    && NAME_KEYS.contains(violation.getConstraintName().toLowerCase(java.util.Locale.ROOT))) {
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
