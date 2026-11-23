package io.github.gmshield.knowledge.service;

import io.github.gmshield.knowledge.data.KnowledgeConflict;
import io.github.gmshield.knowledge.data.KnowledgeStore;
import io.github.gmshield.knowledge.data.OntologyStore;
import io.github.gmshield.knowledge.data.ProposalRow;
import io.github.gmshield.knowledge.data.RecordTypeRow;
import io.github.gmshield.knowledge.data.TermAliasRow;
import io.github.gmshield.knowledge.data.TermRow;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.service.MaterialNames;
import io.github.gmshield.materials.service.OntologyProposals;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The record types and terms a material proposes, and the GM's decisions on them (Story 4.4), for the owner of the work
 * ({@link OwnerContext}). A proposal's name, definition and examples are encrypted for the owner, bound to the entity
 * {@value #PROPOSAL}, their field and the row; its name's fingerprint is the system's one name space's
 * ({@link NameSpace}).
 *
 * <p>For the {@code materials} module ({@link OntologyProposals}), in the caller's transaction: {@link #store} keeps a
 * records step's checked proposals per system, dropping those that name something the system has and those already
 * present for the same material (whatever their state, so a rejected one is never raised again), a repeat within the
 * same version adding its examples up to {@value #MAX_EXAMPLES}; {@link #clearPending} and {@link #removeMaterial}
 * follow a version's and a material's removal.
 *
 * <p>For the GM, each in one transaction with the system's ontology locked: a proposal is approved (as proposed, or
 * under another name) into a record type, whose {@code key} is a slug of the approved name that never changes, or into
 * a term with its quoted definition; merged into a record type of its system (it only closes) or a term of its system
 * (its name becomes an alias of that term); or rejected. Each names the version the proposal was read at
 * ({@code request.stale_version}, 409, when it moved or was decided meanwhile); a taken name is
 * {@code ontology.name_taken} (409), a name outside 1 to {@value Ontology#NAME_MAX} code points
 * {@code ontology.name_invalid} (400), a merge target that is not of the proposal's kind and system
 * {@code ontology.merge_target_invalid} (400). A proposal of a system that is not the owner's, unknown or being deleted
 * is "not found". Logs carry ids and counts only.
 */
@Service
public class Proposals implements OntologyProposals {

    static final String PROPOSAL = "proposal";
    static final String TERM_ENTITY = "term";
    static final String ALIAS_ENTITY = "term_alias";
    static final String FIELD_DEFINITION = "definition";
    static final String FIELD_EXAMPLES = "examples";
    /** A proposal's examples, at most. */
    static final int MAX_EXAMPLES = 3;
    /** An approved type's key: a slug of at most {@value #KEY_BASE} characters, then a suffix when it is taken. */
    static final int KEY_BASE = 28;
    static final String KEY_FALLBACK = "type";
    /** The suffixes a key may take, {@code _2} to {@code _999}: a base of {@value #KEY_BASE} and one stays within 32. */
    private static final int KEY_TRIES = 999;
    private static final Logger log = LoggerFactory.getLogger(Proposals.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OntologyStore store;
    private final KnowledgeStore types;
    private final NameSpace names;
    private final Vault vault;
    private final Library library;
    private final MaterialNames materials;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final Clock clock;

    Proposals(OntologyStore store, KnowledgeStore types, NameSpace names, Vault vault, Library library,
              MaterialNames materials, ObjectProvider<PlatformTransactionManager> transactions,
              ObjectProvider<Clock> clock) {
        this.store = store;
        this.types = types;
        this.names = names;
        this.vault = vault;
        this.library = library;
        this.materials = materials;
        this.transactions = transactions;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** One proposal as the GM sees it. {@code toString} names ids, the kind, the state and the version only. */
    public record Proposal(UUID id, UUID systemId, UUID materialId, String materialName, String kind, String name,
                           String definition, List<Example> examples, String state, long version) {
        public Proposal {
            examples = List.copyOf(examples);
        }

        @Override
        public String toString() {
            return "Proposal[id=" + id + ", systemId=" + systemId + ", materialId=" + materialId + ", kind=" + kind
                    + ", state=" + state + ", version=" + version + "]";
        }
    }

    /** One page of pending proposals and how many there are in all. */
    public record ProposalPage(List<Proposal> items, long total) {
    }

    /** A decision's answer: the proposal as stored, and the record type or term it made or merged into. */
    public record Decision(Proposal proposal, UUID resultId) {
    }

    // --- for the materials module

    @Override
    public Outcome store(UUID materialId, UUID versionId, Collection<UUID> systemIds, List<Proposed> proposals) {
        UUID owner = requireTransaction();
        Map<String, Proposed> distinct = distinct(proposals);
        Instant now = now();
        int stored = 0;
        int extended = 0;
        int dropped = proposals.size() - distinct.size();
        for (UUID systemId : Set.copyOf(systemIds)) {
            for (Proposed proposed : distinct.values()) {
                if (names.taken(systemId, proposed.name(), NameSpace.NONE)) {
                    dropped++;
                    continue;
                }
                byte[] fingerprint = names.fingerprint(proposed.name());
                Optional<ProposalRow> present = store.findProposal(systemId, materialId, fingerprint);
                if (present.isPresent()) {
                    if (extend(present.get(), versionId, proposed.examples())) {
                        extended++;
                    } else {
                        dropped++;
                    }
                    continue;
                }
                UUID id = Ids.newId();
                store.insertProposal(new ProposalRow(id, owner, systemId, materialId, versionId, proposed.kind(),
                        vault.encryptText(binding(id, owner, Ontology.FIELD_NAME), proposed.name()), fingerprint,
                        proposed.definition() == null ? null
                                : vault.encryptText(binding(id, owner, FIELD_DEFINITION), proposed.definition()),
                        vault.encrypt(binding(id, owner, FIELD_EXAMPLES), examplesJson(proposed.examples())),
                        ProposalRow.PENDING, null, 0, now));
                stored++;
            }
        }
        return new Outcome(stored, extended, dropped);
    }

    @Override
    public int clearPending(UUID versionId) {
        requireTransaction();
        return store.clearVersion(versionId);
    }

    @Override
    public int removeMaterial(UUID materialId) {
        requireTransaction();
        return store.clearMaterial(materialId);
    }

    /**
     * The proposals by unique form of their name, the first of each kept, the later ones' examples added to it up to
     * {@value #MAX_EXAMPLES}.
     */
    private static Map<String, Proposed> distinct(List<Proposed> proposals) {
        Map<String, Proposed> found = new LinkedHashMap<>();
        for (Proposed proposed : proposals) {
            String form = Library.uniqueForm(proposed.name());
            Proposed first = found.get(form);
            found.put(form, first == null ? proposed : new Proposed(first.kind(), first.name(), first.definition(),
                    joined(first.examples(), proposed.examples())));
        }
        return found;
    }

    /**
     * A repeat of a stored proposal: when that one is pending and of the same version, the repeat's new examples join
     * its own up to {@value #MAX_EXAMPLES}; when it is pending and of another version (a superseded one, which would
     * take it along when it goes), it moves to this version with the repeat's examples. True when it changed.
     */
    private boolean extend(ProposalRow present, UUID versionId, List<Example> more) {
        if (!present.pending()) {
            return false;
        }
        List<Example> joined = more;
        if (versionId.equals(present.versionId())) {
            List<Example> own = examplesOf(vault.decrypt(binding(present.id(), present.ownerId(), FIELD_EXAMPLES),
                    present.examples()));
            joined = joined(own, more);
            if (joined.size() == own.size()) {
                return false;
            }
        }
        return store.replaceExamples(present.id(), versionId, vault.encrypt(binding(present.id(), present.ownerId(),
                FIELD_EXAMPLES), examplesJson(joined))).isPresent();
    }

    /** {@code first} then the examples of {@code more} it does not hold, at most {@value #MAX_EXAMPLES}. */
    private static List<Example> joined(List<Example> first, List<Example> more) {
        List<Example> joined = new ArrayList<>(first);
        for (Example example : more) {
            if (joined.size() >= MAX_EXAMPLES) {
                break;
            }
            if (!joined.contains(example)) {
                joined.add(example);
            }
        }
        return List.copyOf(joined);
    }

    // --- for the GM

    /**
     * The pending proposals of the owner's system {@code systemId}, oldest first, a page as {@link Ontology#recordTypes}
     * takes it; empty for a system that is not the owner's, unknown or being deleted.
     */
    public Optional<ProposalPage> ofSystem(UUID systemId, int offset, int limit) {
        Ontology.checkPage(offset, limit);
        return reading(() -> library.system(systemId).map(system -> new ProposalPage(
                open(store.pendingOfSystem(systemId, offset, limit)), store.pendingCountOfSystem(systemId))));
    }

    /**
     * The pending proposals of the owner's material {@code materialId}, in every system it counts under, oldest first;
     * empty for a material that is not the owner's or unknown. A proposal of a system being deleted is left out.
     */
    public Optional<ProposalPage> ofMaterial(UUID materialId, int offset, int limit) {
        Ontology.checkPage(offset, limit);
        return reading(() -> materials.name(materialId).map(name -> new ProposalPage(
                open(store.pendingOfMaterial(materialId, offset, limit).stream()
                        .filter(row -> library.system(row.systemId()).isPresent()).toList()),
                store.pendingCountOfMaterial(materialId))));
    }

    /**
     * Approves the owner's proposal {@code id} on {@code version}, as the class says, under {@code typedName} (its own
     * name when null); empty for a proposal that is not visible.
     */
    public Optional<Decision> approve(UUID id, long version, String typedName) {
        Optional<Decision> decided = writing(() -> visible(id).map(row -> {
            types.lockOntology(row.systemId());
            current(row, version);
            String name = typedName == null ? text(row, Ontology.FIELD_NAME, row.name()) : NameSpace.valid(typedName);
            Instant now = now();
            // The decision first: another tab's decision committed meanwhile makes this one stale, whatever the name.
            Proposal approved = decide(row, version, ProposalRow.APPROVED, now);
            if (names.taken(row.systemId(), name, NameSpace.NONE)) {
                throw new ApiException(KnowledgeProblems.NAME_TAKEN);
            }
            UUID owner = row.ownerId();
            UUID made = Ids.newId();
            byte[] fingerprint = names.fingerprint(name);
            if (ProposalRow.RECORD_TYPE.equals(row.kind())) {
                conflicts(() -> {
                    types.insertRecordTypes(List.of(new RecordTypeRow(made, owner, row.systemId(), null,
                            freeKey(row.systemId(), name), row.id(), vault.encryptText(new Binding(Ontology.RECORD_TYPE,
                            Ontology.FIELD_NAME, made, owner), name), fingerprint, 0, now)));
                    return null;
                });
            } else {
                String definition = text(row, FIELD_DEFINITION, row.definition());
                conflicts(() -> {
                    store.insertTerm(new TermRow(made, owner, row.systemId(),
                            vault.encryptText(new Binding(TERM_ENTITY, Ontology.FIELD_NAME, made, owner), name),
                            fingerprint, vault.encryptText(new Binding(TERM_ENTITY, FIELD_DEFINITION, made, owner),
                            definition), row.id(), 0, now));
                    return null;
                });
            }
            return new Decision(approved, made);
        }));
        decided.ifPresent(decision -> log.atInfo().addKeyValue("ownerId", OwnerContext.require())
                .addKeyValue("proposalId", id).addKeyValue("kind", decision.proposal().kind())
                .addKeyValue("resultId", decision.resultId()).log("knowledge.proposal_approved"));
        return decided;
    }

    /**
     * Merges the owner's proposal {@code id} on {@code version} into {@code targetId}, as the class says; empty for a
     * proposal that is not visible.
     */
    public Optional<Decision> merge(UUID id, long version, UUID targetId) {
        Optional<Decision> decided = writing(() -> visible(id).map(row -> {
            types.lockOntology(row.systemId());
            current(row, version);
            Instant now = now();
            // The decision first: another tab's decision committed meanwhile makes this one stale, whatever the target.
            Proposal merged = decide(row, version, ProposalRow.MERGED, now);
            if (ProposalRow.RECORD_TYPE.equals(row.kind())) {
                if (targetId == null || types.findRecordType(targetId)
                        .filter(type -> type.systemId().equals(row.systemId())).isEmpty()) {
                    throw new ApiException(KnowledgeProblems.MERGE_TARGET_INVALID);
                }
            } else {
                TermRow term = (targetId == null ? Optional.<TermRow>empty() : store.findTerm(targetId))
                        .filter(found -> found.systemId().equals(row.systemId()))
                        .orElseThrow(() -> new ApiException(KnowledgeProblems.MERGE_TARGET_INVALID));
                String name = text(row, Ontology.FIELD_NAME, row.name());
                if (names.taken(row.systemId(), name, NameSpace.NONE)) {
                    throw new ApiException(KnowledgeProblems.NAME_TAKEN);
                }
                UUID alias = Ids.newId();
                UUID owner = row.ownerId();
                conflicts(() -> {
                    store.insertAlias(new TermAliasRow(alias, owner, row.systemId(), term.id(),
                            vault.encryptText(new Binding(ALIAS_ENTITY, Ontology.FIELD_NAME, alias, owner), name),
                            names.fingerprint(name), row.id(), now));
                    return null;
                });
            }
            return new Decision(merged, targetId);
        }));
        decided.ifPresent(decision -> log.atInfo().addKeyValue("ownerId", OwnerContext.require())
                .addKeyValue("proposalId", id).addKeyValue("kind", decision.proposal().kind())
                .addKeyValue("resultId", decision.resultId()).log("knowledge.proposal_merged"));
        return decided;
    }

    /** Rejects the owner's proposal {@code id} on {@code version}; empty for a proposal that is not visible. */
    public Optional<Decision> reject(UUID id, long version) {
        Optional<Decision> decided = writing(() -> visible(id).map(row -> {
            current(row, version);
            return new Decision(decide(row, version, ProposalRow.REJECTED, now()), null);
        }));
        decided.ifPresent(decision -> log.atInfo().addKeyValue("ownerId", OwnerContext.require())
                .addKeyValue("proposalId", id).addKeyValue("kind", decision.proposal().kind())
                .log("knowledge.proposal_rejected"));
        return decided;
    }

    /** The owner's proposal {@code id}, when its system is the owner's and ready. */
    private Optional<ProposalRow> visible(UUID id) {
        return store.findProposal(id).filter(row -> library.system(row.systemId()).isPresent());
    }

    /** {@code request.stale_version} unless the proposal is pending at {@code version}. */
    private static void current(ProposalRow row, long version) {
        if (row.version() != version || !row.pending()) {
            throw new ApiException(KnowledgeProblems.STALE_VERSION);
        }
    }

    private Proposal decide(ProposalRow row, long version, String state, Instant now) {
        ProposalRow decided = conflicts(() -> store.decide(row.id(), version, state, now))
                .orElseThrow(() -> new IllegalStateException("knowledge.proposal_vanished"));
        return open(List.of(decided)).getFirst();
    }

    /** The proposals decrypted, each with its material's name (null once the material went). */
    private List<Proposal> open(List<ProposalRow> rows) {
        Map<UUID, String> named = materials.names(rows.stream().map(ProposalRow::materialId)
                .filter(java.util.Objects::nonNull).toList());
        return rows.stream().map(row -> new Proposal(row.id(), row.systemId(), row.materialId(),
                row.materialId() == null ? null : named.get(row.materialId()), row.kind(),
                text(row, Ontology.FIELD_NAME, row.name()),
                row.definition() == null ? null : text(row, FIELD_DEFINITION, row.definition()),
                examplesOf(vault.decrypt(binding(row.id(), row.ownerId(), FIELD_EXAMPLES), row.examples())),
                row.state(), row.version())).toList();
    }

    private String text(ProposalRow row, String field, byte[] stored) {
        return vault.decryptText(binding(row.id(), row.ownerId(), field), stored);
    }

    /**
     * A key for a new record type of the system: the slug of its name ({@link #slug}), or with {@code _2}, {@code _3}...
     * when another type of the system has it.
     */
    private String freeKey(UUID systemId, String name) {
        String base = slug(name);
        for (int suffix = 1; suffix <= KEY_TRIES; suffix++) {
            String key = suffix == 1 ? base : base + "_" + suffix;
            // A core key is always taken, even where a core row the release before V39 wrote holds no key.
            if (!CoreRecordTypes.KEYS.contains(key) && !types.keyTaken(systemId, key)) {
                return key;
            }
        }
        throw new IllegalStateException("knowledge.key_exhausted");
    }

    /**
     * A name's slug: its letters without their accents, in lower case, and its digits; every other run of characters one
     * underscore, none at either end; at most {@value #KEY_BASE} characters (a separator is written only with room for
     * a letter after it), {@value #KEY_FALLBACK} when nothing is left. One pass, no pattern.
     */
    static String slug(String name) {
        String plain = Normalizer.normalize(name, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        boolean gap = false;
        for (int index = 0; index < plain.length() && out.length() < KEY_BASE; index++) {
            char letter = plain.charAt(index);
            if (Character.getType(letter) == Character.NON_SPACING_MARK) {
                continue;
            }
            if ((letter >= 'a' && letter <= 'z') || (letter >= '0' && letter <= '9')) {
                if (gap && !out.isEmpty()) {
                    if (out.length() + 2 > KEY_BASE) {
                        break;
                    }
                    out.append('_');
                }
                gap = false;
                out.append(letter);
            } else {
                gap = true;
            }
        }
        String slug = out.toString();
        if (slug.endsWith("_")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        return slug.isEmpty() ? KEY_FALLBACK : slug;
    }

    static List<Example> examplesOf(byte[] json) {
        List<Example> found = new ArrayList<>();
        try {
            JSON.readTree(json).forEach(example -> found.add(new Example(example.path("page").asInt(),
                    example.path("quote").asString())));
        } finally {
            Arrays.fill(json, (byte) 0);
        }
        return List.copyOf(found);
    }

    static byte[] examplesJson(List<Example> examples) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        examples.forEach(example -> array.addObject().put("page", example.page()).put("quote", example.quote()));
        return JSON.writeValueAsBytes(array);
    }

    /** A store write whose conflict becomes its problem: a taken name, or a version that moved meanwhile. */
    private static <T> T conflicts(Supplier<T> write) {
        try {
            return write.get();
        } catch (KnowledgeConflict conflict) {
            throw new ApiException(switch (conflict.reason()) {
                case NAME_TAKEN, CORE_TAKEN -> KnowledgeProblems.NAME_TAKEN;
                case STALE_VERSION -> KnowledgeProblems.STALE_VERSION;
            });
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private <T> T reading(Supplier<T> read) {
        OwnerContext.require();
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }

    private <T> T writing(Supplier<T> write) {
        OwnerContext.require();
        return new TransactionTemplate(transactions.getObject()).execute(status -> write.get());
    }

    private static UUID requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("knowledge.transaction_required");
        }
        return OwnerContext.require();
    }

    static Binding binding(UUID rowId, UUID owner, String field) {
        return new Binding(PROPOSAL, field, rowId, owner);
    }
}
