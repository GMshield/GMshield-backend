package io.github.gmshield.materials.data;

import java.util.function.Function;

/**
 * Two ways to reach {@code upload}, as for {@code job}. {@link #joined()} joins the caller's transaction (the owner's
 * transaction that also writes the material and its version) or, outside one, runs each statement on its own.
 * {@link #separately} runs in a transaction of its own on a connection of its own, for the sweep, which has no owner
 * and so cannot open the application's (JPA) transaction. Inside {@code separately}, use only the store it hands over.
 */
public interface UploadStorage {

    UploadStore joined();

    /** Runs {@code work} in a new transaction of its own; an exception rolls back everything it wrote. */
    <T> T separately(Function<UploadStore, T> work);
}
