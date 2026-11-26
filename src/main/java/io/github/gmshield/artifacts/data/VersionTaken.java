package io.github.gmshield.artifacts.data;

/**
 * A version's number that its artifact already has (Story 5.2): another edit or restore of the same artifact stored it
 * first. The service answers it as a stale version.
 */
public class VersionTaken extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public VersionTaken(Throwable cause) {
        super("artifacts.version_taken", cause);
    }
}
