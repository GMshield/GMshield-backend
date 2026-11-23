package io.github.gmshield.knowledge.service;

import io.github.gmshield.knowledge.data.KnowledgeStore;
import io.github.gmshield.knowledge.data.OntologyStore;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.vault.service.Vault;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A system's one name space (Story 4.4): its record types' names, its terms' names and aliases, and the core types'
 * names in pt-BR and en are unique together, compared by their unique form ({@link Library#uniqueForm}: trimmed, NFC,
 * lower case) through one keyed fingerprint, the record type's (entity {@value Ontology#RECORD_TYPE}, field
 * {@value Ontology#FIELD_NAME}). Each table keeps its own names unique; the caller checks the three together while it
 * holds the system's ontology lock ({@link KnowledgeStore#lockOntology}).
 */
@Component
class NameSpace {

    /** No record type has this id: a check that excepts none. */
    static final UUID NONE = new UUID(0, 0);
    private static final List<String> CORE_FORMS = CoreRecordTypes.namesInEveryLanguage().stream()
            .map(Library::uniqueForm).toList();

    private final KnowledgeStore types;
    private final OntologyStore terms;
    private final Vault vault;

    NameSpace(KnowledgeStore types, OntologyStore terms, Vault vault) {
        this.types = types;
        this.terms = terms;
        this.vault = vault;
    }

    /** A name's keyed fingerprint: of its unique form, the same for a type, a term, an alias and a proposal. */
    byte[] fingerprint(String name) {
        return vault.fingerprint(Ontology.RECORD_TYPE, Ontology.FIELD_NAME, Library.uniqueForm(name));
    }

    /**
     * Whether the owner's system {@code systemId} already has this name: a core name in pt-BR or en, a record type
     * other than {@code exceptType}, a term or an alias.
     */
    boolean taken(UUID systemId, String name, UUID exceptType) {
        if (CORE_FORMS.contains(Library.uniqueForm(name))) {
            return true;
        }
        byte[] fingerprint = fingerprint(name);
        return types.nameTaken(systemId, fingerprint, exceptType) || terms.termNameTaken(systemId, fingerprint);
    }

    /** A typed name, trimmed, within 1 to {@value Ontology#NAME_MAX} code points, or {@code ontology.name_invalid}. */
    static String valid(String typed) {
        String name = typed == null ? "" : typed.strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > Ontology.NAME_MAX) {
            throw new ApiException(KnowledgeProblems.NAME_INVALID);
        }
        return name;
    }
}
