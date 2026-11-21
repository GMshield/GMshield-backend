package io.github.gmshield.library.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What a module does when one of the owner's systems is deleted (Story 3.1 part B, AD-22). {@link SystemDeletion}
 * finds every handler registered as a bean and calls it as the owner of the work, so {@code library} depends on no
 * module that implements it ({@code materials}, {@code conversations}). Each call names what goes, collected again at
 * each call: the system and its campaigns.
 *
 * <ul>
 *   <li>{@link #count}: how many of its own items go, for the confirmation dialog; inside a read-only transaction.</li>
 *   <li>{@link #prepare}: in the GM's transaction (and again at the start of each removal pass), it cancels the jobs of
 *       what goes and hides it from every list; it answers the latest time a claim of a job it cancelled lapses, when
 *       one was running. Nothing is removed yet.</li>
 *   <li>{@link #remove}: after every such claim has lapsed, outside any transaction, it removes its rows and files,
 *       each step safe to repeat. A failure (thrown) leaves the deletion for the next pass.</li>
 * </ul>
 */
public interface SystemDeletionHandler {

    /** What goes: the owner's system and the ids of all its campaigns. */
    record Going(UUID systemId, List<UUID> campaignIds) {

        public Going {
            campaignIds = List.copyOf(campaignIds);
        }
    }

    /** The name of the count this handler gives: {@code conversations} or {@code materials}. */
    String part();

    /** How many of this module's items go with the system. */
    long count(Going going);

    /** Cancels and hides what goes, in the caller's transaction; the latest claim lapse of what it cancelled, if any. */
    Optional<Instant> prepare(Going going);

    /** Removes what goes, in transactions of its own; safe to repeat. */
    void remove(Going going);
}
