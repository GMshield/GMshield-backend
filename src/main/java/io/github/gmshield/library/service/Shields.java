package io.github.gmshield.library.service;

import io.github.gmshield.library.data.LibraryConflict;
import io.github.gmshield.library.data.LibraryStore;
import io.github.gmshield.library.data.ShieldRow;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.Ids;
import io.github.gmshield.shared.service.OwnerContext;
import io.github.gmshield.vault.service.Binding;
import io.github.gmshield.vault.service.Vault;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Shields of the owner's campaigns (Story 6.1): a Shield (Escudo do Mestre) is the GM's private space in a campaign,
 * for notes (6.2) and dice (6.3, 6.4). Everything here acts for the owner of the work ({@link OwnerContext}): another
 * owner's campaign or Shield reads exactly like an unknown one, and so do a campaign being deleted and a Shield of
 * another campaign.
 *
 * <p>Every campaign has at least one Shield: {@link Library} inserts the first one with the campaign, and V53 gave one to
 * each campaign that existed. The first is stored unnamed (name null) until the GM renames it; the frontend shows it as
 * "Escudo do Mestre" / "GM's Shield" in the GM's language. A campaign that the previous release created after V53 has
 * none: listing its Shields creates the unnamed one first. The partial unique index (one unnamed Shield per campaign)
 * keeps two lists at once from both creating it; the one that loses reads again.
 *
 * <p>A typed name is 1 to {@value Library#NAME_MAX} code points after trimming ({@code request.shield_name_invalid},
 * 400), encrypted only through the vault, bound to the entity {@code shield}, the field {@code name}, the row and the
 * owner, and unique within its campaign by the keyed fingerprint of its {@link Library#uniqueForm}
 * ({@code request.shield_name_taken}, 409). A rename names the version it was made on ({@code request.stale_version},
 * 409, when it is stale). Lists are oldest first. Logs carry ids, counts and versions only.
 *
 * <p>Part B: the campaign's only Shield cannot be deleted ({@code request.last_shield}, 409). The deletion locks the
 * campaign's row before it counts, so two deletions at once cannot leave a campaign with none.
 *
 * <p>Story 6.2 part B: a Shield holds notes, so its deletion is the recorded, resumable process
 * ({@link SystemDeletion#deleteShield}). Under the campaign's lock the Shield is found live and not the last, becomes
 * deleting (its name free at once) and the deletion is recorded, in one transaction that also marks its live notes
 * deleting; from the commit it is "not found" to every read and count here. The preview counts its live notes
 * ({@link SystemDeletion#previewShield}) and, from Story 6.4, its stored rolls. {@link #lockedShield} gives the
 * {@code artifacts} module ({@link Rolls} too) the live Shield under the same lock, so a note or a roll is never created
 * in, or moved into, a Shield being deleted.
 */
@Service
public class Shields {

    static final String SHIELD = "shield";
    static final String FIELD_NAME = "name";
    private static final Logger log = LoggerFactory.getLogger(Shields.class);

    private final LibraryStore store;
    private final Vault vault;
    private final ObjectProvider<SystemDeletion> deletion;
    private final ObjectProvider<PlatformTransactionManager> transactions;
    private final Clock clock;

    Shields(LibraryStore store, Vault vault, ObjectProvider<SystemDeletion> deletion,
            ObjectProvider<PlatformTransactionManager> transactions, ObjectProvider<Clock> clock) {
        this.store = store;
        this.vault = vault;
        this.deletion = deletion;
        this.transactions = transactions;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /** A Shield: its name, decrypted (null while it is the campaign's unnamed first one), and its version. */
    public record Shield(UUID id, String name, long version) {
        @Override
        public String toString() {
            return "Shield[id=" + id + ", version=" + version + "]";
        }
    }

    /** One page of a campaign's Shields, the cursor of the next one (null on the last), and how many it has in all. */
    public record ShieldPage(List<Shield> items, UUID nextCursor, long total) {
    }

    /**
     * The Shields of the owner's campaign {@code campaignId}, oldest first, after the Shield {@code cursor} when it is
     * given, or after the {@code offset} oldest when that is above zero (never both), at most {@code limit} (1 to
     * {@value Library#MAX_LIMIT}); empty for an unknown campaign, another owner's or one being deleted. A campaign with no
     * Shield gets its unnamed first one before it is listed.
     */
    public Optional<ShieldPage> shields(UUID campaignId, UUID cursor, int offset, int limit) {
        Library.checkLimit(limit);
        Library.checkOffset(cursor, offset);
        Optional<Long> count = reading(() -> store.findCampaign(campaignId)
                .map(campaign -> store.shieldCount(campaign.id())));
        if (count.isEmpty()) {
            return Optional.empty();
        }
        if (count.get() == 0L) {
            ensureFirst(campaignId);
        }
        return reading(() -> store.findCampaign(campaignId).map(campaign -> {
            List<ShieldRow> found = offset > 0 ? store.shieldsFrom(campaign.id(), offset, limit + 1)
                    : store.shields(campaign.id(), cursor, limit + 1);
            List<ShieldRow> page = found.size() > limit ? found.subList(0, limit) : found;
            UUID next = found.size() > limit ? page.getLast().id() : null;
            return new ShieldPage(page.stream().map(this::open).toList(), next, store.shieldCount(campaign.id()));
        }));
    }

    /**
     * The owner's Shield {@code shieldId} of the campaign {@code campaignId}; empty for an unknown or malformed id,
     * another owner's, a Shield of another campaign or one of a campaign being deleted.
     */
    public Optional<Shield> shield(UUID campaignId, UUID shieldId) {
        return reading(() -> store.findCampaign(campaignId)
                .flatMap(campaign -> store.findShield(campaign.id(), shieldId)).map(this::open));
    }

    /**
     * The owner's live Shield {@code shieldId} of the campaign {@code campaignId}, found under the campaign's row lock
     * (Story 6.2 part B), which the caller's transaction (which must exist) holds until it ends: a Shield's deletion
     * takes the same lock, so while it is held the Shield found here cannot become deleting. Empty for any id
     * {@link #shield} does not find, and for a Shield that became deleting while this waited for the lock.
     */
    public Optional<Shield> lockedShield(UUID campaignId, UUID shieldId) {
        OwnerContext.require();
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("library.transaction_required");
        }
        return store.lockCampaign(campaignId).flatMap(campaign -> store.findShield(campaign.id(), shieldId))
                .map(this::open);
    }

    /**
     * A new Shield of the owner's campaign {@code campaignId}, named {@code typedName}. Empty, and nothing is stored, for
     * an unknown campaign, another owner's or one being deleted. Throws {@code request.shield_name_invalid} (400) for a
     * name outside its limits and {@code request.shield_name_taken} (409) when another Shield of the campaign has the same
     * name; nothing is stored then.
     */
    public Optional<Shield> createShield(UUID campaignId, String typedName) {
        String name = Library.name(typedName, LibraryProblems.SHIELD_NAME_INVALID);
        UUID owner = OwnerContext.require();
        UUID id = Ids.newId();
        byte[] fingerprint = fingerprint(name);
        Optional<Shield> created = writing(() -> store.findCampaign(campaignId).map(campaign -> {
            if (store.shieldNameTaken(campaign.id(), fingerprint, id)) {
                throw new ApiException(LibraryProblems.SHIELD_NAME_TAKEN);
            }
            ShieldRow row = new ShieldRow(id, owner, campaign.id(), vault.encryptText(binding(id, owner), name),
                    fingerprint, 0, now());
            conflicts(() -> {
                store.insertShield(row);
                return row;
            });
            return new Shield(id, name, row.version());
        }));
        created.ifPresent(shield -> log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("shieldId", id).log("library.shield_created"));
        return created;
    }

    /**
     * Renames the owner's Shield {@code shieldId} of the campaign {@code campaignId} on the version {@code version}:
     * the Shield as stored, its version raised by one; empty for any id {@link #shield} does not find. Throws
     * {@code request.shield_name_invalid} (400), {@code request.stale_version} (409) when the Shield is no longer at
     * {@code version}, and {@code request.shield_name_taken} (409) when another Shield of the campaign has the new name;
     * nothing changes then.
     */
    public Optional<Shield> renameShield(UUID campaignId, UUID shieldId, long version, String typedName) {
        String name = Library.name(typedName, LibraryProblems.SHIELD_NAME_INVALID);
        byte[] fingerprint = fingerprint(name);
        Optional<Shield> renamed = writing(() -> store.findCampaign(campaignId)
                .flatMap(campaign -> store.findShield(campaign.id(), shieldId)).map(row -> {
                    if (row.version() != version) {
                        throw new ApiException(LibraryProblems.STALE_VERSION);
                    }
                    if (store.shieldNameTaken(row.campaignId(), fingerprint, row.id())) {
                        throw new ApiException(LibraryProblems.SHIELD_NAME_TAKEN);
                    }
                    byte[] stored = vault.encryptText(binding(row.id(), row.ownerId()), name);
                    ShieldRow after = conflicts(() -> store.renameShield(row.campaignId(), row.id(), version, stored,
                            fingerprint)).orElseThrow(() -> new IllegalStateException("library.shield_vanished"));
                    return new Shield(after.id(), name, after.version());
                }));
        renamed.ifPresent(shield -> log.atInfo().addKeyValue("ownerId", OwnerContext.require())
                .addKeyValue("campaignId", campaignId).addKeyValue("shieldId", shieldId)
                .addKeyValue("version", shield.version()).log("library.shield_renamed"));
        return renamed;
    }

    /**
     * What goes with a Shield if it is deleted: how many live notes (Story 6.2 part B) and stored rolls (Story 6.4), and
     * whether it is its campaign's only live Shield, which cannot be deleted.
     */
    public record ShieldDeletion(long notes, long rolls, boolean last) {
    }

    /**
     * What goes with the owner's Shield {@code shieldId} of the campaign {@code campaignId} if it is deleted; empty for
     * any id {@link #shield} does not find.
     */
    public Optional<ShieldDeletion> shieldDeletion(UUID campaignId, UUID shieldId) {
        return reading(() -> store.findCampaign(campaignId)
                .flatMap(campaign -> store.findShield(campaign.id(), shieldId))
                .map(row -> new ShieldDeletion(deletion.getObject().previewShield(row.id()), store.rollCount(row.id()),
                        store.shieldCount(row.campaignId()) <= 1L)));
    }

    /**
     * Starts the deletion of the owner's Shield {@code shieldId} of the campaign {@code campaignId} (Story 6.2 part B),
     * as the class says: true once it is recorded (from the commit the Shield and its notes are not found; the pass
     * removes them), false for any id {@link #shield} does not find, a deleting Shield included. Throws
     * {@code request.last_shield} (409) when it is the campaign's only live Shield; nothing changes then. The campaign's
     * row is locked before the Shields are counted.
     */
    public boolean deleteShield(UUID campaignId, UUID shieldId) {
        Instant now = now();
        boolean recorded = Boolean.TRUE.equals(writing(() -> {
            if (store.lockCampaign(campaignId).isEmpty() || store.findShield(campaignId, shieldId).isEmpty()) {
                return Boolean.FALSE;
            }
            if (store.shieldCount(campaignId) <= 1L) {
                throw new ApiException(LibraryProblems.LAST_SHIELD);
            }
            if (!store.markShieldDeleting(campaignId, shieldId, now)) {
                throw new IllegalStateException("library.shield_vanished");
            }
            if (!deletion.getObject().deleteShield(shieldId)) {
                // Marked but not recorded, no pass would ever remove it: the mark rolls back.
                throw new IllegalStateException("library.shield_deletion_not_recorded");
            }
            return Boolean.TRUE;
        }));
        log.atInfo().addKeyValue("ownerId", OwnerContext.require()).addKeyValue("campaignId", campaignId)
                .addKeyValue("shieldId", shieldId).addKeyValue("recorded", recorded).log("library.shield_delete");
        return recorded;
    }

    /**
     * The campaign's unnamed first Shield, inserted in a transaction of its own when the campaign is still ready and has
     * no Shield (a campaign the previous release created after V53). When another request inserted it meanwhile, the
     * partial unique index refuses this one: nothing is stored here, and the caller reads again.
     */
    private void ensureFirst(UUID campaignId) {
        UUID owner = OwnerContext.require();
        UUID id = Ids.newId();
        try {
            boolean inserted = Boolean.TRUE.equals(writing(() -> {
                if (store.findCampaign(campaignId).isEmpty() || store.shieldCount(campaignId) > 0) {
                    return Boolean.FALSE;
                }
                store.insertShield(ShieldRow.unnamed(id, owner, campaignId, now()));
                return Boolean.TRUE;
            }));
            if (inserted) {
                log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                        .addKeyValue("shieldId", id).log("library.first_shield_created");
            }
        } catch (LibraryConflict conflict) {
            if (conflict.reason() != LibraryConflict.Reason.UNNAMED_SHIELD_TAKEN) {
                throw conflict;
            }
            log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                    .log("library.first_shield_raced");
        }
    }

    /** A Shield's name fingerprint: of the name's unique form. */
    private byte[] fingerprint(String name) {
        return vault.fingerprint(SHIELD, FIELD_NAME, Library.uniqueForm(name));
    }

    private Shield open(ShieldRow row) {
        String name = row.unnamed() ? null : vault.decryptText(binding(row.id(), row.ownerId()), row.name());
        return new Shield(row.id(), name, row.version());
    }

    /** A store write whose conflict becomes its problem: a taken name, or a version that moved meanwhile. */
    private static <T> T conflicts(Supplier<T> write) {
        try {
            return write.get();
        } catch (LibraryConflict conflict) {
            throw new ApiException(switch (conflict.reason()) {
                case SHIELD_NAME_TAKEN -> LibraryProblems.SHIELD_NAME_TAKEN;
                case STALE_VERSION -> LibraryProblems.STALE_VERSION;
                default -> throw conflict;
            });
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** One read-only transaction for the reads of one answer, so they come from one session. */
    private <T> T reading(Supplier<T> read) {
        OwnerContext.require();
        TransactionTemplate template = new TransactionTemplate(transactions.getObject());
        template.setReadOnly(true);
        return template.execute(status -> read.get());
    }

    /** One transaction for a write and its checks: a problem thrown inside rolls everything back. */
    private <T> T writing(Supplier<T> write) {
        OwnerContext.require();
        return new TransactionTemplate(transactions.getObject()).execute(status -> write.get());
    }

    private static Binding binding(UUID rowId, UUID owner) {
        return new Binding(SHIELD, FIELD_NAME, rowId, owner);
    }
}
