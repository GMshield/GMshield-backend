package io.github.gmshield.library.data;

/**
 * A write of the library that the stored rows refuse (Story 3.1): another system of the owner already has this name
 * fingerprint, another campaign of the system has this name fingerprint (Story 3.2), another Shield of the campaign has
 * this name fingerprint or the campaign has an unnamed Shield already (Story 6.1), or the row's version moved since
 * the version the edit names. Its message is the reason only, never a
 * value. The write's transaction cannot commit after it.
 */
public class LibraryConflict extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the write was refused. */
    public enum Reason {
        /** The owner's key on the name fingerprint is taken by another system. */
        NAME_TAKEN,
        /** The key on (owner, system, name fingerprint) is taken by another campaign of the system (Story 3.2). */
        CAMPAIGN_NAME_TAKEN,
        /** The row is no longer at the version the edit names. */
        STALE_VERSION,
        /** The key on (campaign, name fingerprint) is taken by another Shield of the campaign (Story 6.1). */
        SHIELD_NAME_TAKEN,
        /**
         * The campaign has an unnamed Shield already (Story 6.1: the partial unique index on {@code shield.campaign_id}
         * where the name is null): another request created its first Shield meanwhile.
         */
        UNNAMED_SHIELD_TAKEN
    }

    private final Reason reason;

    public LibraryConflict(Reason reason) {
        super("library." + reason.name().toLowerCase(java.util.Locale.ROOT));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
