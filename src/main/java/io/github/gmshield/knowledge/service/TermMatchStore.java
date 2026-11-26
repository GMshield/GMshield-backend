package io.github.gmshield.knowledge.service;

import io.github.gmshield.knowledge.data.KnowledgeStore;
import io.github.gmshield.knowledge.data.OntologyStore;
import io.github.gmshield.knowledge.data.RecordStore;
import io.github.gmshield.knowledge.data.TermAliasRow;
import io.github.gmshield.knowledge.data.TermRow;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.service.ExtractedRecords;
import io.github.gmshield.materials.service.TermMatches;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The names a question's terms match (Story 4.6), for the {@code scope} module ({@link TermMatches}), as the owner of the
 * work and in the caller's transaction. Each name is put in its unique form ({@link Library#uniqueForm}) and
 * fingerprinted with the keyed HMACs its table already stores: the record type's subkey (entity
 * {@value Ontology#RECORD_TYPE}, field {@value Ontology#FIELD_NAME}) for the ontology's names, which a system's types,
 * terms and aliases share ({@link NameSpace}), and the record's (entity {@value ExtractedRecordStore#RECORD}) for record
 * names. A match is an equality on those fingerprints (fingerprint-IN queries); the core types' names in pt-BR and en,
 * singular and plural, come from {@link CoreRecordTypes#keyAsked}. Term names and aliases are decrypted for the
 * matched terms only; a definition is never read. Nothing here logs a name.
 */
@Service
class TermMatchStore implements TermMatches {

    private final KnowledgeStore types;
    private final OntologyStore terms;
    private final RecordStore records;
    private final Vault vault;
    private final Library library;

    TermMatchStore(KnowledgeStore types, OntologyStore terms, RecordStore records, Vault vault, Library library) {
        this.types = types;
        this.terms = terms;
        this.records = records;
        this.vault = vault;
        this.library = library;
    }

    @Override
    public Ontological ontology(UUID systemId, Collection<String> names) {
        UUID owner = OwnerContext.require();
        List<String> forms = forms(names);
        if (systemId == null || forms.isEmpty() || library.system(systemId).isEmpty()) {
            return Ontological.NONE;
        }
        Set<String> keys = new TreeSet<>();
        forms.forEach(form -> CoreRecordTypes.keyAsked(form).ifPresent(keys::add));
        List<byte[]> fingerprints = forms.stream()
                .map(form -> vault.fingerprint(Ontology.RECORD_TYPE, Ontology.FIELD_NAME, form)).toList();
        types.recordTypesNamed(systemId, fingerprints).forEach(row -> keys.add(row.key()));
        Set<UUID> termIds = new LinkedHashSet<>();
        terms.termsNamed(systemId, fingerprints).forEach(row -> termIds.add(row.id()));
        terms.aliasesNamed(systemId, fingerprints).forEach(row -> termIds.add(row.termId()));
        if (termIds.isEmpty()) {
            return new Ontological(List.copyOf(keys), List.of());
        }
        List<TermRow> matched = terms.findTerms(termIds).stream().filter(row -> row.systemId().equals(systemId))
                .toList();
        Map<UUID, List<String>> aliases = new HashMap<>();
        for (TermAliasRow alias : terms.aliasesOf(matched.stream().map(TermRow::id).toList())) {
            aliases.computeIfAbsent(alias.termId(), id -> new ArrayList<>()).add(vault.decryptText(
                    new Binding(Proposals.ALIAS_ENTITY, Ontology.FIELD_NAME, alias.id(), owner), alias.name()));
        }
        Set<String> found = new LinkedHashSet<>();
        for (TermRow term : matched) {
            found.add(vault.decryptText(new Binding(Proposals.TERM_ENTITY, Ontology.FIELD_NAME, term.id(), owner),
                    term.name()));
            found.addAll(aliases.getOrDefault(term.id(), List.of()));
        }
        return new Ontological(List.copyOf(keys), List.copyOf(found));
    }

    @Override
    public List<ExtractedRecords.Located> records(Collection<UUID> versionIds, Set<String> recordTypes,
                                                  Collection<String> names, int limit) {
        OwnerContext.require();
        List<String> forms = forms(names);
        if (versionIds.isEmpty() || recordTypes.isEmpty() || forms.isEmpty() || limit < 1) {
            return List.of();
        }
        List<byte[]> fingerprints = forms.stream()
                .map(form -> ExtractedRecordStore.nameFingerprint(vault, form)).toList();
        return records.namedAny(Set.copyOf(versionIds), Set.copyOf(recordTypes), fingerprints, limit).stream()
                .map(row -> new ExtractedRecords.Located(row.id(), row.versionId(), row.type(), row.firstPage(),
                        row.passageIds())).toList();
    }

    /** The names' distinct unique forms, blank ones left out. */
    private static List<String> forms(Collection<String> names) {
        return names.stream().filter(Objects::nonNull).map(Library::uniqueForm).filter(form -> !form.isEmpty())
                .distinct().toList();
    }
}
