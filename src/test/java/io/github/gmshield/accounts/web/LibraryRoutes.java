package io.github.gmshield.accounts.web;

import java.util.List;

/**
 * The route prefixes of {@link LibraryOwnerFilter} (package-private in the application), for the cross-owner route
 * guard in another package (Story 3.13 part C).
 */
public final class LibraryRoutes {

    private LibraryRoutes() {
    }

    /** {@code LibraryOwnerFilter.LIBRARY_ROUTES}: a request under one of these is the account of its session. */
    public static List<String> prefixes() {
        return LibraryOwnerFilter.LIBRARY_ROUTES;
    }
}
