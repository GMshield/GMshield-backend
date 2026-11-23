package io.github.gmshield.knowledge.data;

import io.github.gmshield.library.data.InMemoryLibraryStore;
import io.github.gmshield.library.data.SystemRow;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * {@link MissingCoreTypes} over the in-memory library and knowledge tables (Story 4.1): across owners, the ready systems
 * with fewer core record types than {@code cores}, in id order after {@code after}, as the JDBC statement reads them.
 */
public class InMemoryMissingCoreTypes implements MissingCoreTypes {

    private final InMemoryLibraryStore library;
    private final InMemoryKnowledgeStore knowledge;

    public InMemoryMissingCoreTypes(InMemoryLibraryStore library, InMemoryKnowledgeStore knowledge) {
        this.library = library;
        this.knowledge = knowledge;
    }

    @Override
    public List<SystemRef> systems(UUID after, int cores, int limit) {
        return library.systems.values().stream()
                .filter(system -> !library.deleting.contains(system.id()))
                .filter(system -> after == null || system.id().compareTo(after) > 0)
                .filter(system -> knowledge.recordTypes.values().stream().filter(row -> row.core() != null
                        && row.ownerId().equals(system.ownerId()) && row.systemId().equals(system.id())).count() < cores)
                .sorted(Comparator.comparing(SystemRow::id)).limit(limit)
                .map(system -> new SystemRef(system.ownerId(), system.id())).toList();
    }
}
