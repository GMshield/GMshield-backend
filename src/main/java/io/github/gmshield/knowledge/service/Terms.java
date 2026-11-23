package io.github.gmshield.knowledge.service;

import io.github.gmshield.knowledge.data.OntologyStore;
import io.github.gmshield.knowledge.data.TermAliasRow;
import io.github.gmshield.knowledge.data.TermRow;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A system's approved terms (Story 4.4), for the owner of the work ({@link OwnerContext}): each a name and a definition
 * quoted from its material, with its aliases (the names of the term proposals merged into it) and its origin, the
 * approving proposal and its material. Names, definitions and aliases are encrypted for the owner (entities
 * {@value Proposals#TERM_ENTITY} and {@value Proposals#ALIAS_ENTITY}). A system that is not the owner's, unknown or being
 * deleted has none to show ("not found").
 */
@Service
public class Terms {

    private final OntologyStore store;
    private final Vault vault;
    private final Library library;
    private final Origins origins;
    private final ObjectProvider<PlatformTransactionManager> transactions;

    Terms(OntologyStore store, Vault vault, Library library, Origins origins,
          ObjectProvider<PlatformTransactionManager> transactions) {
        this.store = store;
        this.vault = vault;
        this.library = library;
        this.origins = origins;
        this.transactions = transactions;
    }

    /** One approved term. {@code toString} names ids, counts and the version only. */
    public record Term(UUID id, String name, String definition, List<String> aliases, Ontology.Origin origin,
                       long version) {
        public Term {
            aliases = List.copyOf(aliases);
        }

        @Override
        public String toString() {
            return "Term[id=" + id + ", aliases=" + aliases.size() + ", origin=" + origin + ", version=" + version + "]";
        }
    }

    /** One page of a system's terms and how many it has in all. */
    public record TermPage(List<Term> items, long total) {
    }

    /**
     * The terms of the owner's system {@code systemId}, oldest first, a page as {@link Ontology#recordTypes} takes it;
     * empty for a system that is not the owner's, unknown or being deleted.
     */
    public Optional<TermPage> terms(UUID systemId, int offset, int limit) {
        Ontology.checkPage(offset, limit);
        OwnerContext.require();
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> library.system(systemId).map(system -> page(systemId, offset, limit)));
    }

    private TermPage page(UUID systemId, int offset, int limit) {
        List<TermRow> rows = store.termsOf(systemId, offset, limit);
        Map<UUID, List<String>> aliases = new HashMap<>();
        for (TermAliasRow alias : store.aliasesOf(rows.stream().map(TermRow::id).toList())) {
            aliases.computeIfAbsent(alias.termId(), id -> new ArrayList<>()).add(vault.decryptText(
                    new Binding(Proposals.ALIAS_ENTITY, Ontology.FIELD_NAME, alias.id(), alias.ownerId()),
                    alias.name()));
        }
        Map<UUID, Ontology.Origin> from = origins.of(rows.stream().map(TermRow::proposalId).toList());
        List<Term> items = rows.stream().map(row -> new Term(row.id(),
                vault.decryptText(new Binding(Proposals.TERM_ENTITY, Ontology.FIELD_NAME, row.id(), row.ownerId()),
                        row.name()),
                vault.decryptText(new Binding(Proposals.TERM_ENTITY, Proposals.FIELD_DEFINITION, row.id(),
                        row.ownerId()), row.definition()),
                aliases.getOrDefault(row.id(), List.of()), from.get(row.proposalId()), row.version())).toList();
        return new TermPage(items, store.termCount(systemId));
    }
}
