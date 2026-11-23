package io.github.gmshield.knowledge.data;

/**
 * A write of a record type that the stored rows refuse (Story 4.1): another record type of the system has this name
 * fingerprint, the system already has this core key, or the row's version moved since the version the change names. Its
 * message is the reason only, never a value. The write's transaction cannot commit after it.
 */
public class KnowledgeConflict extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the write was refused. */
    public enum Reason {
        /** The key on (owner, system, name fingerprint) is taken by another record type of the system. */
        NAME_TAKEN,
        /** The key on (owner, system, core) is taken: the system already has this core type. */
        CORE_TAKEN,
        /** The row is no longer at the version the change names. */
        STALE_VERSION
    }

    private final Reason reason;

    public KnowledgeConflict(Reason reason) {
        super("knowledge." + reason.name().toLowerCase(java.util.Locale.ROOT));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
