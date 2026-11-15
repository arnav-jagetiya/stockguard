package com.stockguard.shared.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Dedicated-connection session-level PostgreSQL advisory lock coordination service per ADR-012.
 * <p>
 * Guarantees that:
 * <ul>
 *   <li>The advisory lock is acquired on a dedicated {@link Connection} borrowed directly from {@link DataSource}.</li>
 *   <li>The lock is non-blocking via {@code pg_try_advisory_lock(bigint)}.</li>
 *   <li>The lock release {@code pg_advisory_unlock(bigint)} executes on the exact same connection in {@code finally}.</li>
 *   <li>The connection is closed in {@code finally}.</li>
 *   <li>If unlock fails, the original task exception is preserved (and unlock exception attached via {@link Throwable#addSuppressed(Throwable)}).</li>
 *   <li>A connection is NEVER returned to the pool while its advisory lock remains held.</li>
 * </ul>
 */
@Service
@Profile("!test")
public class PostgresAdvisoryLockService {

    private static final Logger log = LoggerFactory.getLogger(PostgresAdvisoryLockService.class);

    private final DataSource dataSource;

    public PostgresAdvisoryLockService(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "DataSource must not be null");
    }

    /**
     * Executes the supplied task within a dedicated PostgreSQL session advisory lock.
     *
     * @param lockKey 64-bit advisory lock key
     * @param task task to execute if lock is acquired
     * @param <T> result type
     * @return Optional containing the task result, or empty if lock was held by another node
     */
    public <T> Optional<T> executeWithLock(long lockKey, Supplier<T> task) {
        Objects.requireNonNull(task, "Task must not be null");

        Connection connection = null;
        boolean lockAcquired = false;
        Throwable taskException = null;

        try {
            connection = dataSource.getConnection();
            lockAcquired = tryAcquireSessionLock(connection, lockKey);

            if (!lockAcquired) {
                log.info("PostgreSQL advisory lock [{}] is currently held by another node. Skipping execution cleanly.", lockKey);
                return Optional.empty();
            }

            log.debug("PostgreSQL advisory lock [{}] acquired on dedicated connection.", lockKey);
            try {
                T result = task.get();
                return Optional.ofNullable(result);
            } catch (Throwable t) {
                taskException = t;
                throw t;
            }
        } catch (SQLException ex) {
            log.error("Database connection/lock error for advisory lock [{}]: {}", lockKey, ex.getMessage(), ex);
            throw new RuntimeException("Database error during advisory lock execution", ex);
        } finally {
            if (connection != null) {
                boolean unlockSucceeded = false;
                if (lockAcquired) {
                    try {
                        unlockSucceeded = releaseSessionLock(connection, lockKey);
                        if (!unlockSucceeded) {
                            log.warn("pg_advisory_unlock({}) returned false; lock was not owned by this session.", lockKey);
                            if (taskException == null) {
                                taskException = new IllegalStateException("Failed to release advisory lock [" + lockKey + "]: pg_advisory_unlock returned false");
                            }
                        } else {
                            log.debug("PostgreSQL advisory lock [{}] released successfully.", lockKey);
                        }
                    } catch (Throwable unlockEx) {
                        log.error("Failed to release advisory lock [{}]: {}", lockKey, unlockEx.getMessage(), unlockEx);
                        if (taskException != null) {
                            taskException.addSuppressed(unlockEx);
                        } else {
                            taskException = unlockEx;
                        }
                    }

                    // CRITICAL: If unlock failed, abort the connection to terminate the physical backend session,
                    // ensuring that an unreleased advisory lock is NEVER returned to the HikariCP pool!
                    if (!unlockSucceeded) {
                        try {
                            log.error("Aborting connection to prevent returning held advisory lock [{}] to pool.", lockKey);
                            connection.abort(Runnable::run);
                        } catch (Throwable abortEx) {
                            log.error("Failed to abort connection: {}", abortEx.getMessage(), abortEx);
                            if (taskException != null) {
                                taskException.addSuppressed(abortEx);
                            }
                        }
                    }
                }

                try {
                    if (!connection.isClosed()) {
                        connection.close();
                    }
                } catch (SQLException closeEx) {
                    log.error("Error closing connection for advisory lock [{}]: {}", lockKey, closeEx.getMessage(), closeEx);
                    if (taskException != null) {
                        taskException.addSuppressed(closeEx);
                    }
                }

                // Propagate any exception: preserves original task exception or propagates unlock/close failure
                if (taskException != null) {
                    if (taskException instanceof RuntimeException re) {
                        throw re;
                    }
                    if (taskException instanceof Error err) {
                        throw err;
                    }
                    throw new RuntimeException("Advisory lock execution failed", taskException);
                }
            }
        }
    }

    /**
     * Executes the supplied runnable task within a dedicated PostgreSQL session advisory lock.
     *
     * @return true if lock was acquired and task executed; false if lock was held by another node
     */
    public boolean executeWithLock(long lockKey, Runnable task) {
        Objects.requireNonNull(task, "Task must not be null");
        return executeWithLock(lockKey, () -> {
            task.run();
            return true;
        }).orElse(false);
    }

    private boolean tryAcquireSessionLock(Connection connection, long lockKey) throws SQLException {
        String sql = "SELECT pg_try_advisory_lock(?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, lockKey);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean(1);
                }
                return false;
            }
        }
    }

    private boolean releaseSessionLock(Connection connection, long lockKey) throws SQLException {
        String sql = "SELECT pg_advisory_unlock(?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, lockKey);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean(1);
                }
                return false;
            }
        }
    }
}
