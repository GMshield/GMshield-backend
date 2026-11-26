package io.github.gmshield.knowledge.data;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code proposal}, {@code term} and {@code term_alias} (Story 4.4, V39), always within the owner of the work: every
 * read sees that owner's rows only, and an insert for another owner is refused. Lookups are exact, by id or by key:
 * there is no text search. Writes join the caller's transaction, which must exist. The store does not know whether a
 * system is ready or being deleted, nor a material's: the service checks them first.
 */
public interface OntologyStore {

    // --- proposals

    /**
     * Stores a new proposal of the current owner. Throws {@link KnowledgeConflict} ({@code NAME_TAKEN}) when the system
     * already has a proposal of that material with this name fingerprint.
     */
    void insertProposal(ProposalRow row);

    /** The current owner's proposal with this id; empty for an unknown id or another owner's. */
    Optional<ProposalRow> findProposal(UUID id);

    /** The current owner's proposals with these ids, in no order; unknown ids are left out. */
    List<ProposalRow> findProposals(Collection<UUID> ids);

    /** The current owner's proposal of the system and material with this name fingerprint, whatever its state. */
    Optional<ProposalRow> findProposal(UUID systemId, UUID materialId, byte[] nameFingerprint);

    /** The current owner's pending proposals of the system, oldest first, the {@code offset} first ones skipped. */
    List<ProposalRow> pendingOfSystem(UUID systemId, int offset, int limit);

    long pendingCountOfSystem(UUID systemId);

    /** The current owner's pending proposals of the material, oldest first, the {@code offset} first ones skipped. */
    List<ProposalRow> pendingOfMaterial(UUID materialId, int offset, int limit);

    long pendingCountOfMaterial(UUID materialId);

    /**
     * The pending proposal {@code id}'s examples become {@code examples} and its material version {@code versionId}
     * (its version rises by one); empty for an unknown id, another owner's or one no longer pending.
     */
    Optional<ProposalRow> replaceExamples(UUID id, UUID versionId, byte[] examples);

    /**
     * Decides the current owner's proposal {@code id} on {@code version}: it takes {@code state} and {@code decidedAt},
     * and its version rises by one; answers it as stored, empty for an unknown id or another owner's. Throws
     * {@link KnowledgeConflict} ({@code STALE_VERSION}) when it is not at {@code version} or no longer pending.
     */
    Optional<ProposalRow> decide(UUID id, long version, String state, Instant decidedAt);

    /**
     * The version's pending proposals go, and the others no longer name the version; answers how many went. Safe to
     * repeat.
     */
    int clearVersion(UUID versionId);

    /**
     * The material's proposals that were not approved go (an alias merged from one keeps its term and loses its
     * proposal), and the approved ones no longer name the material or a version; answers how many went.
     */
    int clearMaterial(UUID materialId);

    /** Deletes every proposal of the current owner's system; safe to repeat. Terms and types that name them go first. */
    void deleteProposalsOfSystem(UUID systemId);

    // --- terms and their aliases

    /**
     * Stores a new term of the current owner. Throws {@link KnowledgeConflict} ({@code NAME_TAKEN}) when another term of
     * the system has its name fingerprint.
     */
    void insertTerm(TermRow row);

    /** The current owner's term with this id; empty for an unknown id or another owner's. */
    Optional<TermRow> findTerm(UUID id);

    /** The current owner's terms of the system, oldest first (ascending id), the {@code offset} first ones skipped. */
    List<TermRow> termsOf(UUID systemId, int offset, int limit);

    long termCount(UUID systemId);

    /**
     * Stores a new alias of the current owner. Throws {@link KnowledgeConflict} ({@code NAME_TAKEN}) when another alias
     * of the system has its name fingerprint.
     */
    void insertAlias(TermAliasRow row);

    /** The current owner's aliases of these terms, oldest first. */
    List<TermAliasRow> aliasesOf(Collection<UUID> termIds);

    /**
     * The current owner's terms with these ids (Story 4.6), oldest first (ascending id); unknown ids are left out.
     */
    List<TermRow> findTerms(Collection<UUID> ids);

    /**
     * The current owner's terms of the system whose name fingerprint is one of {@code nameFingerprints} (Story 4.6: a
     * fingerprint-IN equality, never a text search), oldest first.
     */
    List<TermRow> termsNamed(UUID systemId, Collection<byte[]> nameFingerprints);

    /**
     * The current owner's aliases of the system whose name fingerprint is one of {@code nameFingerprints} (Story 4.6),
     * oldest first.
     */
    List<TermAliasRow> aliasesNamed(UUID systemId, Collection<byte[]> nameFingerprints);

    /** Whether a term or an alias of the current owner's system has this name fingerprint. */
    boolean termNameTaken(UUID systemId, byte[] nameFingerprint);

    /** Deletes every alias, then every term, of the current owner's system; safe to repeat. */
    void deleteTermsOfSystem(UUID systemId);
}
