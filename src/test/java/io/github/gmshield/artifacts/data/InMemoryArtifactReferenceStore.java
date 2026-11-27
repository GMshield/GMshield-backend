package io.github.gmshield.artifacts.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code artifact_reference} in memory (Story 5.4 part A), for tests that run without PostgreSQL. Like Hibernate's
 * {@code @TenantId}, it sees the rows of the {@link OwnerContext}'s owner only and refuses to work without one; like the
 * table, its key is the artifact and the number. A write undoes itself when its transaction rolls back. The cascade from
 * the artifact's row is PostgreSQL's alone
 * ({@code ConversationIntegrationTest#aSavedDraftCitingAnArtifactCopiesItsReferenceAndTheRowsGoWithTheArtifactsRowOnPostgres}).
 */
public class InMemoryArtifactReferenceStore implements ArtifactReferenceStore {

    /** The rows by their key, {@code <record id>/<number>}. */
    public final Map<String, ArtifactReferenceRow> rows = new ConcurrentHashMap<>();

    @Override
    public synchronized void insert(List<ArtifactReferenceRow> inserted) {
        UUID owner = OwnerContext.require();
        for (ArtifactReferenceRow row : inserted) {
            String key = row.recordId() + "/" + row.number();
            if (!row.ownerId().equals(owner) || rows.containsKey(key)) {
                throw new IllegalStateException("key refused");
            }
            rows.put(key, row);
            RollbackUndo.onRollback(() -> rows.remove(key));
        }
    }

    @Override
    public List<ArtifactReferenceRow> of(UUID recordId) {
        UUID owner = OwnerContext.require();
        return rows.values().stream().filter(row -> row.ownerId().equals(owner) && row.recordId().equals(recordId))
                .sorted(Comparator.comparingInt(ArtifactReferenceRow::number)).toList();
    }

    @Override
    public synchronized int deleteOf(Collection<UUID> recordIds) {
        UUID owner = OwnerContext.require();
        List<Map.Entry<String, ArtifactReferenceRow>> found = rows.entrySet().stream()
                .filter(entry -> entry.getValue().ownerId().equals(owner)
                        && recordIds.contains(entry.getValue().recordId())).toList();
        found.forEach(entry -> {
            rows.remove(entry.getKey());
            RollbackUndo.onRollback(() -> rows.put(entry.getKey(), entry.getValue()));
        });
        return found.size();
    }
}
