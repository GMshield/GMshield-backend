package io.github.gmshield.library.data;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The library's tables, always within the owner of the work: every read sees that owner's rows only, and an
 * insert for another owner is refused. Lookups are exact, by id: there is no text search.
 *
 * <p>From Story 3.1 part B a system is {@code ready} or {@code deleting}: every read here sees ready systems, and the
 * campaigns of ready systems, only; a deleting system and its campaigns are reached through the deletion's own
 * methods ({@link #markDeleting}, {@link #deleting}, {@link #campaignIdsOfDeleting}, {@link #deleteDeleting}) alone.
 *
 * <p>From Story 3.2 part B a campaign is {@code ready} or {@code deleting} too: every read here sees ready campaigns of
 * ready systems only; a deleting campaign is reached through its deletion's methods ({@link #markCampaignDeleting},
 * {@link #campaignDeleting}, {@link #deleteDeletingCampaign}), and through a system's deletion, which takes every
 * campaign of the system, deleting ones included.
 *
 * <p>From Story 6.1 a campaign has Shields ({@code shield}, V53): the methods below take a campaign id that the caller has
 * found ready in the same transaction; they never check the campaign's state themselves. {@link #deleteShieldsOf} alone
 * reaches the Shields of any campaign of the owner, for the deletions.
 *
 * <p>From Story 6.2 part B (V55) a Shield is live or deleting: every Shield read and count here sees live Shields only; a
 * deleting one is reached through its deletion's methods ({@link #shieldDeleting}, {@link #deleteDeletingShield}) and
 * through {@link #deleteShieldsOf}.
 */
public interface LibraryStore {

    /**
     * Stores a new system of the current owner; joins the caller's transaction, which must exist. Throws
     * {@link LibraryConflict} ({@code NAME_TAKEN}) when another system of the owner has its name fingerprint.
     */
    void insertSystem(SystemRow row);

    /**
     * Writes an edit of the current owner's system (Story 3.1) and answers the row as stored, its version raised by one;
     * empty for an unknown id or another owner's. Throws {@link LibraryConflict}: {@code STALE_VERSION} when the row is
     * not at {@code change.version()} (nothing is written), {@code NAME_TAKEN} when another system of the owner has
     * the new name fingerprint. Joins the caller's transaction, which must exist.
     */
    Optional<SystemRow> updateSystem(SystemChange change);

    /** Whether a system of the current owner other than {@code except} has this name fingerprint. */
    boolean systemNameTaken(byte[] nameFingerprint, UUID except);

    /**
     * The current owner's systems, newest first (descending id, a UUID v7), only those whose id is below
     * {@code before} when it is given, at most {@code limit}.
     */
    List<SystemRow> systems(UUID before, int limit);

    /**
     * The current owner's campaigns of ready systems (Story 3.2), of the system {@code systemId} only when it is given,
     * archived ones when {@code archived} is true and the others when it is false; newest first (descending id), only
     * those whose id is below {@code before} when it is given, at most {@code limit}; empty for another owner's system.
     */
    List<CampaignRow> campaigns(UUID systemId, boolean archived, UUID before, int limit);

    /**
     * As {@link #systems}, by position instead of by cursor (a numbered page): the systems after the {@code offset}
     * newest ones, at most {@code limit}.
     */
    List<SystemRow> systemsFrom(int offset, int limit);

    /** How many systems {@link #systems} lists in all. */
    long systemCount();

    /**
     * As {@link #campaigns}, by position instead of by cursor: the campaigns after the {@code offset} newest ones, at
     * most {@code limit}.
     */
    List<CampaignRow> campaignsFrom(UUID systemId, boolean archived, int offset, int limit);

    /** How many campaigns {@link #campaigns} lists in all, for the same system and the same {@code archived}. */
    long campaignCount(UUID systemId, boolean archived);

    /**
     * The ids of all the current owner's ready campaigns of the ready system {@code systemId}, archived ones included,
     * newest first.
     */
    List<UUID> campaignIdsOf(UUID systemId);

    /**
     * Stores a new campaign of the current owner; joins the caller's transaction, which must exist. Throws
     * {@link LibraryConflict} ({@code CAMPAIGN_NAME_TAKEN}) when another campaign of the system has its name fingerprint.
     */
    void insertCampaign(CampaignRow row);

    /**
     * Writes an edit of the current owner's campaign (Story 3.2) and answers the row as stored, its version raised by
     * one; empty for an unknown id, another owner's or a campaign of a deleting system. Throws {@link LibraryConflict}:
     * {@code STALE_VERSION} when the row is not at {@code change.version()} (nothing is written),
     * {@code CAMPAIGN_NAME_TAKEN} when another campaign of the system has the new name fingerprint. Joins the caller's
     * transaction, which must exist.
     */
    Optional<CampaignRow> updateCampaign(CampaignChange change);

    /**
     * The current owner's campaign {@code id}, of a ready system and not archived, was chosen at {@code at} (Story 3.2):
     * its version stays. False, and nothing changes, for any other id. Joins the caller's transaction, which must exist.
     */
    boolean chooseCampaign(UUID id, Instant at);

    /** Whether a campaign of the current owner's system {@code systemId} other than {@code except} has this fingerprint. */
    boolean campaignNameTaken(UUID systemId, byte[] nameFingerprint, UUID except);

    /**
     * The current owner's campaigns of ready systems that have no name fingerprint yet (rows from before V23), of the
     * system {@code systemId} only when it is given, archived ones included.
     */
    List<CampaignRow> campaignsWithoutFingerprint(UUID systemId);

    /**
     * Fills the missing name fingerprint of the current owner's campaign {@code id}; its version stays. False when there
     * is no such campaign or it already has one. Joins the caller's transaction, which must exist.
     */
    boolean fillCampaignFingerprint(UUID id, byte[] nameFingerprint);

    /** The current owner's system with this id; empty for an unknown id or another owner's. */
    Optional<SystemRow> findSystem(UUID id);

    /** The current owner's campaign with this id, archived or not; empty for an unknown id or another owner's. */
    Optional<CampaignRow> findCampaign(UUID id);

    /**
     * The current owner's ready systems with these ids, in one read (Story 3.3: the places of a page of materials), in no
     * set order; an unknown id, another owner's or a deleting system is left out.
     */
    List<SystemRow> findSystems(Collection<UUID> ids);

    /**
     * The current owner's ready campaigns of ready systems with these ids, archived or not, in one read (Story 3.3), in
     * no set order; an unknown id, another owner's or a deleting campaign is left out.
     */
    List<CampaignRow> findCampaigns(Collection<UUID> ids);

    /**
     * The current owner's current campaign (Story 3.2): of the ready, not archived campaigns, the one chosen last, else
     * the oldest (the earliest id on a tie); empty when there is none.
     */
    Optional<CampaignRow> currentCampaign();

    // --- deletion (Story 3.1 part B)

    /**
     * The current owner's ready system {@code id}, locked first, becomes {@code deleting} at {@code at}, its name
     * fingerprint replaced by its id (so the name is free at once); false for an unknown id, another owner's or a system
     * already deleting. Joins the caller's transaction, which must exist.
     */
    boolean markDeleting(UUID id, Instant at);

    /** Whether the current owner's system {@code id} is deleting. */
    boolean deleting(UUID id);

    /**
     * The ids of every campaign of the current owner's deleting system {@code systemId}, newest first; empty when the
     * system is not the owner's or not deleting.
     */
    List<UUID> campaignIdsOfDeleting(UUID systemId);

    /**
     * Deletes the current owner's deleting system {@code id} with its campaigns, which nothing may name any more; false
     * when there is no such system. Joins the caller's transaction, which must exist.
     */
    boolean deleteDeleting(UUID id);

    // --- campaign deletion (Story 3.2 part B)

    /**
     * The current owner's ready campaign {@code id} of a ready system, locked first, becomes {@code deleting} at
     * {@code at}, its name fingerprint replaced by its id (so the name is free in its system at once); false for an
     * unknown id, another owner's, a campaign already deleting or one of a deleting system. Joins the caller's
     * transaction, which must exist.
     */
    boolean markCampaignDeleting(UUID id, Instant at);

    /** Whether the current owner's campaign {@code id} is deleting (false once its row is gone). */
    boolean campaignDeleting(UUID id);

    /**
     * Deletes the current owner's deleting campaign {@code id}, which nothing may name any more; false when there is no
     * such campaign (a system's deletion may have taken it). Joins the caller's transaction, which must exist.
     */
    boolean deleteDeletingCampaign(UUID id);

    // --- Shields (Story 6.1)

    /**
     * Stores a new Shield of the current owner's campaign; joins the caller's transaction, which must exist. Throws
     * {@link LibraryConflict}: {@code SHIELD_NAME_TAKEN} when another Shield of the campaign has its name fingerprint,
     * {@code UNNAMED_SHIELD_TAKEN} when it is unnamed and the campaign has an unnamed Shield already.
     */
    void insertShield(ShieldRow row);

    /** The current owner's Shield {@code id} of the campaign {@code campaignId}; empty for any other id. */
    Optional<ShieldRow> findShield(UUID campaignId, UUID id);

    /**
     * The current owner's Shields of the campaign {@code campaignId}, oldest first (ascending id, a UUID v7), only those
     * whose id is above {@code after} when it is given, at most {@code limit}.
     */
    List<ShieldRow> shields(UUID campaignId, UUID after, int limit);

    /** As {@link #shields}, by position instead of by cursor: the Shields after the {@code offset} oldest, at most {@code limit}. */
    List<ShieldRow> shieldsFrom(UUID campaignId, int offset, int limit);

    /** How many Shields the current owner's campaign {@code campaignId} has. */
    long shieldCount(UUID campaignId);

    /** Whether a Shield of the campaign {@code campaignId} other than {@code except} has this name fingerprint. */
    boolean shieldNameTaken(UUID campaignId, byte[] nameFingerprint, UUID except);

    /**
     * Renames the current owner's Shield {@code id} of the campaign {@code campaignId} and answers the row as stored, its
     * version raised by one; empty for any other id. Throws {@link LibraryConflict}: {@code STALE_VERSION} when the row
     * is not at {@code version} (nothing is written), {@code SHIELD_NAME_TAKEN} when another Shield of the campaign has
     * the new name fingerprint. Joins the caller's transaction, which must exist.
     */
    Optional<ShieldRow> renameShield(UUID campaignId, UUID id, long version, byte[] name, byte[] nameFingerprint);

    /**
     * Deletes every Shield of the current owner's campaigns {@code campaignIds}, whatever their state (a campaign's or a
     * system's deletion, before the campaign rows go); answers how many went. Joins the caller's transaction, which must
     * exist.
     */
    int deleteShieldsOf(Collection<UUID> campaignIds);

    // --- Shield deletion (Story 6.1 part B)

    /**
     * The current owner's ready campaign {@code id} of a ready system, its row locked until the caller's transaction ends
     * (which must exist), so that two deletions of its Shields run one after the other; empty for any other id. A
     * campaign that became deleting while this waited for the lock is empty too.
     */
    Optional<CampaignRow> lockCampaign(UUID id);

    // --- Shield deletion as a recorded process (Story 6.2 part B, V55)

    /**
     * The current owner's live Shield {@code id} of the campaign {@code campaignId} becomes deleting at {@code at}: from
     * the commit every Shield read and count above leaves it out, and a named Shield's name fingerprint becomes its own
     * id (16 bytes), so its name is free in its campaign at once. False for any other id, a deleting one included.
     * Joins the caller's transaction, which must exist (and in which the caller locked the campaign).
     */
    boolean markShieldDeleting(UUID campaignId, UUID id, Instant at);

    /** Whether the current owner's Shield {@code id} is deleting, whatever its campaign's state (false once it is gone). */
    boolean shieldDeleting(UUID id);

    /**
     * Deletes the current owner's deleting Shield {@code id}, which no note may name any more; false when there is no
     * such Shield (a campaign's or a system's deletion may have taken it). Joins the caller's transaction, which must
     * exist.
     */
    boolean deleteDeletingShield(UUID id);
}
