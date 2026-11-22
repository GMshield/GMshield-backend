package io.github.gmshield.scope.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The scope tables in memory (Story 3.5), for tests that run without PostgreSQL. Like Hibernate's {@code @TenantId},
 * it sees the rows of the {@link OwnerContext}'s owner only; like the tables, a change names the version it was made on
 * and a first row starts at version 1. A write undoes itself when its transaction rolls back.
 */
public class InMemoryScopeStore implements ScopeStore {

    /** One stored row, with its owner. */
    public record Row(UUID ownerId, StoredScope scope) {
    }

    public final Map<UUID, Row> rows = new ConcurrentHashMap<>();

    @Override
    public Optional<StoredScope> find(UUID campaignId) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(rows.get(campaignId)).filter(row -> row.ownerId().equals(owner)).map(Row::scope);
    }

    @Override
    public synchronized StoredScope change(UUID campaignId, long version, boolean campaignTier, boolean systemTier,
                                           boolean otherTier, boolean modelKnowledge, Set<UUID> materialsOff,
                                           Instant now) {
        UUID owner = OwnerContext.require();
        Row before = rows.get(campaignId);
        if (before != null && !before.ownerId().equals(owner)) {
            throw new IllegalStateException("key refused");
        }
        long stored = before == null ? 0 : before.scope().version();
        if (stored != version) {
            throw new StaleScopeVersion();
        }
        StoredScope after = new StoredScope(campaignId, campaignTier, systemTier, otherTier, modelKnowledge,
                new HashSet<>(materialsOff), version + 1);
        rows.put(campaignId, new Row(owner, after));
        RollbackUndo.onRollback(() -> {
            if (before == null) {
                rows.remove(campaignId);
            } else {
                rows.put(campaignId, before);
            }
        });
        return after;
    }

    @Override
    public synchronized void deleteOfCampaigns(Collection<UUID> campaignIds) {
        UUID owner = OwnerContext.require();
        for (UUID campaignId : List.copyOf(campaignIds)) {
            Row before = rows.get(campaignId);
            if (before != null && before.ownerId().equals(owner)) {
                rows.remove(campaignId);
                RollbackUndo.onRollback(() -> rows.put(campaignId, before));
            }
        }
    }

    @Override
    public synchronized void deleteOfMaterial(UUID materialId) {
        UUID owner = OwnerContext.require();
        for (Map.Entry<UUID, Row> entry : List.copyOf(rows.entrySet())) {
            Row before = entry.getValue();
            if (before.ownerId().equals(owner) && before.scope().materialsOff().contains(materialId)) {
                Set<UUID> off = new HashSet<>(before.scope().materialsOff());
                off.remove(materialId);
                StoredScope s = before.scope();
                rows.put(entry.getKey(), new Row(owner, new StoredScope(s.campaignId(), s.campaignTier(),
                        s.systemTier(), s.otherTier(), s.modelKnowledge(), off, s.version())));
                RollbackUndo.onRollback(() -> rows.put(entry.getKey(), before));
            }
        }
    }
}
