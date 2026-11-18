package io.github.gmshield.library.data;

import java.util.Optional;
import java.util.UUID;

/**
 * The library's tables, always within the owner of the work: every read sees that owner's rows only, and an
 * insert for another owner is refused. Lookups are exact, by id: there is no text search.
 */
public interface LibraryStore {

    /** Stores a new system of the current owner; joins the caller's transaction, which must exist. */
    void insertSystem(SystemRow row);

    /** Stores a new campaign of the current owner; joins the caller's transaction, which must exist. */
    void insertCampaign(CampaignRow row);

    /** The current owner's system with this id; empty for an unknown id or another owner's. */
    Optional<SystemRow> findSystem(UUID id);

    /** The current owner's campaign with this id; empty for an unknown id or another owner's. */
    Optional<CampaignRow> findCampaign(UUID id);

    /** The current owner's oldest campaign (the earliest id on a tie), if the owner has one. */
    Optional<CampaignRow> firstCampaign();
}
