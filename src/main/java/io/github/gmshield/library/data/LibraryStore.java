package io.github.gmshield.library.data;

import java.time.Instant;
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
}
