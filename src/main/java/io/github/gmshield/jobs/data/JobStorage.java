package io.github.gmshield.jobs.data;

import java.util.function.Function;

/**
 * Two ways to reach {@code job}. {@link #joined()} joins the caller's transaction: the transaction that creates a
 * job, or the owner's transaction in which a handler runs. {@link #separately} runs on a connection of its own, in
 * a transaction of its own that never joins the caller's, because a JPA transaction needs an owner and the
 * backend's own bookkeeping (offers, renewals, the sweep) has none. Inside {@code separately}, use only the store
 * it hands over.
 */
public interface JobStorage {

    JobStore joined();

    /** Runs {@code work} in a new transaction of its own; an exception rolls back everything it wrote. */
    <T> T separately(Function<JobStore, T> work);
}
