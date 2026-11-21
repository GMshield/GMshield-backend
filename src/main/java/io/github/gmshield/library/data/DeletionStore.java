package io.github.gmshield.library.data;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code deletion} (Story 3.1 part B, V22). Not a library table (AD-7): it holds ids and times only, so it is read and
 * written through JDBC, without {@code @TenantId}. Every write and every read by id names the owner, and joins the
 * caller's transaction when there is one; {@link #due} alone reads across owners, for the removal sweep, which then
 * works as each owner.
 */
public interface DeletionStore {

    /** Records a deletion; joins the caller's transaction, which must exist. */
    void insert(DeletionRow row);

    /** The owner's deletion with this id, done or not; empty for an unknown id or another owner's. */
    Optional<DeletionRow> find(UUID ownerId, UUID id);

    /** Moves the owner's pending deletion's {@code notBefore} later; false when it is unknown, another's or done. */
    boolean postpone(UUID ownerId, UUID id, Instant notBefore);

    /** The owner's pending deletion is done at {@code at}; false when it is unknown, another's or done already. */
    boolean done(UUID ownerId, UUID id, Instant at);

    /** At most {@code limit} pending deletions of every owner whose removal may start at {@code now}, earliest first. */
    List<DeletionRow> due(Instant now, int limit);
}
