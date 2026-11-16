package io.github.gmshield.shared.service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The owner whose library the current work may read and write (AD-11). Library queries are filtered by it
 * (Hibernate {@code @TenantId}, read by {@code shared.data.OwnerTenantResolver}); without one they never run.
 *
 * <p>It is bound for the length of one piece of work and gone afterwards, whatever happens: a
 * {@link ScopedValue}, never a field a later request could find. Two places bind it, both through
 * {@link #callAs}: the request edge of the library routes ({@code accounts.web}), from the session, and
 * work that runs as one owner, such as a first sign-in creating the new account's starter library. It never
 * comes from a request parameter or body. Binding it again inside a bound piece of work is refused: one piece
 * of work has one owner.
 */
public final class OwnerContext {

    private static final ScopedValue<UUID> OWNER = ScopedValue.newInstance();

    private OwnerContext() {
    }

    /** Work that runs as one owner. */
    @FunctionalInterface
    public interface Work<R, X extends Exception> {
        R run() throws X;
    }

    /** No owner is bound: a library query was attempted outside any owner's work. Its message names nothing. */
    public static final class NoOwnerException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        NoOwnerException(String reason) {
            super(reason);
        }
    }

    /** Runs {@code work} as {@code owner}, then unbinds it, also when the work throws. */
    public static <R, X extends Exception> R callAs(UUID owner, Work<R, X> work) throws X {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(work, "work");
        if (OWNER.isBound()) {
            throw new NoOwnerException("owner.already_bound");
        }
        return ScopedValue.where(OWNER, owner).call(work::run);
    }

    /** {@link #callAs} for work without a result or a checked exception. */
    public static void runAs(UUID owner, Runnable work) {
        Objects.requireNonNull(work, "work");
        callAs(owner, () -> {
            work.run();
            return null;
        });
    }

    /** The bound owner, if any. */
    public static Optional<UUID> current() {
        return OWNER.isBound() ? Optional.of(OWNER.get()) : Optional.empty();
    }

    /** The bound owner; fails closed with {@link NoOwnerException} when there is none. */
    public static UUID require() {
        if (!OWNER.isBound()) {
            throw new NoOwnerException("owner.missing");
        }
        return OWNER.get();
    }
}
