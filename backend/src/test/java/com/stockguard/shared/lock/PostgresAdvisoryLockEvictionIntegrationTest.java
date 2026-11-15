package com.stockguard.shared.lock;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test verifying that unsuccessful or failed advisory lock release
 * invokes {@link Connection#abort(java.util.concurrent.Executor)} on HikariCP connection,
 * resulting in real physical pool eviction and replacement with a fresh database connection.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresAdvisoryLockEvictionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("stockguard_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    private static final long LOCK_KEY = 0x53544B4752445243L;

    @Test
    @DisplayName("Failed unlock aborts connection and evicts it from HikariCP pool, forcing connection recreation")
    void failedUnlockAbortsAndEvictsConnectionFromHikariPool() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setPoolName("HikariEvictionTestPool");

        try (HikariDataSource pool = new HikariDataSource(config)) {
            // 1. Borrow initial connection from the pool of size 1 and inspect its backend PID
            int initialPid;
            try (Connection c = pool.getConnection();
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT pg_backend_pid()")) {
                assertThat(rs.next()).isTrue();
                initialPid = rs.getInt(1);
            }

            // 2. Wrap pool in a DataSource that intercepts unlock statement to return false
            DataSource faultyUnlockDataSource = new DataSource() {
                @Override
                public Connection getConnection() throws SQLException {
                    Connection realConn = pool.getConnection();

                    return (Connection) Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[]{Connection.class},
                            new InvocationHandler() {
                                @Override
                                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                    if ("prepareStatement".equals(method.getName()) && args != null && args.length > 0) {
                                        String sql = (String) args[0];
                                        if ("SELECT pg_advisory_unlock(?)".equals(sql)) {
                                            // Intercept unlock query to return false (simulating lock not owned / release failure)
                                            return createMockFailedUnlockStatement();
                                        }
                                    }
                                    // Delegate everything else directly to the real HikariProxyConnection
                                    try {
                                        return method.invoke(realConn, args);
                                    } catch (InvocationTargetException ite) {
                                        throw ite.getTargetException();
                                    }
                                }
                            }
                    );
                }

                @Override
                public Connection getConnection(String username, String password) throws SQLException {
                    return getConnection();
                }

                @Override
                public PrintWriter getLogWriter() { return null; }
                @Override
                public void setLogWriter(PrintWriter out) {}
                @Override
                public void setLoginTimeout(int seconds) {}
                @Override
                public int getLoginTimeout() { return 0; }
                @Override
                public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                    throw new SQLFeatureNotSupportedException();
                }
                @Override
                public <T> T unwrap(Class<T> iface) throws SQLException { return null; }
                @Override
                public boolean isWrapperFor(Class<?> iface) throws SQLException { return false; }
            };

            PostgresAdvisoryLockService service = new PostgresAdvisoryLockService(faultyUnlockDataSource);

            // 3. Execute with lock. tryAcquire succeeds on Postgres, task runs, but unlock in finally returns false.
            // Service must call connection.abort() and throw IllegalStateException.
            assertThatThrownBy(() -> service.executeWithLock(LOCK_KEY, () -> "TASK_SUCCESS"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("pg_advisory_unlock returned false");

            // 4. Verification of physical pool eviction:
            // Since maximumPoolSize is 1, borrowing from pool now MUST create a NEW physical connection
            // because the previous connection was aborted and evicted.
            int newPid;
            try (Connection c = pool.getConnection()) {
                assertThat(c.isClosed()).isFalse();
                try (Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery("SELECT pg_backend_pid()")) {
                    assertThat(rs.next()).isTrue();
                    newPid = rs.getInt(1);
                }
            }

            // Backend PID must be different, proving real HikariCP pool eviction!
            assertThat(newPid)
                    .withFailMessage("Expected new physical connection after abort, but received identical PID %d", initialPid)
                    .isNotEqualTo(initialPid);

            // 5. Verify the new connection is healthy and can acquire/release advisory locks cleanly
            try (Connection c = pool.getConnection();
                 PreparedStatement lockStmt = c.prepareStatement("SELECT pg_try_advisory_lock(?)");
                 PreparedStatement unlockStmt = c.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                lockStmt.setLong(1, LOCK_KEY);
                try (ResultSet rs = lockStmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBoolean(1)).isTrue();
                }

                unlockStmt.setLong(1, LOCK_KEY);
                try (ResultSet rs = unlockStmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBoolean(1)).isTrue();
                }
            }
        }
    }

    private PreparedStatement createMockFailedUnlockStatement() {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if ("executeQuery".equals(method.getName())) {
                        return createMockFalseResultSet();
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    if ("setLong".equals(method.getName())) {
                        return null;
                    }
                    return null;
                }
        );
    }

    private ResultSet createMockFalseResultSet() {
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    if ("next".equals(method.getName())) {
                        return true;
                    }
                    if ("getBoolean".equals(method.getName())) {
                        return false; // pg_advisory_unlock returned false!
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return null;
                }
        );
    }
}
