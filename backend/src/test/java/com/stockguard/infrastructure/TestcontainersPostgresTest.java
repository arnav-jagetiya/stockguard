package com.stockguard.infrastructure;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates Testcontainers PostgreSQL 16 container startup and live query connectivity.
 * <p>
 * If Docker is active and accessible via Testcontainers, a real PostgreSQL 16 Alpine container
 * is started, verifying container lifecycle, JDBC connectivity, and query execution.
 * <p>
 * If Docker is unavailable in the environment, the test cleanly skips using JUnit 5
 * {@link Assumptions}, documenting environmental limitations without false-positive claims.
 */
class TestcontainersPostgresTest {

    @Test
    @DisplayName("Testcontainers PostgreSQL 16 container starts and executes query when Docker is available")
    void postgresContainerStartsAndExecutesQuery() throws Exception {
        boolean dockerAvailable;
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException ex) {
            dockerAvailable = false;
        }

        Assumptions.assumeTrue(dockerAvailable,
                "Docker is not available in the current environment; skipping live Testcontainers PostgreSQL test.");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("stockguard_test")
                .withUsername("test_user")
                .withPassword("test_pass")) {

            postgres.start();
            assertTrue(postgres.isRunning(), "PostgreSQL container should be running");

            try (Connection conn = DriverManager.getConnection(
                    postgres.getJdbcUrl(),
                    postgres.getUsername(),
                    postgres.getPassword());
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1")) {

                assertTrue(rs.next(), "Query result should contain at least one row");
                assertEquals(1, rs.getInt(1), "SELECT 1 should return integer 1");
            }
        }
    }
}
