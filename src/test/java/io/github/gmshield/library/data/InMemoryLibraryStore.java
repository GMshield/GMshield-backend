package io.github.gmshield.library.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@code system} and {@code campaign} in memory, for tests that run without PostgreSQL. Like Hibernate's
 * {@code @TenantId}, it sees the rows of the {@link OwnerContext}'s owner only and refuses to work without one;
 * like the tables, it keeps the unique keys and the campaign's (owner, system) reference. A write undoes itself
 * when its transaction rolls back.
 */
public class InMemoryLibraryStore implements LibraryStore {

    public final Map<UUID, SystemRow> systems = new ConcurrentHashMap<>();
    public final Map<UUID, CampaignRow> campaigns = new ConcurrentHashMap<>();
    /** Set it to make the next campaign insert fail, as a database failure would. */
    public final AtomicBoolean failNextCampaign = new AtomicBoolean();

    @Override
    public synchronized void insertSystem(SystemRow row) {
        UUID owner = ownerFor(row.ownerId());
        boolean nameTaken = systems.values().stream().anyMatch(other -> other.ownerId().equals(owner)
                && java.util.Arrays.equals(other.nameFingerprint(), row.nameFingerprint()));
        if (systems.containsKey(row.id()) || nameTaken) {
            throw new IllegalStateException("unique key taken");
        }
        systems.put(row.id(), row);
        RollbackUndo.onRollback(() -> systems.remove(row.id()));
    }

    @Override
    public synchronized void insertCampaign(CampaignRow row) {
        UUID owner = ownerFor(row.ownerId());
        if (failNextCampaign.getAndSet(false)) {
            throw new IllegalStateException("campaign insert failed");
        }
        SystemRow system = systems.get(row.systemId());
        if (campaigns.containsKey(row.id()) || system == null || !system.ownerId().equals(owner)) {
            throw new IllegalStateException("key or reference refused");
        }
        campaigns.put(row.id(), row);
        RollbackUndo.onRollback(() -> campaigns.remove(row.id()));
    }

    @Override
    public Optional<SystemRow> findSystem(UUID id) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(systems.get(id)).filter(row -> row.ownerId().equals(owner));
    }

    @Override
    public Optional<CampaignRow> findCampaign(UUID id) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(campaigns.get(id)).filter(row -> row.ownerId().equals(owner));
    }

    @Override
    public Optional<CampaignRow> firstCampaign() {
        UUID owner = OwnerContext.require();
        return campaigns.values().stream().filter(row -> row.ownerId().equals(owner))
                .min(Comparator.comparing(CampaignRow::createdAt).thenComparing(CampaignRow::id));
    }

    /** The current owner, who must be the row's; a write outside a transaction is refused, as JPA refuses it. */
    private static UUID ownerFor(UUID rowOwner) {
        UUID owner = OwnerContext.require();
        if (!owner.equals(rowOwner)) {
            throw new IllegalStateException("another owner's row");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("no transaction");
        }
        return owner;
    }
}
