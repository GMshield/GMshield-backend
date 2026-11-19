package io.github.gmshield.limits.data;

/** {@code model_call_usage} (Story 2.4). Writes join the caller's transaction. */
public interface UsageStore {

    /** Writes the row unless one with its call id exists; whether it was written. */
    boolean insertOnce(UsageRow row);
}
