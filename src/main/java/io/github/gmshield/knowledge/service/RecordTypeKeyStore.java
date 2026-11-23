package io.github.gmshield.knowledge.service;

import io.github.gmshield.knowledge.data.KnowledgeStore;
import io.github.gmshield.knowledge.data.RecordTypeRow;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.materials.service.RecordTypeKeys;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * A system's record type keys (Story 4.4 part B), for the {@code materials} and {@code scope} modules
 * ({@link RecordTypeKeys}), as the owner of the work and in the caller's transaction when there is one. A records step
 * extracts the core types rule, spell and item ({@link #EXTRACTED_CORE}, named by their stored English names) and every
 * type the GM approved in the systems its material counts under; the other core types (creature, character sheet,
 * encounter, dungeon, note) are never extracted. An approved type's name is decrypted for the records step's input
 * only, where the model reads it as data. Nothing here logs a name.
 */
@Service
class RecordTypeKeyStore implements RecordTypeKeys {

    /** The core types a records step extracts, in order (Story 4.2). */
    static final List<String> EXTRACTED_CORE = List.of("rule", "spell", "item");

    private final KnowledgeStore store;
    private final Vault vault;
    private final Library library;

    RecordTypeKeyStore(KnowledgeStore store, Vault vault, Library library) {
        this.store = store;
        this.vault = vault;
        this.library = library;
    }

    @Override
    public List<Named> extraction(Collection<UUID> systemIds) {
        UUID owner = OwnerContext.require();
        Map<String, String> named = new LinkedHashMap<>();
        EXTRACTED_CORE.forEach(key -> named.put(key, CoreRecordTypes.name(key)));
        for (UUID systemId : systemIds.stream().distinct().toList()) {
            if (library.system(systemId).isEmpty()) {
                continue;
            }
            for (RecordTypeRow row : store.approvedRecordTypes(systemId)) {
                // The first system that has the key names it.
                if (!named.containsKey(row.key())) {
                    named.put(row.key(), vault.decryptText(new Binding(Ontology.RECORD_TYPE, Ontology.FIELD_NAME,
                            row.id(), owner), row.name()));
                }
            }
        }
        List<Named> found = new ArrayList<>(named.size());
        named.forEach((key, name) -> found.add(new Named(key, name)));
        return List.copyOf(found);
    }

    @Override
    public List<String> extractionKeys(Collection<UUID> systemIds) {
        OwnerContext.require();
        Set<String> keys = new LinkedHashSet<>(EXTRACTED_CORE);
        for (UUID systemId : systemIds.stream().distinct().toList()) {
            if (library.system(systemId).isPresent()) {
                store.approvedRecordTypes(systemId).forEach(row -> keys.add(row.key()));
            }
        }
        return List.copyOf(keys);
    }

    @Override
    public Set<String> keysOf(UUID systemId) {
        OwnerContext.require();
        if (systemId == null || library.system(systemId).isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(store.keys(systemId));
    }
}
