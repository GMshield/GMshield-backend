package io.github.gmshield.shared.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmshield.shared.data.OwnerTenantResolver;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The owner of the work: bound for one piece of work only, never twice, and required by every library query. */
class OwnerContextTest {

    private static final UUID A = UUID.fromString("0190a0b0-0000-7000-8000-00000000000a");
    private static final UUID B = UUID.fromString("0190a0b0-0000-7000-8000-00000000000b");

    @Test
    void anOwnerIsBoundForItsWorkOnlyAlsoWhenTheWorkFails() throws Exception {
        assertThat(OwnerContext.current()).isEmpty();
        assertThat(OwnerContext.callAs(A, OwnerContext::require)).isEqualTo(A);
        assertThat(OwnerContext.current()).isEmpty();

        assertThatThrownBy(() -> OwnerContext.callAs(A, () -> {
            throw new IOException("failed");
        })).isInstanceOf(IOException.class);
        assertThat(OwnerContext.current()).isEmpty();
        assertThatThrownBy(() -> OwnerContext.runAs(A, () -> {
            throw new IllegalArgumentException("failed");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(OwnerContext.current()).isEmpty();
    }

    @Test
    void withoutAnOwnerTheContextFailsClosed() {
        assertThatThrownBy(OwnerContext::require).isInstanceOf(OwnerContext.NoOwnerException.class)
                .hasMessage("owner.missing");
        assertThatThrownBy(() -> OwnerContext.callAs(null, () -> 1)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void anOwnerIsNeverBoundInsideAnotherOwnersWork() {
        assertThatThrownBy(() -> OwnerContext.runAs(A, () -> OwnerContext.runAs(B, () -> { })))
                .isInstanceOf(OwnerContext.NoOwnerException.class).hasMessage("owner.already_bound");
        assertThatThrownBy(() -> OwnerContext.runAs(A, () -> OwnerContext.runAs(A, () -> { })))
                .isInstanceOf(OwnerContext.NoOwnerException.class);
        assertThat(OwnerContext.current()).isEmpty();
    }

    @Test
    void theTenantResolverAnswersTheOwnerAndThrowsWithoutOne() throws Exception {
        OwnerTenantResolver resolver = new OwnerTenantResolver();

        assertThat(OwnerContext.callAs(B, resolver::resolveCurrentTenantIdentifier)).isEqualTo(B);
        assertThatThrownBy(resolver::resolveCurrentTenantIdentifier).isInstanceOf(OwnerContext.NoOwnerException.class);
        assertThat(resolver.isRoot(A)).isFalse();
        assertThat(OwnerContext.callAs(A, () -> resolver.isRoot(A))).isFalse();
    }
}
