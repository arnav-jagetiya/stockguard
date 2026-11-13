package com.stockguard.testconfig;

import com.stockguard.inventory.repository.JdbcInventoryOperations;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.shared.idempotency.IdempotencyService;
import com.stockguard.warehouse.repository.WarehouseRepository;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Scoped test-only configuration active exclusively under the "test" profile.
 * <p>
 * Provides mock beans for database-dependent repositories and services when
 * tests run in isolated context-bootstrap mode (such as {@link com.stockguard.StockGuardApplicationTests}
 * and {@link com.stockguard.security.config.InterimSecurityTest}) where DataSource and JPA
 * auto-configurations are excluded by application-test.yml.
 * <p>
 * In production and in real PostgreSQL integration tests (profile "dev"), this configuration
 * is inactive, ensuring 100% mandatory dependency injection and fail-fast startup.
 */
@Configuration
@Profile("test")
public class TestProfileConfiguration {

    @Bean
    public JdbcInventoryOperations jdbcInventoryOperations() {
        return Mockito.mock(JdbcInventoryOperations.class);
    }

    @Bean
    public ProductRepository productRepository() {
        return Mockito.mock(ProductRepository.class);
    }

    @Bean
    public WarehouseRepository warehouseRepository() {
        return Mockito.mock(WarehouseRepository.class);
    }

    @Bean
    public IdempotencyService idempotencyService() {
        return Mockito.mock(IdempotencyService.class);
    }

    @Bean
    public PlatformTransactionManager platformTransactionManager() {
        return Mockito.mock(PlatformTransactionManager.class);
    }
}
