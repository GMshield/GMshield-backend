package io.github.gmshield.library.data;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
import org.hibernate.StaleObjectStateException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;

/**
 * Story 3.1: what a failed write of a system stands for, read from the failure and its causes. A version that moved is a
 * stale version, the owner's name key is a taken name, and anything else stays the failure it was.
 */
class LibraryConflictTest {

    @Test
    void aMovedVersionIsStaleWhereverItIsInTheChain() {
        assertThat(JpaLibraryStore.conflictOf(new OptimisticLockException("row moved")))
                .hasValueSatisfying(conflict -> assertThat(conflict.reason()).isEqualTo(LibraryConflict.Reason.STALE_VERSION));
        assertThat(JpaLibraryStore.conflictOf(new PersistenceException(new StaleObjectStateException("LibrarySystem", 1))))
                .hasValueSatisfying(conflict -> assertThat(conflict.reason()).isEqualTo(LibraryConflict.Reason.STALE_VERSION));
    }

    @Test
    void theNameKeyIsATakenNameAndAnyOtherFailureIsNoConflict() {
        SQLException sql = new SQLException("duplicate key value violates unique constraint", "23505");
        assertThat(JpaLibraryStore.conflictOf(new PersistenceException(
                new ConstraintViolationException("insert refused", sql, "system_owner_name_fingerprint_key"))))
                .hasValueSatisfying(conflict -> {
                    assertThat(conflict.reason()).isEqualTo(LibraryConflict.Reason.NAME_TAKEN);
                    assertThat(conflict).hasMessage("library.name_taken").hasNoCause();
                });
        assertThat(JpaLibraryStore.conflictOf(new ConstraintViolationException("insert refused", sql, "system_pkey"))).isEmpty();
        assertThat(JpaLibraryStore.conflictOf(new ConstraintViolationException("insert refused", sql, null))).isEmpty();
        assertThat(JpaLibraryStore.conflictOf(new IllegalStateException("other"))).isEmpty();
        assertThat(new LibraryConflict(LibraryConflict.Reason.STALE_VERSION)).hasMessage("library.stale_version");
    }
}
