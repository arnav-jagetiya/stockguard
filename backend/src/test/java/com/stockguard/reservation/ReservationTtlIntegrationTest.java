package com.stockguard.reservation;

import com.stockguard.inventory.dto.OpeningBalanceRequest;
import com.stockguard.inventory.service.InventoryService;
import com.stockguard.order.dto.CreateOrderRequest;
import com.stockguard.order.dto.OrderItemRequest;
import com.stockguard.order.dto.OrderResponse;
import com.stockguard.order.service.OrderService;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.product.domain.Product;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.reservation.config.ReservationConfig;
import com.stockguard.reservation.config.ReservationProperties;
import com.stockguard.reservation.dto.ReservationResponse;
import com.stockguard.reservation.repository.JdbcReservationOperations;
import com.stockguard.reservation.repository.JdbcReservationOperations.InsertedReservationHeader;
import com.stockguard.reservation.repository.ReservationAllocationRepository;
import com.stockguard.reservation.repository.ReservationRepository;
import com.stockguard.reservation.service.ReservationService;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.UserRepository;
import com.stockguard.shared.idempotency.IdempotencyService;
import com.stockguard.warehouse.domain.Warehouse;
import com.stockguard.warehouse.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class ReservationTtlIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("stockguard_test")
            .withUsername("test_user")
            .withPassword("test_pass")
            .withInitScript("init-test-roles.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "stockguard_app");
        registry.add("spring.datasource.password", () -> "test_app_pass");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "stockguard_owner");
        registry.add("spring.flyway.password", () -> "test_owner_pass");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("stockguard.security.bootstrap.enabled", () -> "false");
    }

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private JdbcReservationOperations jdbcReservationOperations;

    @Autowired
    private OrderService orderService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationAllocationRepository allocationRepository;

    @Autowired
    private com.stockguard.order.repository.OrderItemRepository orderItemRepository;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.stockguard.audit.service.AuditLogService auditLogService;

    private UUID orgId;
    private UUID actorUserId;
    private Warehouse warehouse;
    private Product product;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(Organization.create("TTL Org " + UUID.randomUUID()));
        orgId = org.getId();

        User actor = userRepository.save(User.create("ttl.user." + UUID.randomUUID() + "@stockguard.com", "hash"));
        actorUserId = actor.getId();
        membershipRepository.save(OrganizationMembership.create(orgId, actorUserId, Role.ADMIN));

        warehouse = warehouseRepository.save(new Warehouse(orgId, "WH-TTL-" + UUID.randomUUID().toString().substring(0, 5), "TTL WH", "Loc", 1));
        product = productRepository.save(new Product(orgId, "P-TTL-" + UUID.randomUUID().toString().substring(0, 5), "TTL Prod", null));

        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product.getId(), warehouse.getId(), 500L, "Seed stock"), null);
    }

    @Test
    @DisplayName("Default TTL produces exactly 15 minutes of database-time expiry in PostgreSQL")
    void defaultTtlProduces15MinutesDatabaseExpiry() {
        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-TTL-15", null, "USD",
                List.of(new OrderItemRequest(product.getId(), 10L, new BigDecimal("25.0000")))
        );
        OrderResponse order = orderService.createOrder(orgId, actorUserId, orderReq, null);

        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, order.id(), null);

        // Query database time calculation directly from PostgreSQL
        Double diffMinutes = jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (expires_at - created_at)) / 60.0 FROM reservations WHERE id = ? AND organization_id = ?",
                Double.class, res.id(), orgId
        );

        assertThat(diffMinutes).isNotNull();
        assertThat(diffMinutes).isEqualTo(15.0);

        // Verify PostgreSQL database clock authoritativeness
        Integer matchesDbFormula = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservations WHERE id = ? AND organization_id = ? AND expires_at = created_at + (15 * INTERVAL '1 minute')",
                Integer.class, res.id(), orgId
        );
        assertThat(matchesDbFormula).isEqualTo(1);
    }

    @Test
    @DisplayName("A different configured TTL changes expires_at accordingly using PostgreSQL parameter binding")
    void customTtlChangesExpiresAtAccordingly() {
        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-TTL-CUSTOM", null, "USD",
                List.of(new OrderItemRequest(product.getId(), 5L, new BigDecimal("10.0000")))
        );
        OrderResponse order = orderService.createOrder(orgId, actorUserId, orderReq, null);

        UUID customResId = UUID.randomUUID();
        long customTtlMinutes = 45L;

        InsertedReservationHeader header = jdbcReservationOperations.insertReservation(
                customResId, orgId, order.id(), customTtlMinutes
        );

        Double diffMinutes = jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (expires_at - created_at)) / 60.0 FROM reservations WHERE id = ? AND organization_id = ?",
                Double.class, customResId, orgId
        );

        assertThat(diffMinutes).isNotNull();
        assertThat(diffMinutes).isEqualTo(45.0);
        assertThat(header.expiresAt()).isNotNull();
    }

    @Test
    @DisplayName("JVM and database clock differences cannot affect database-calculated expiry")
    void databaseClockAuthoritativeForExpiry() {
        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-TTL-CLOCK", null, "USD",
                List.of(new OrderItemRequest(product.getId(), 2L, new BigDecimal("10.0000")))
        );
        OrderResponse order = orderService.createOrder(orgId, actorUserId, orderReq, null);

        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, order.id(), null);

        // In PostgreSQL, NOW() in the same transaction produces identical timestamp for created_at and base of expires_at
        Integer exactMatch = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservations WHERE id = ? AND organization_id = ? AND expires_at = created_at + INTERVAL '15 minute'",
                Integer.class, res.id(), orgId
        );
        assertThat(exactMatch).isEqualTo(1);
    }

    @Test
    @DisplayName("Zero and negative TTL configuration values are rejected at startup and programmatic instantiation")
    void zeroAndNegativeTtlValuesRejected() {
        // 1. Programmatic validation in ReservationService constructor
        assertThatThrownBy(() -> new ReservationService(
                jdbcReservationOperations, reservationRepository, allocationRepository,
                orderItemRepository, idempotencyService, transactionManager, 0L, auditLogService
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("strictly greater than zero");

        assertThatThrownBy(() -> new ReservationService(
                jdbcReservationOperations, reservationRepository, allocationRepository,
                orderItemRepository, idempotencyService, transactionManager, -10L, auditLogService
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("strictly greater than zero");

        // 2. ReservationProperties @PostConstruct validation
        ReservationProperties zeroProps = new ReservationProperties();
        zeroProps.setDefaultTtlMinutes(0);
        assertThatThrownBy(zeroProps::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("strictly greater than zero");

        ReservationProperties negativeProps = new ReservationProperties();
        negativeProps.setDefaultTtlMinutes(-5);
        assertThatThrownBy(negativeProps::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("strictly greater than zero");

        // 3. ApplicationContext startup failure when invalid TTL is configured
        new ApplicationContextRunner()
                .withUserConfiguration(ReservationConfig.class)
                .withPropertyValues("stockguard.reservation.default-ttl-minutes=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(org.springframework.boot.context.properties.bind.validation.BindValidationException.class);
                    assertThat(context.getStartupFailure().getMessage())
                            .contains("stockguard.reservation");
                });

        new ApplicationContextRunner()
                .withUserConfiguration(ReservationConfig.class)
                .withPropertyValues("stockguard.reservation.default-ttl-minutes=-15")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(org.springframework.boot.context.properties.bind.validation.BindValidationException.class);
                    assertThat(context.getStartupFailure().getMessage())
                            .contains("stockguard.reservation");
                });
    }
}
