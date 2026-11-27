package io.github.gmshield.artifacts.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * {@code artifact_share} in memory (Story 5.6 part A), for tests that run without PostgreSQL. Like Hibernate's
 * {@code @TenantId}, it sees the rows of the {@link OwnerContext}'s owner only and refuses to work without one; like the
 * table (V52), its key is the artifact and the campaign. A write undoes itself when its transaction rolls back. The
 * cascade from the artifact's row is PostgreSQL's alone
 * ({@code ArtifactsIntegrationTest#aShareIsOneRowPerPairAndItGoesWithTheArtifactsRowAndWithTheTargetCampaignOnPostgres}):
 * a share whose artifact is gone stays here, and every read of the service leaves it out.
 */
public class InMemoryArtifactShareStore implements ArtifactShareStore {

    private static final Comparator<ArtifactShareRow> OLDEST = Comparator.comparing(ArtifactShareRow::createdAt);

    /** The rows by their key, {@code <artifact id>/<campaign id>}. */
    public final Map<String, ArtifactShareRow> rows = new ConcurrentHashMap<>();

    @Override
    public synchronized void insert(ArtifactShareRow row) {
        UUID owner = OwnerContext.require();
        String key = key(row.artifactId(), row.campaignId());
        if (!row.ownerId().equals(owner) || rows.containsKey(key)) {
            throw new IllegalStateException("key refused");
        }
        rows.put(key, row);
        RollbackUndo.onRollback(() -> rows.remove(key));
    }

    @Override
    public List<ArtifactShareRow> of(UUID artifactId) {
        UUID owner = OwnerContext.require();
        return rows.values().stream().filter(row -> row.ownerId().equals(owner) && row.artifactId().equals(artifactId))
                .sorted(OLDEST.thenComparing(ArtifactShareRow::campaignId)).toList();
    }

    @Override
    public List<ArtifactShareRow> into(UUID campaignId) {
        UUID owner = OwnerContext.require();
        return rows.values().stream().filter(row -> row.ownerId().equals(owner) && row.campaignId().equals(campaignId))
                .sorted(OLDEST.thenComparing(ArtifactShareRow::artifactId)).toList();
    }

    @Override
    public boolean delete(UUID artifactId, UUID campaignId) {
        return removed(row -> row.artifactId().equals(artifactId) && row.campaignId().equals(campaignId)) > 0;
    }

    @Override
    public int deleteOf(Collection<UUID> artifactIds) {
        return removed(row -> artifactIds.contains(row.artifactId()));
    }

    @Override
    public int deleteInto(Collection<UUID> campaignIds) {
        return removed(row -> campaignIds.contains(row.campaignId()));
    }

    private synchronized int removed(Predicate<ArtifactShareRow> going) {
        UUID owner = OwnerContext.require();
        List<Map.Entry<String, ArtifactShareRow>> found = rows.entrySet().stream()
                .filter(entry -> entry.getValue().ownerId().equals(owner) && going.test(entry.getValue())).toList();
        found.forEach(entry -> {
            rows.remove(entry.getKey());
            RollbackUndo.onRollback(() -> rows.put(entry.getKey(), entry.getValue()));
        });
        return found.size();
    }

    private static String key(UUID artifactId, UUID campaignId) {
        return artifactId + "/" + campaignId;
    }
}
