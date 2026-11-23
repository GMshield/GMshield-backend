package io.github.gmshield.scope.data;

/**
 * A change of a campaign's scope settings named a version they have left (Story 3.5), or another change inserted its
 * first row meanwhile. The write's transaction cannot commit after it.
 */
public class StaleScopeVersion extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StaleScopeVersion() {
        super("scope.stale_version");
    }
}
