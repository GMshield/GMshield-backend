package io.github.gmshield.library.data;

/**
 * A write of the library that the stored rows refuse (Story 3.1): another system of the owner already has this name
 * fingerprint, or the row's version moved since the version the edit names. Its message is the reason only, never a
 * value. The write's transaction cannot commit after it.
 */
public class LibraryConflict extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the write was refused. */
    public enum Reason {
        /** The owner's key on the name fingerprint is taken by another system. */
        NAME_TAKEN,
        /** The row is no longer at the version the edit names. */
        STALE_VERSION
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
