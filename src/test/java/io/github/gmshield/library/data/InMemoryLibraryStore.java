package io.github.gmshield.library.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@code system} and {@code campaign} in memory, for tests that run without PostgreSQL. Like Hibernate's
 * {@code @TenantId}, it sees the rows of the {@link OwnerContext}'s owner only and refuses to work without one;
 * like the tables, it keeps the unique keys and the campaign's (owner, system) reference, and (Story 3.1) a system's
 * version, refusing a stale edit or a taken name as {@link LibraryConflict}. A write undoes itself when its transaction
 * rolls back.
 */
public class InMemoryLibraryStore implements LibraryStore {

    public final Map<UUID, SystemRow> systems = new ConcurrentHashMap<>();
    public final Map<UUID, CampaignRow> campaigns = new ConcurrentHashMap<>();
    /** Set it to make the next campaign insert fail, as a database failure would. */
    public final AtomicBoolean failNextCampaign = new AtomicBoolean();

    @Override
    public synchronized void insertSystem(SystemRow row) {
        UUID owner = ownerFor(row.ownerId());
        if (systems.containsKey(row.id())) {
            throw new IllegalStateException("unique key taken");
        }
        if (nameTaken(owner, row.nameFingerprint(), row.id())) {
            throw new LibraryConflict(LibraryConflict.Reason.NAME_TAKEN);
        }
        systems.put(row.id(), row);
        RollbackUndo.onRollback(() -> systems.remove(row.id()));
    }

    @Override
    public synchronized Optional<SystemRow> updateSystem(SystemChange change) {
        Optional<SystemRow> found = findSystem(change.id());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SystemRow before = found.get();
        UUID owner = ownerFor(before.ownerId());
        if (before.version() != change.version()) {
            throw new LibraryConflict(LibraryConflict.Reason.STALE_VERSION);
        }
        if (nameTaken(owner, change.nameFingerprint(), change.id())) {
            throw new LibraryConflict(LibraryConflict.Reason.NAME_TAKEN);
        }
        SystemRow after = new SystemRow(before.id(), owner, change.name(), change.nameFingerprint(), change.edition(),
                change.description(), before.version() + 1, before.createdAt());
        systems.put(after.id(), after);
        RollbackUndo.onRollback(() -> systems.put(before.id(), before));
        return Optional.of(after);
    }

    @Override
    public boolean systemNameTaken(byte[] nameFingerprint, UUID except) {
        return nameTaken(OwnerContext.require(), nameFingerprint, except);
    }

    @Override
    public List<SystemRow> systems(UUID before, int limit) {
        UUID owner = OwnerContext.require();
        return systems.values().stream()
                .filter(row -> row.ownerId().equals(owner) && (before == null || row.id().compareTo(before) < 0))
                .sorted(Comparator.comparing(SystemRow::id).reversed()).limit(Math.max(limit, 0)).toList();
    }

    @Override
    public List<CampaignRow> campaignsOf(UUID systemId, UUID before, int limit) {
        UUID owner = OwnerContext.require();
        return campaigns.values().stream()
                .filter(row -> row.ownerId().equals(owner) && row.systemId().equals(systemId)
                        && (before == null || row.id().compareTo(before) < 0))
                .sorted(Comparator.comparing(CampaignRow::id).reversed()).limit(Math.max(limit, 0)).toList();
    }

    @Override
    public List<UUID> campaignIdsOf(UUID systemId) {
        return campaignsOf(systemId, null, Integer.MAX_VALUE).stream().map(CampaignRow::id).toList();
    }

    private boolean nameTaken(UUID owner, byte[] fingerprint, UUID except) {
        return systems.values().stream().anyMatch(other -> other.ownerId().equals(owner) && !other.id().equals(except)
                && Arrays.equals(other.nameFingerprint(), fingerprint));
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
