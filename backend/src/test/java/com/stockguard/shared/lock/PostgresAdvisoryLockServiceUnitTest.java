package com.stockguard.shared.lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying advisory-lock exception propagation, suppressed exception handling,
 * and connection abort quarantine per ADR-012.
 */
class PostgresAdvisoryLockServiceUnitTest {

    private DataSource mockDataSource;
    private Connection mockConnection;
    private PreparedStatement mockLockStmt;
    private ResultSet mockLockRs;
    private PreparedStatement mockUnlockStmt;
    private ResultSet mockUnlockRs;
    private PostgresAdvisoryLockService lockService;

    private static final long LOCK_KEY = 0x53544B4752445243L;

    @BeforeEach
    void setUp() throws SQLException {
        mockDataSource = mock(DataSource.class);
        mockConnection = mock(Connection.class);
        mockLockStmt = mock(PreparedStatement.class);
        mockLockRs = mock(ResultSet.class);
        mockUnlockStmt = mock(PreparedStatement.class);
        mockUnlockRs = mock(ResultSet.class);

        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(mockLockStmt);
        when(mockLockStmt.executeQuery()).thenReturn(mockLockRs);
        when(mockLockRs.next()).thenReturn(true);
        when(mockLockRs.getBoolean(1)).thenReturn(true); // Lock acquired

        when(mockConnection.prepareStatement("SELECT pg_advisory_unlock(?)")).thenReturn(mockUnlockStmt);
        when(mockUnlockStmt.executeQuery()).thenReturn(mockUnlockRs);

        lockService = new PostgresAdvisoryLockService(mockDataSource);
    }

    @Test
    @DisplayName("Task throws exception and unlock succeeds: task exception is preserved without suppression")
    void taskThrowsException_unlockSucceeds_taskExceptionPreserved() throws SQLException {
        when(mockUnlockRs.next()).thenReturn(true);
        when(mockUnlockRs.getBoolean(1)).thenReturn(true); // Unlock succeeds

        assertThatThrownBy(() -> lockService.executeWithLock(LOCK_KEY, () -> {
            throw new IllegalStateException("Primary business task failed!");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Primary business task failed!")
                .hasNoSuppressedExceptions();

        verify(mockConnection, never()).abort(any());
        verify(mockConnection).close();
    }

    @Test
    @DisplayName("Task succeeds but unlock throws exception: unlock exception is not swallowed and connection is aborted")
    void taskSucceeds_unlockThrows_unlockExceptionPropagated() throws SQLException {
        when(mockUnlockStmt.executeQuery()).thenThrow(new SQLException("Simulated DB connection broken during unlock"));

        assertThatThrownBy(() -> lockService.executeWithLock(LOCK_KEY, () -> "SUCCESS"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Advisory lock execution failed")
                .hasCauseInstanceOf(SQLException.class);

        verify(mockConnection).abort(any(Executor.class));
        verify(mockConnection).close();
    }

    @Test
    @DisplayName("Both task and unlock throw: original task exception preserved with unlock failure attached as suppressed")
    void taskAndUnlockBothThrow_taskExceptionPreservedWithUnlockSuppressed() throws SQLException {
        when(mockUnlockStmt.executeQuery()).thenThrow(new SQLException("Unlock communication error"));

        assertThatThrownBy(() -> lockService.executeWithLock(LOCK_KEY, () -> {
            throw new IllegalArgumentException("Original task error!");
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Original task error!")
                .satisfies(ex -> {
                    assertThat(ex.getSuppressed()).hasSizeGreaterThanOrEqualTo(1);
                    assertThat(ex.getSuppressed()[0])
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("Unlock communication error");
                });

        verify(mockConnection).abort(any(Executor.class));
        verify(mockConnection).close();
    }

    @Test
    @DisplayName("Task succeeds but unlock returns false: throws IllegalStateException and connection is quarantined via abort")
    void taskSucceeds_unlockReturnsFalse_throwsIllegalStateExceptionAndAborts() throws SQLException {
        when(mockUnlockRs.next()).thenReturn(true);
        when(mockUnlockRs.getBoolean(1)).thenReturn(false); // pg_advisory_unlock returned false!

        assertThatThrownBy(() -> lockService.executeWithLock(LOCK_KEY, () -> "SUCCESS"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pg_advisory_unlock returned false");

        verify(mockConnection).abort(any(Executor.class));
        verify(mockConnection).close();
    }
}
