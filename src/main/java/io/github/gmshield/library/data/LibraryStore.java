package io.github.gmshield.library.data;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The library's tables, always within the owner of the work: every read sees that owner's rows only, and an
 * insert for another owner is refused. Lookups are exact, by id: there is no text search.
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
     * The current owner's campaigns of the system {@code systemId}, newest first (descending id), only those whose id
     * is below {@code before} when it is given, at most {@code limit}; empty for another owner's system.
     */
    List<CampaignRow> campaignsOf(UUID systemId, UUID before, int limit);

    /** The ids of all the current owner's campaigns of the system {@code systemId}, newest first. */
    List<UUID> campaignIdsOf(UUID systemId);

    /** Stores a new campaign of the current owner; joins the caller's transaction, which must exist. */
    void insertCampaign(CampaignRow row);

    /** The current owner's system with this id; empty for an unknown id or another owner's. */
    Optional<SystemRow> findSystem(UUID id);

    /** The current owner's campaign with this id; empty for an unknown id or another owner's. */
    Optional<CampaignRow> findCampaign(UUID id);

    /** The current owner's oldest campaign (the earliest id on a tie), if the owner has one. */
    Optional<CampaignRow> firstCampaign();
}
