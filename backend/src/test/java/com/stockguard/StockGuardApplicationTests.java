package com.stockguard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Isolated application-context bootstrap smoke test.
 * <p>
 * This test verifies that the Spring Boot application starts, configures its component
 * scanner across all modular domain packages (inventory, order, reservation, warehouse,
 * product, organization, audit, shared), and initializes bean definitions.
 * <p>
 * IMPORTANT TEST BOUNDARY NOTICE:
 * This test uses the "test" profile which intentionally excludes live database
 * auto-configurations (DataSourceAutoConfiguration, HibernateJpaAutoConfiguration,
 * FlywayAutoConfiguration). Consequently, this test validates context wiring only;
 * it does NOT claim to prove database connectivity or full infrastructure integration.
 * Real PostgreSQL lifecycle and live SQL execution are tested independently in
 * {@link com.stockguard.infrastructure.TestcontainersPostgresTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class StockGuardApplicationTests {

    @Test
    @DisplayName("Application context bootstraps and scans modular packages (isolated test profile)")
    void contextLoads() {
    }
}
