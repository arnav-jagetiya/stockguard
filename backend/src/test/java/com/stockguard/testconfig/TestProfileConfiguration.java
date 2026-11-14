package com.stockguard.testconfig;

import com.stockguard.inventory.repository.JdbcInventoryOperations;
import com.stockguard.order.repository.JdbcOrderOperations;
import com.stockguard.order.repository.OrderItemRepository;
import com.stockguard.order.repository.OrderRepository;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.reservation.repository.JdbcReservationOperations;
import com.stockguard.reservation.repository.ReservationAllocationRepository;
import com.stockguard.reservation.repository.ReservationRepository;
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
    public JdbcOrderOperations jdbcOrderOperations() {
        return Mockito.mock(JdbcOrderOperations.class);
    }

    @Bean
    public OrderRepository orderRepository() {
        return Mockito.mock(OrderRepository.class);
    }

    @Bean
    public OrderItemRepository orderItemRepository() {
        return Mockito.mock(OrderItemRepository.class);
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
    public JdbcReservationOperations jdbcReservationOperations() {
        return Mockito.mock(JdbcReservationOperations.class);
    }

    @Bean
    public ReservationRepository reservationRepository() {
        return Mockito.mock(ReservationRepository.class);
    }

    @Bean
    public ReservationAllocationRepository reservationAllocationRepository() {
        return Mockito.mock(ReservationAllocationRepository.class);
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
