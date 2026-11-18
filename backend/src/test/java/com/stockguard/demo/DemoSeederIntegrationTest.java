package com.stockguard.demo;

import com.stockguard.audit.domain.AuditLog;
import com.stockguard.audit.repository.AuditLogRepository;
import com.stockguard.demo.config.DemoProperties;
import com.stockguard.demo.dto.DemoSeedResult;
import com.stockguard.demo.service.DemoEnvironmentSeeder;
import com.stockguard.inventory.domain.DiscrepancyStatus;
import com.stockguard.inventory.domain.InventoryBalance;
import com.stockguard.inventory.domain.ReconciliationDiscrepancyEntity;
import com.stockguard.inventory.repository.InventoryBalanceRepository;
import com.stockguard.inventory.repository.ReconciliationDiscrepancyRepository;
import com.stockguard.order.domain.Order;
import com.stockguard.order.domain.OrderStatus;
import com.stockguard.order.repository.OrderRepository;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.product.domain.Product;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.reservation.domain.Reservation;
import com.stockguard.reservation.domain.ReservationStatus;
import com.stockguard.reservation.repository.ReservationRepository;
import com.stockguard.security.bootstrap.AdminBootstrapRunner;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.UserRepository;
import com.stockguard.transfer.dto.TransferResponse;
import com.stockguard.transfer.service.TransferService;
import com.stockguard.warehouse.domain.Warehouse;
import com.stockguard.warehouse.repository.WarehouseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoSeederIntegrationTest {

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
        registry.add("stockguard.demo.seed-enabled", () -> "false");
    }

    @Autowired
    private DemoEnvironmentSeeder demoEnvironmentSeeder;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryBalanceRepository balanceRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private TransferService transferService;

    @Autowired
    private ReconciliationDiscrepancyRepository discrepancyRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Comprehensive seed creates realistic Acme Industrial Supply organization with valid domain data")
    void seedsRealisticDemoEnvironment() {
        String testAdminPassword = AdminBootstrapRunner.generateSecure256BitHexCredential();
        DemoProperties properties = new DemoProperties();
        properties.setSeedEnabled(true);
        properties.setOrgName("Acme Industrial Supply Pvt. Ltd.");
        properties.setAdminEmail("acme-admin@stockguard.dev");
        properties.setAdminPassword(testAdminPassword);
        properties.setAdminFullName("Aarav Mehta");

        DemoSeedResult result = demoEnvironmentSeeder.seedDemoEnvironment(properties);

        assertThat(result.alreadySeeded()).isFalse();
        UUID orgId = result.organizationId();
        assertThat(orgId).isNotNull();

        // 1. Organization verification
        Optional<Organization> orgOpt = organizationRepository.findById(orgId);
        assertThat(orgOpt).isPresent();
        assertThat(orgOpt.get().getName()).isEqualTo("Acme Industrial Supply Pvt. Ltd.");

        // 2. Team verification (1 ADMIN, 2 MANAGERS, 3 OPERATORS, 3 VIEWERS = 9 total including public demo visitor)
        List<OrganizationMembership> memberships = membershipRepository.findAllByOrganizationIdAndActiveTrue(orgId);
        assertThat(memberships).hasSize(9);

        long adminCount = memberships.stream().filter(m -> m.getRole() == Role.ADMIN).count();
        long managerCount = memberships.stream().filter(m -> m.getRole() == Role.MANAGER).count();
        long operatorCount = memberships.stream().filter(m -> m.getRole() == Role.OPERATOR).count();
        long viewerCount = memberships.stream().filter(m -> m.getRole() == Role.VIEWER).count();

        assertThat(adminCount).isEqualTo(1);
        assertThat(managerCount).isEqualTo(2);
        assertThat(operatorCount).isEqualTo(3);
        assertThat(viewerCount).isEqualTo(3);

        User adminUser = userRepository.findByNormalizedEmail(User.normalizeEmail("acme-admin@stockguard.dev")).orElseThrow();
        assertThat(passwordEncoder.matches(testAdminPassword, adminUser.getPasswordHash())).isTrue();

        // 3. Warehouse verification (3 facilities)
        List<Warehouse> warehouses = warehouseRepository.searchWarehouses(orgId, null, false);
        assertThat(warehouses).hasSize(3);
        assertThat(warehouses).extracting(Warehouse::getCode).containsExactlyInAnyOrder("WH-BLR-01", "WH-PUN-02", "WH-NCR-03");

        // 4. Product catalog verification (30 SKUs)
        List<Product> products = productRepository.findAllByOrganizationIdOrderByNormalizedSkuAsc(orgId);
        assertThat(products).hasSize(30);

        // 5. Inventory Balances and Ledger Mathematical Reconciliation
        List<InventoryBalance> balances = balanceRepository.findAllByOrganizationId(orgId);
        assertThat(balances).isNotEmpty();

        for (InventoryBalance b : balances) {
            // Invariant: Available = On-Hand - Reserved
            assertThat(b.getOnHand()).isGreaterThanOrEqualTo(0L);
            assertThat(b.getReserved()).isGreaterThanOrEqualTo(0L);
            assertThat(b.getReserved()).isLessThanOrEqualTo(b.getOnHand());
            assertThat(b.getAvailable()).isEqualTo(b.getOnHand() - b.getReserved());

            // Mathematical reconciliation against double-entry ledger entries
            Long ledgerOnHand = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(on_hand_delta), 0) FROM inventory_ledger_entries WHERE organization_id = ? AND product_id = ? AND warehouse_id = ?",
                    Long.class, orgId, b.getProductId(), b.getWarehouseId()
            );
            Long ledgerReserved = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(reserved_delta), 0) FROM inventory_ledger_entries WHERE organization_id = ? AND product_id = ? AND warehouse_id = ?",
                    Long.class, orgId, b.getProductId(), b.getWarehouseId()
            );

            assertThat(b.getOnHand()).as("Balance on-hand must match ledger sum for prod %s wh %s", b.getProductId(), b.getWarehouseId())
                    .isEqualTo(ledgerOnHand);
            assertThat(b.getReserved()).as("Balance reserved must match ledger sum for prod %s wh %s", b.getProductId(), b.getWarehouseId())
                    .isEqualTo(ledgerReserved);
        }

        // 6. Orders and Reservations
        List<Order> orders = orderRepository.listOrders(orgId, null);
        assertThat(orders).hasSize(22);

        Set<OrderStatus> observedOrderStatuses = new HashSet<>();
        for (Order o : orders) {
            observedOrderStatuses.add(o.getStatus());
            assertThat(o.getCurrency()).isEqualTo("INR");
            assertThat(o.getTotalAmount()).isGreaterThan(java.math.BigDecimal.ZERO);
        }

        assertThat(observedOrderStatuses).contains(
                OrderStatus.PENDING,
                OrderStatus.CONFIRMED,
                OrderStatus.FULFILLED,
                OrderStatus.CANCELLED,
                OrderStatus.EXPIRED
        );

        List<String> observedResStatuses = jdbcTemplate.queryForList(
                "SELECT status FROM reservations WHERE organization_id = ?",
                String.class,
                orgId
        );
        assertThat(observedResStatuses).contains(
                ReservationStatus.ACTIVE.name(),
                ReservationStatus.CONSUMED.name(),
                ReservationStatus.RELEASED.name(),
                ReservationStatus.EXPIRED.name()
        );

        // 7. Transfers verification
        List<TransferResponse> transfers = transferService.listTransfers(orgId, null, 100);
        assertThat(transfers).hasSize(4);

        // Verify total system stock conservation across all 4 transfers
        for (TransferResponse t : transfers) {
            assertThat(t.sourceWarehouseId()).isNotEqualTo(t.destinationWarehouseId());
            assertThat(t.items()).isNotEmpty();
        }

        // 8. Discrepancies verification (4 discrepancies across states)
        List<ReconciliationDiscrepancyEntity> discrepancies = discrepancyRepository.findAll();
        List<ReconciliationDiscrepancyEntity> orgDiscrepancies = discrepancies.stream()
                .filter(d -> d.getOrganizationId().equals(orgId))
                .toList();
        assertThat(orgDiscrepancies).hasSize(4);

        Set<DiscrepancyStatus> discrepancyStatuses = new HashSet<>();
        for (ReconciliationDiscrepancyEntity d : orgDiscrepancies) {
            discrepancyStatuses.add(d.getStatus());
        }
        assertThat(discrepancyStatuses).contains(
                DiscrepancyStatus.OPEN,
                DiscrepancyStatus.ACKNOWLEDGED,
                DiscrepancyStatus.INVESTIGATING,
                DiscrepancyStatus.RESOLVED
        );

        // 9. Audit Logs verification
        Long auditLogCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE organization_id = ?",
                Long.class,
                orgId
        );
        assertThat(auditLogCount).isGreaterThan(50L);

        List<String> actions = jdbcTemplate.queryForList(
                "SELECT action FROM audit_logs WHERE organization_id = ?",
                String.class,
                orgId
        );
        assertThat(actions).contains("DEMO_SEED_COMPLETED");
    }
}
