package io.github.gmshield.knowledge.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * {@code proposal}, {@code term} and {@code term_alias} in memory (Story 4.4), for tests that run without PostgreSQL.
 * Like Hibernate's {@code @TenantId}, it sees the rows of the {@link OwnerContext}'s owner only and refuses to work
 * without one; like the tables, it keeps the name keys (a proposal's per system and material, a term's and an alias's
 * per system) and the versions, refusing a taken name or a stale decision as {@link KnowledgeConflict}. A write undoes
 * itself when its transaction rolls back.
 */
public class InMemoryOntologyStore implements OntologyStore {

    public final Map<UUID, ProposalRow> proposals = new ConcurrentHashMap<>();
    public final Map<UUID, TermRow> terms = new ConcurrentHashMap<>();
    public final Map<UUID, TermAliasRow> aliases = new ConcurrentHashMap<>();

    @Override
    public synchronized void insertProposal(ProposalRow row) {
        owned(row.ownerId());
        if (proposals.containsKey(row.id()) || row.materialId() != null
                && findProposal(row.systemId(), row.materialId(), row.nameFingerprint()).isPresent()) {
            throw new KnowledgeConflict(KnowledgeConflict.Reason.NAME_TAKEN);
        }
        put(proposals, row.id(), row);
    }

    @Override
    public Optional<ProposalRow> findProposal(UUID id) {
        return mine(proposals.values().stream(), ProposalRow::ownerId).filter(row -> row.id().equals(id)).findFirst();
    }

    @Override
    public List<ProposalRow> findProposals(Collection<UUID> ids) {
        return mine(proposals.values().stream(), ProposalRow::ownerId).filter(row -> ids.contains(row.id())).toList();
    }

    @Override
    public Optional<ProposalRow> findProposal(UUID systemId, UUID materialId, byte[] nameFingerprint) {
        return mine(proposals.values().stream(), ProposalRow::ownerId).filter(row -> row.systemId().equals(systemId)
                && Objects.equals(row.materialId(), materialId)
                && Arrays.equals(row.nameFingerprint(), nameFingerprint)).findFirst();
    }

    @Override
    public List<ProposalRow> pendingOfSystem(UUID systemId, int offset, int limit) {
        return page(pendingWhere(row -> row.systemId().equals(systemId)), offset, limit);
    }

    @Override
    public long pendingCountOfSystem(UUID systemId) {
        return pendingWhere(row -> row.systemId().equals(systemId)).count();
    }

    @Override
    public List<ProposalRow> pendingOfMaterial(UUID materialId, int offset, int limit) {
        return page(pendingWhere(row -> materialId.equals(row.materialId())), offset, limit);
    }

    @Override
    public long pendingCountOfMaterial(UUID materialId) {
        return pendingWhere(row -> materialId.equals(row.materialId())).count();
    }

    @Override
    public synchronized Optional<ProposalRow> replaceExamples(UUID id, UUID versionId, byte[] examples) {
        return findProposal(id).filter(ProposalRow::pending).map(row -> {
            ProposalRow after = new ProposalRow(row.id(), row.ownerId(), row.systemId(), row.materialId(),
                    versionId, row.kind(), row.name(), row.nameFingerprint(), row.definition(), examples,
                    row.state(), row.decidedAt(), row.version() + 1, row.createdAt());
            put(proposals, id, after);
            return after;
        });
    }

    @Override
    public synchronized Optional<ProposalRow> decide(UUID id, long version, String state, Instant decidedAt) {
        Optional<ProposalRow> found = findProposal(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ProposalRow row = found.get();
        if (row.version() != version || !row.pending()) {
            throw new KnowledgeConflict(KnowledgeConflict.Reason.STALE_VERSION);
        }
        ProposalRow after = new ProposalRow(row.id(), row.ownerId(), row.systemId(), row.materialId(),
                row.versionId(), row.kind(), row.name(), row.nameFingerprint(), row.definition(), row.examples(), state,
                decidedAt, version + 1, row.createdAt());
        put(proposals, id, after);
        return Optional.of(after);
    }

    @Override
    public synchronized int clearVersion(UUID versionId) {
        int removed = 0;
        for (ProposalRow row : mine(proposals.values().stream(), ProposalRow::ownerId)
                .filter(row -> versionId.equals(row.versionId())).toList()) {
            if (row.pending()) {
                remove(proposals, row.id());
                removed++;
            } else {
                put(proposals, row.id(), detached(row, row.materialId()));
            }
        }
        return removed;
    }

    @Override
    public synchronized int clearMaterial(UUID materialId) {
        int removed = 0;
        for (ProposalRow row : mine(proposals.values().stream(), ProposalRow::ownerId)
                .filter(row -> materialId.equals(row.materialId())).toList()) {
            if (ProposalRow.APPROVED.equals(row.state())) {
                put(proposals, row.id(), detached(row, null));
                continue;
            }
            for (TermAliasRow alias : aliases.values().stream().filter(alias -> row.id().equals(alias.proposalId()))
                    .toList()) {
                put(aliases, alias.id(), new TermAliasRow(alias.id(), alias.ownerId(), alias.systemId(),
                        alias.termId(), alias.name(), alias.nameFingerprint(), null, alias.createdAt()));
            }
            remove(proposals, row.id());
            removed++;
        }
        return removed;
    }

    @Override
    public synchronized void deleteProposalsOfSystem(UUID systemId) {
        mine(proposals.values().stream(), ProposalRow::ownerId).filter(row -> row.systemId().equals(systemId)).toList()
                .forEach(row -> remove(proposals, row.id()));
    }

    @Override
    public synchronized void insertTerm(TermRow row) {
        owned(row.ownerId());
        if (terms.containsKey(row.id()) || mine(terms.values().stream(), TermRow::ownerId).anyMatch(other ->
                other.systemId().equals(row.systemId()) && Arrays.equals(other.nameFingerprint(), row.nameFingerprint()))) {
            throw new KnowledgeConflict(KnowledgeConflict.Reason.NAME_TAKEN);
        }
        if (findProposal(row.proposalId()).filter(proposal -> proposal.systemId().equals(row.systemId())).isEmpty()) {
            throw new IllegalStateException("proposal key refused");
        }
        put(terms, row.id(), row);
    }

    @Override
    public Optional<TermRow> findTerm(UUID id) {
        return mine(terms.values().stream(), TermRow::ownerId).filter(row -> row.id().equals(id)).findFirst();
    }

    @Override
    public List<TermRow> termsOf(UUID systemId, int offset, int limit) {
        return mine(terms.values().stream(), TermRow::ownerId).filter(row -> row.systemId().equals(systemId))
                .sorted(Comparator.comparing(TermRow::id)).skip(Math.max(offset, 0)).limit(Math.max(limit, 0))
                .toList();
    }

    @Override
    public long termCount(UUID systemId) {
        return mine(terms.values().stream(), TermRow::ownerId).filter(row -> row.systemId().equals(systemId)).count();
    }

    @Override
    public synchronized void insertAlias(TermAliasRow row) {
        owned(row.ownerId());
        if (aliases.containsKey(row.id()) || mine(aliases.values().stream(), TermAliasRow::ownerId).anyMatch(other ->
                other.systemId().equals(row.systemId()) && Arrays.equals(other.nameFingerprint(), row.nameFingerprint()))) {
            throw new KnowledgeConflict(KnowledgeConflict.Reason.NAME_TAKEN);
        }
        if (findTerm(row.termId()).filter(term -> term.systemId().equals(row.systemId())).isEmpty()) {
            throw new IllegalStateException("term key refused");
        }
        put(aliases, row.id(), row);
    }

    @Override
    public List<TermAliasRow> aliasesOf(Collection<UUID> termIds) {
        return mine(aliases.values().stream(), TermAliasRow::ownerId).filter(row -> termIds.contains(row.termId()))
                .sorted(Comparator.comparing(TermAliasRow::id)).toList();
    }

    @Override
    public List<TermRow> findTerms(Collection<UUID> ids) {
        return mine(terms.values().stream(), TermRow::ownerId).filter(row -> ids.contains(row.id()))
                .sorted(Comparator.comparing(TermRow::id)).toList();
    }

    @Override
    public List<TermRow> termsNamed(UUID systemId, Collection<byte[]> nameFingerprints) {
        return mine(terms.values().stream(), TermRow::ownerId).filter(row -> row.systemId().equals(systemId)
                        && nameFingerprints.stream().anyMatch(one -> Arrays.equals(one, row.nameFingerprint())))
                .sorted(Comparator.comparing(TermRow::id)).toList();
    }

    @Override
    public List<TermAliasRow> aliasesNamed(UUID systemId, Collection<byte[]> nameFingerprints) {
        return mine(aliases.values().stream(), TermAliasRow::ownerId).filter(row -> row.systemId().equals(systemId)
                        && nameFingerprints.stream().anyMatch(one -> Arrays.equals(one, row.nameFingerprint())))
                .sorted(Comparator.comparing(TermAliasRow::id)).toList();
    }

    @Override
    public boolean termNameTaken(UUID systemId, byte[] nameFingerprint) {
        return mine(terms.values().stream(), TermRow::ownerId).anyMatch(row -> row.systemId().equals(systemId)
                && Arrays.equals(row.nameFingerprint(), nameFingerprint))
                || mine(aliases.values().stream(), TermAliasRow::ownerId).anyMatch(row ->
                row.systemId().equals(systemId) && Arrays.equals(row.nameFingerprint(), nameFingerprint));
    }

    @Override
    public synchronized void deleteTermsOfSystem(UUID systemId) {
        mine(aliases.values().stream(), TermAliasRow::ownerId).filter(row -> row.systemId().equals(systemId)).toList()
                .forEach(row -> remove(aliases, row.id()));
        mine(terms.values().stream(), TermRow::ownerId).filter(row -> row.systemId().equals(systemId)).toList()
                .forEach(row -> remove(terms, row.id()));
    }

    private Stream<ProposalRow> pendingWhere(java.util.function.Predicate<ProposalRow> filter) {
        return mine(proposals.values().stream(), ProposalRow::ownerId).filter(ProposalRow::pending).filter(filter)
                .sorted(Comparator.comparing(ProposalRow::id));
    }

    private static List<ProposalRow> page(Stream<ProposalRow> rows, int offset, int limit) {
        return rows.skip(Math.max(offset, 0)).limit(Math.max(limit, 0)).toList();
    }

    private static ProposalRow detached(ProposalRow row, UUID materialId) {
        return new ProposalRow(row.id(), row.ownerId(), row.systemId(), materialId, null, row.kind(), row.name(),
                row.nameFingerprint(), row.definition(), row.examples(), row.state(), row.decidedAt(), row.version(),
                row.createdAt());
    }

    private static <T> Stream<T> mine(Stream<T> rows, java.util.function.Function<T, UUID> owner) {
        UUID current = OwnerContext.require();
        return rows.filter(row -> owner.apply(row).equals(current));
    }

    private static void owned(UUID ownerId) {
        if (!ownerId.equals(OwnerContext.require())) {
            throw new IllegalStateException("owner refused");
        }
    }

    private static <T> void put(Map<UUID, T> rows, UUID id, T row) {
        T before = rows.put(id, row);
        RollbackUndo.onRollback(() -> {
            if (before == null) {
                rows.remove(id);
            } else {
                rows.put(id, before);
            }
        });
    }

    private static <T> void remove(Map<UUID, T> rows, UUID id) {
        T before = rows.remove(id);
        if (before != null) {
            RollbackUndo.onRollback(() -> rows.put(id, before));
        }
    }
}
