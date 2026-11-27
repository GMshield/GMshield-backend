package io.github.gmshield.library.data;

import io.github.gmshield.shared.data.RollbackUndo;
import io.github.gmshield.shared.service.OwnerContext;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@code system} and {@code campaign} in memory, for tests that run without PostgreSQL. Like Hibernate's
 * {@code @TenantId}, it sees the rows of the {@link OwnerContext}'s owner only and refuses to work without one;
 * like the tables, it keeps the unique keys and the campaign's (owner, system) reference, and (Story 3.1) a system's
 * version, refusing a stale edit or a taken name as {@link LibraryConflict}; from Story 3.2 a campaign's version, archive,
 * choice and name key (owner, system, fingerprint) as well; from Story 3.2 part B a campaign's state; from Story 6.1 a
 * campaign's Shields, with their name key (campaign, fingerprint), the one unnamed Shield per campaign, their version
 * and the (owner, campaign) reference, which refuses to delete a campaign that still has Shields; from Story 6.1 part B
 * a per-campaign lock that {@link #lockCampaign} holds until its transaction ends, so that Shield deletions of one
 * campaign run one after the other (the campaign's own deletion does not take it); from Story 6.2 part B a Shield's
 * deleting state (every Shield read and count skips a deleting one, and its name is freed). A write undoes itself when
 * its transaction rolls back.
 */
public class InMemoryLibraryStore implements LibraryStore {

    public final Map<UUID, SystemRow> systems = new ConcurrentHashMap<>();
    public final Map<UUID, CampaignRow> campaigns = new ConcurrentHashMap<>();
    /** The systems being deleted (Story 3.1 part B): like {@code state = 'deleting'}, every read but the deletion's skips them. */
    public final Set<UUID> deleting = ConcurrentHashMap.newKeySet();
    /**
     * The campaigns being deleted (Story 3.2 part B): like {@code campaign.state = 'deleting'}, every read but the
     * deletions' skips them.
     */
    public final Set<UUID> deletingCampaigns = ConcurrentHashMap.newKeySet();
    /** The Shields (Story 6.1, V53). */
    public final Map<UUID, ShieldRow> shields = new ConcurrentHashMap<>();
    /** Per-campaign locks for Shield deletions (Story 6.1 part B), each held from {@link #lockCampaign} until its transaction ends. */
    private final Map<UUID, ReentrantLock> campaignLocks = new ConcurrentHashMap<>();
    /**
     * Like {@code record_shield_fkey} (Story 6.2 part B): when set, whether a Shield still holds a note, so deleting its
     * row is refused as the database refuses it.
     */
    public volatile java.util.function.Predicate<UUID> shieldHasNotes;
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
                .filter(row -> row.ownerId().equals(owner) && !deleting.contains(row.id())
                        && (before == null || row.id().compareTo(before) < 0))
                .sorted(Comparator.comparing(SystemRow::id).reversed()).limit(Math.max(limit, 0)).toList();
    }

    @Override
    public List<CampaignRow> campaigns(UUID systemId, boolean archived, UUID before, int limit) {
        UUID owner = OwnerContext.require();
        return campaigns.values().stream()
                .filter(row -> row.ownerId().equals(owner) && (systemId == null || row.systemId().equals(systemId))
                        && ready(row) && row.archived() == archived
                        && (before == null || row.id().compareTo(before) < 0))
                .sorted(Comparator.comparing(CampaignRow::id).reversed()).limit(Math.max(limit, 0)).toList();
    }

    @Override
    public List<SystemRow> systemsFrom(int offset, int limit) {
        return systems(null, Integer.MAX_VALUE).stream().skip(Math.max(offset, 0)).limit(Math.max(limit, 0)).toList();
    }

    @Override
    public long systemCount() {
        return systems(null, Integer.MAX_VALUE).size();
    }

    @Override
    public List<CampaignRow> campaignsFrom(UUID systemId, boolean archived, int offset, int limit) {
        return campaigns(systemId, archived, null, Integer.MAX_VALUE).stream().skip(Math.max(offset, 0))
                .limit(Math.max(limit, 0)).toList();
    }

    @Override
    public long campaignCount(UUID systemId, boolean archived) {
        return campaigns(systemId, archived, null, Integer.MAX_VALUE).size();
    }

    @Override
    public List<UUID> campaignIdsOf(UUID systemId) {
        UUID owner = OwnerContext.require();
        return campaigns.values().stream()
                .filter(row -> row.ownerId().equals(owner) && row.systemId().equals(systemId) && ready(row))
                .sorted(Comparator.comparing(CampaignRow::id).reversed()).map(CampaignRow::id).toList();
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
        if (campaignTaken(owner, row.systemId(), row.nameFingerprint(), row.id())) {
            throw new LibraryConflict(LibraryConflict.Reason.CAMPAIGN_NAME_TAKEN);
        }
        campaigns.put(row.id(), row);
        RollbackUndo.onRollback(() -> campaigns.remove(row.id()));
    }

    @Override
    public synchronized Optional<CampaignRow> updateCampaign(CampaignChange change) {
        Optional<CampaignRow> found = findCampaign(change.id());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        CampaignRow before = found.get();
        UUID owner = ownerFor(before.ownerId());
        if (before.version() != change.version()) {
            throw new LibraryConflict(LibraryConflict.Reason.STALE_VERSION);
        }
        if (campaignTaken(owner, before.systemId(), change.nameFingerprint(), change.id())) {
            throw new LibraryConflict(LibraryConflict.Reason.CAMPAIGN_NAME_TAKEN);
        }
        Instant archivedAt;
        Instant chosenAt = before.chosenAt();
        if (change.archived() && before.archivedAt() == null) {
            archivedAt = change.at();
            chosenAt = null;
        } else {
            archivedAt = change.archived() ? before.archivedAt() : null;
        }
        CampaignRow after = new CampaignRow(before.id(), owner, before.systemId(), change.name(), change.nameFingerprint(),
                archivedAt, chosenAt, before.version() + 1, before.createdAt());
        campaigns.put(after.id(), after);
        RollbackUndo.onRollback(() -> campaigns.put(before.id(), before));
        return Optional.of(after);
    }

    @Override
    public synchronized boolean chooseCampaign(UUID id, Instant at) {
        Optional<CampaignRow> found = findCampaign(id).filter(row -> !row.archived());
        found.ifPresent(before -> {
            ownerFor(before.ownerId());
            campaigns.put(id, new CampaignRow(id, before.ownerId(), before.systemId(), before.name(),
                    before.nameFingerprint(), null, at, before.version(), before.createdAt()));
            RollbackUndo.onRollback(() -> campaigns.put(id, before));
        });
        return found.isPresent();
    }

    @Override
    public boolean campaignNameTaken(UUID systemId, byte[] nameFingerprint, UUID except) {
        return campaignTaken(OwnerContext.require(), systemId, nameFingerprint, except);
    }

    @Override
    public List<CampaignRow> campaignsWithoutFingerprint(UUID systemId) {
        UUID owner = OwnerContext.require();
        return campaigns.values().stream()
                .filter(row -> row.ownerId().equals(owner) && row.nameFingerprint() == null
                        && (systemId == null || row.systemId().equals(systemId)) && ready(row))
                .sorted(Comparator.comparing(CampaignRow::id)).toList();
    }

    @Override
    public synchronized boolean fillCampaignFingerprint(UUID id, byte[] nameFingerprint) {
        Optional<CampaignRow> found = findCampaign(id).filter(row -> row.nameFingerprint() == null);
        found.ifPresent(before -> {
            UUID owner = ownerFor(before.ownerId());
            if (campaignTaken(owner, before.systemId(), nameFingerprint, id)) {
                throw new LibraryConflict(LibraryConflict.Reason.CAMPAIGN_NAME_TAKEN);
            }
            campaigns.put(id, new CampaignRow(id, owner, before.systemId(), before.name(), nameFingerprint,
                    before.archivedAt(), before.chosenAt(), before.version(), before.createdAt()));
            RollbackUndo.onRollback(() -> campaigns.put(id, before));
        });
        return found.isPresent();
    }

    /** Like the key on (owner, system, name fingerprint), which ignores a null fingerprint. */
    private boolean campaignTaken(UUID owner, UUID systemId, byte[] fingerprint, UUID except) {
        return fingerprint != null && campaigns.values().stream().anyMatch(other -> other.ownerId().equals(owner)
                && other.systemId().equals(systemId) && !other.id().equals(except)
                && Arrays.equals(other.nameFingerprint(), fingerprint));
    }

    @Override
    public Optional<SystemRow> findSystem(UUID id) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(systems.get(id)).filter(row -> row.ownerId().equals(owner) && !deleting.contains(id));
    }

    @Override
    public Optional<CampaignRow> findCampaign(UUID id) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(campaigns.get(id))
                .filter(row -> row.ownerId().equals(owner) && ready(row));
    }

    @Override
    public List<SystemRow> findSystems(java.util.Collection<UUID> ids) {
        return ids.stream().distinct().map(this::findSystem).flatMap(Optional::stream).toList();
    }

    @Override
    public List<CampaignRow> findCampaigns(java.util.Collection<UUID> ids) {
        return ids.stream().distinct().map(this::findCampaign).flatMap(Optional::stream).toList();
    }

    @Override
    public Optional<CampaignRow> currentCampaign() {
        UUID owner = OwnerContext.require();
        return campaigns.values().stream().filter(row -> row.ownerId().equals(owner) && ready(row) && !row.archived())
                .min(Comparator.comparing(CampaignRow::chosenAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(CampaignRow::createdAt).thenComparing(CampaignRow::id));
    }

    @Override
    public synchronized boolean markDeleting(UUID id, Instant at) {
        Optional<SystemRow> found = findSystem(id);
        found.ifPresent(before -> {
            ownerFor(before.ownerId());
            ByteBuffer id16 = ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                    .putLong(id.getLeastSignificantBits());
            systems.put(id, new SystemRow(id, before.ownerId(), before.name(), id16.array(), before.edition(),
                    before.description(), before.version() + 1, before.createdAt()));
            deleting.add(id);
            RollbackUndo.onRollback(() -> {
                deleting.remove(id);
                systems.put(id, before);
            });
        });
        return found.isPresent();
    }

    @Override
    public boolean deleting(UUID id) {
        UUID owner = OwnerContext.require();
        SystemRow row = systems.get(id);
        return row != null && row.ownerId().equals(owner) && deleting.contains(id);
    }

    @Override
    public List<UUID> campaignIdsOfDeleting(UUID systemId) {
        if (!deleting(systemId)) {
            return List.of();
        }
        return campaigns.values().stream().filter(row -> row.systemId().equals(systemId))
                .sorted(Comparator.comparing(CampaignRow::id).reversed()).map(CampaignRow::id).toList();
    }

    @Override
    public synchronized boolean deleteDeleting(UUID id) {
        if (!deleting(id)) {
            return false;
        }
        SystemRow system = systems.get(id);
        ownerFor(system.ownerId());
        for (CampaignRow campaign : List.copyOf(campaigns.values())) {
            if (campaign.systemId().equals(id)) {
                refuseWithShields(campaign.id());
            }
        }
        for (CampaignRow campaign : List.copyOf(campaigns.values())) {
            if (campaign.systemId().equals(id)) {
                campaigns.remove(campaign.id());
                boolean wasDeleting = deletingCampaigns.remove(campaign.id());
                RollbackUndo.onRollback(() -> {
                    campaigns.put(campaign.id(), campaign);
                    if (wasDeleting) {
                        deletingCampaigns.add(campaign.id());
                    }
                });
            }
        }
        systems.remove(id);
        deleting.remove(id);
        RollbackUndo.onRollback(() -> {
            systems.put(id, system);
            deleting.add(id);
        });
        return true;
    }

    @Override
    public synchronized boolean markCampaignDeleting(UUID id, Instant at) {
        Optional<CampaignRow> found = findCampaign(id);
        found.ifPresent(before -> {
            ownerFor(before.ownerId());
            ByteBuffer id16 = ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                    .putLong(id.getLeastSignificantBits());
            campaigns.put(id, new CampaignRow(id, before.ownerId(), before.systemId(), before.name(), id16.array(),
                    before.archivedAt(), before.chosenAt(), before.version() + 1, before.createdAt()));
            deletingCampaigns.add(id);
            RollbackUndo.onRollback(() -> {
                deletingCampaigns.remove(id);
                campaigns.put(id, before);
            });
        });
        return found.isPresent();
    }

    @Override
    public boolean campaignDeleting(UUID id) {
        UUID owner = OwnerContext.require();
        CampaignRow row = campaigns.get(id);
        return row != null && row.ownerId().equals(owner) && deletingCampaigns.contains(id);
    }

    @Override
    public synchronized boolean deleteDeletingCampaign(UUID id) {
        if (!campaignDeleting(id)) {
            return false;
        }
        CampaignRow campaign = campaigns.get(id);
        ownerFor(campaign.ownerId());
        refuseWithShields(id);
        campaigns.remove(id);
        deletingCampaigns.remove(id);
        RollbackUndo.onRollback(() -> {
            campaigns.put(id, campaign);
            deletingCampaigns.add(id);
        });
        return true;
    }

    @Override
    public synchronized void insertShield(ShieldRow row) {
        UUID owner = ownerFor(row.ownerId());
        CampaignRow campaign = campaigns.get(row.campaignId());
        if (shields.containsKey(row.id()) || campaign == null || !campaign.ownerId().equals(owner)) {
            throw new IllegalStateException("key or reference refused");
        }
        if (row.unnamed() && shields.values().stream()
                .anyMatch(other -> other.campaignId().equals(row.campaignId()) && other.unnamed())) {
            throw new LibraryConflict(LibraryConflict.Reason.UNNAMED_SHIELD_TAKEN);
        }
        if (shieldTaken(row.campaignId(), row.nameFingerprint(), row.id())) {
            throw new LibraryConflict(LibraryConflict.Reason.SHIELD_NAME_TAKEN);
        }
        shields.put(row.id(), row);
        RollbackUndo.onRollback(() -> shields.remove(row.id()));
    }

    @Override
    public Optional<ShieldRow> findShield(UUID campaignId, UUID id) {
        UUID owner = OwnerContext.require();
        return Optional.ofNullable(shields.get(id))
                .filter(row -> row.ownerId().equals(owner) && row.campaignId().equals(campaignId) && !row.deleting());
    }

    @Override
    public List<ShieldRow> shields(UUID campaignId, UUID after, int limit) {
        UUID owner = OwnerContext.require();
        return shields.values().stream()
                .filter(row -> row.ownerId().equals(owner) && row.campaignId().equals(campaignId) && !row.deleting()
                        && (after == null || row.id().compareTo(after) > 0))
                .sorted(Comparator.comparing(ShieldRow::id)).limit(Math.max(limit, 0)).toList();
    }

    @Override
    public List<ShieldRow> shieldsFrom(UUID campaignId, int offset, int limit) {
        return shields(campaignId, null, Integer.MAX_VALUE).stream().skip(Math.max(offset, 0)).limit(Math.max(limit, 0))
                .toList();
    }

    @Override
    public long shieldCount(UUID campaignId) {
        return shields(campaignId, null, Integer.MAX_VALUE).size();
    }

    @Override
    public boolean shieldNameTaken(UUID campaignId, byte[] nameFingerprint, UUID except) {
        OwnerContext.require();
        return shieldTaken(campaignId, nameFingerprint, except);
    }

    @Override
    public synchronized Optional<ShieldRow> renameShield(UUID campaignId, UUID id, long version, byte[] name,
                                                         byte[] nameFingerprint) {
        Optional<ShieldRow> found = findShield(campaignId, id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ShieldRow before = found.get();
        UUID owner = ownerFor(before.ownerId());
        if (before.version() != version) {
            throw new LibraryConflict(LibraryConflict.Reason.STALE_VERSION);
        }
        if (shieldTaken(campaignId, nameFingerprint, id)) {
            throw new LibraryConflict(LibraryConflict.Reason.SHIELD_NAME_TAKEN);
        }
        ShieldRow after = new ShieldRow(id, owner, campaignId, name.clone(), nameFingerprint.clone(), before.version() + 1,
                before.createdAt());
        shields.put(id, after);
        RollbackUndo.onRollback(() -> shields.put(id, before));
        return Optional.of(after);
    }

    @Override
    public synchronized int deleteShieldsOf(java.util.Collection<UUID> campaignIds) {
        UUID owner = OwnerContext.require();
        int deleted = 0;
        for (ShieldRow row : List.copyOf(shields.values())) {
            if (row.ownerId().equals(owner) && campaignIds.contains(row.campaignId())) {
                ownerFor(row.ownerId());
                shields.remove(row.id());
                RollbackUndo.onRollback(() -> shields.put(row.id(), row));
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    public Optional<CampaignRow> lockCampaign(UUID id) {
        OwnerContext.require();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("no transaction");
        }
        if (findCampaign(id).isEmpty()) {
            return Optional.empty();
        }
        ReentrantLock lock = campaignLocks.computeIfAbsent(id, key -> new ReentrantLock());
        lock.lock();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                lock.unlock();
            }
        });
        // As PostgreSQL reads the row again once the lock is granted: it may have become deleting meanwhile.
        return findCampaign(id);
    }

    @Override
    public synchronized boolean markShieldDeleting(UUID campaignId, UUID id, Instant at) {
        Optional<ShieldRow> found = findShield(campaignId, id);
        if (found.isEmpty()) {
            return false;
        }
        ShieldRow before = found.get();
        ownerFor(before.ownerId());
        byte[] fingerprint = before.unnamed() ? null : SystemRow.idBytes(id);
        ShieldRow after = new ShieldRow(id, before.ownerId(), campaignId, before.name(), fingerprint,
                before.version() + 1, before.createdAt(), at);
        shields.put(id, after);
        RollbackUndo.onRollback(() -> shields.put(id, before));
        return true;
    }

    @Override
    public boolean shieldDeleting(UUID id) {
        UUID owner = OwnerContext.require();
        ShieldRow row = shields.get(id);
        return row != null && row.ownerId().equals(owner) && row.deleting();
    }

    @Override
    public synchronized boolean deleteDeletingShield(UUID id) {
        if (!shieldDeleting(id)) {
            return false;
        }
        ShieldRow row = shields.get(id);
        ownerFor(row.ownerId());
        if (shieldHasNotes != null && shieldHasNotes.test(id)) {
            throw new IllegalStateException("record_shield_fkey");
        }
        shields.remove(id);
        RollbackUndo.onRollback(() -> shields.put(id, row));
        return true;
    }

    /** Like the key on (campaign, name fingerprint), which ignores a null fingerprint. */
    private boolean shieldTaken(UUID campaignId, byte[] fingerprint, UUID except) {
        return fingerprint != null && shields.values().stream().anyMatch(other -> other.campaignId().equals(campaignId)
                && !other.id().equals(except) && Arrays.equals(other.nameFingerprint(), fingerprint));
    }

    /** Like {@code shield_campaign_fkey}: a campaign that still has Shields cannot be deleted. */
    private void refuseWithShields(UUID campaignId) {
        if (shields.values().stream().anyMatch(row -> row.campaignId().equals(campaignId))) {
            throw new IllegalStateException("shield_campaign_fkey");
        }
    }

    /** Like the reads' filter: a ready campaign of a ready system. */
    private boolean ready(CampaignRow row) {
        return !deletingCampaigns.contains(row.id()) && !deleting.contains(row.systemId());
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
