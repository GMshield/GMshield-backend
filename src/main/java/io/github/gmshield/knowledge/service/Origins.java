package io.github.gmshield.knowledge.service;

import io.github.gmshield.knowledge.data.OntologyStore;
import io.github.gmshield.knowledge.data.ProposalRow;
import io.github.gmshield.materials.service.MaterialNames;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Where an approved record type or term came from (Story 4.4): the approving proposal, its material and that material's
 * name; the material and its name are null once the material was deleted ("material removido"). A core type has no
 * origin of this kind: its origin is {@code core}.
 */
@Component
class Origins {

    private final OntologyStore store;
    private final MaterialNames materials;

    Origins(OntologyStore store, MaterialNames materials) {
        this.store = store;
        this.materials = materials;
    }

    /** The origin of each of these proposals, by proposal id; an unknown id is left out. */
    Map<UUID, Ontology.Origin> of(Collection<UUID> proposalIds) {
        List<UUID> wanted = proposalIds.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        List<ProposalRow> rows = store.findProposals(wanted);
        Map<UUID, String> names = materials.names(rows.stream().map(ProposalRow::materialId).filter(Objects::nonNull)
                .toList());
        Map<UUID, Ontology.Origin> found = new HashMap<>();
        rows.forEach(row -> found.put(row.id(), new Ontology.Origin(row.id(), row.materialId(),
                row.materialId() == null ? null : names.get(row.materialId()))));
        return Map.copyOf(found);
    }
}
