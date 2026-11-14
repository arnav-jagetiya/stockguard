package com.stockguard.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockguard.audit.domain.AuditAction;
import com.stockguard.audit.domain.AuditEntityType;
import com.stockguard.audit.domain.AuditLog;
import com.stockguard.audit.service.AuditLogService;
import com.stockguard.inventory.dto.AdjustStockRequest;
import com.stockguard.inventory.dto.OpeningBalanceRequest;
import com.stockguard.inventory.dto.ReceiveStockRequest;
import com.stockguard.inventory.service.InventoryService;
import com.stockguard.order.domain.OrderStatus;
import com.stockguard.order.dto.CreateOrderRequest;
import com.stockguard.order.dto.OrderItemRequest;
import com.stockguard.order.dto.OrderResponse;
import com.stockguard.order.service.OrderService;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.organization.service.OrganizationMembershipService;
import com.stockguard.product.domain.Product;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.reservation.domain.ReservationStatus;
import com.stockguard.reservation.dto.ExpirationBatchResult;
import com.stockguard.reservation.dto.ReservationResponse;
import com.stockguard.reservation.service.ReservationExpirationService;
import com.stockguard.reservation.service.ReservationService;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.UserRepository;
import com.stockguard.transfer.dto.CreateTransferRequest;
import com.stockguard.transfer.dto.TransferItemRequest;
import com.stockguard.transfer.dto.TransferResponse;
import com.stockguard.transfer.service.TransferService;
import com.stockguard.warehouse.domain.Warehouse;
import com.stockguard.warehouse.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class BusinessAuditIntegrationTest {

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
        registry.add("stockguard.reservation.expiration.enabled", () -> "false");
        registry.add("stockguard.security.bootstrap.enabled", () -> "false");
    }

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationExpirationService expirationService;

    @Autowired
    private OrganizationMembershipService membershipService;

    @Autowired
    private AuditLogService auditLogService;

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID orgId;
    private UUID actorUserId;
    private Warehouse warehouse1;
    private Warehouse warehouse2;
    private Product productA;
    private Product productB;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(Organization.create("Audit Integration Org " + UUID.randomUUID()));
        orgId = org.getId();

        User actor = userRepository.save(User.create("audit.user." + UUID.randomUUID() + "@stockguard.com", "hash"));
        actorUserId = actor.getId();
        membershipRepository.save(OrganizationMembership.create(orgId, actorUserId, Role.ADMIN));

        warehouse1 = warehouseRepository.save(new Warehouse(orgId, "WH-AUD-1-" + UUID.randomUUID().toString().substring(0, 5), "WH 1", "Loc 1", 10));
        warehouse2 = warehouseRepository.save(new Warehouse(orgId, "WH-AUD-2-" + UUID.randomUUID().toString().substring(0, 5), "WH 2", "Loc 2", 20));

        productA = productRepository.save(new Product(orgId, "SKU-AUD-A-" + UUID.randomUUID().toString().substring(0, 5), "Product A", "Test desc A"));
        productB = productRepository.save(new Product(orgId, "SKU-AUD-B-" + UUID.randomUUID().toString().substring(0, 5), "Product B", "Test desc B"));
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON: " + json, e);
        }
    }

    // =========================================================================
    // 1. INVENTORY MUTATION AUDITS
    // =========================================================================

    @Test
    @DisplayName("1. Opening balance creates OPENING_BALANCE_RECORDED audit log with before/after state and metadata")
    void openingBalanceCreatesAuditLog() {
        inventoryService.recordOpeningBalance(
                orgId,
                actorUserId,
                new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 100L, "Initial stock loading"),
                null
        );

        List<AuditLog> logs = auditLogService.findAuditLogs(
                orgId, AuditEntityType.INVENTORY_BALANCE, AuditAction.OPENING_BALANCE_RECORDED,
                actorUserId, null, null, 10, 0
        );

        assertThat(logs).hasSize(1);
        AuditLog log = logs.getFirst();
        assertThat(log.getOrganizationId()).isEqualTo(orgId);
        assertThat(log.getActorUserId()).isEqualTo(actorUserId);
        assertThat(log.getEntityId()).isEqualTo(productA.getId());

        Map<String, Object> before = parseJson(log.getBeforeState());
        assertThat(before).containsEntry("on_hand", 0).containsEntry("reserved", 0);

        Map<String, Object> after = parseJson(log.getAfterState());
        assertThat(after).containsEntry("on_hand", 100).containsEntry("reserved", 0);

        Map<String, Object> metadata = parseJson(log.getMetadata());
        assertThat(metadata)
                .containsEntry("productId", productA.getId().toString())
                .containsEntry("warehouseId", warehouse1.getId().toString())
                .containsEntry("onHand", 100)
                .containsEntry("notes", "Initial stock loading");
        assertThat(metadata.get("transactionId")).isNotNull();
    }

    @Test
    @DisplayName("2. Stock receipt creates STOCK_RECEIVED audit log with before/after state and metadata")
    void stockReceiptCreatesAuditLog() {
        inventoryService.recordOpeningBalance(
                orgId, actorUserId,
                new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 100L, "Init"),
                null
        );

        UUID receiptRef = UUID.randomUUID();
        inventoryService.receiveStock(
                orgId,
                actorUserId,
                new ReceiveStockRequest(productA.getId(), warehouse1.getId(), 50L, receiptRef, "Supplier PO-999"),
                null
        );

        List<AuditLog> logs = auditLogService.findAuditLogs(
                orgId, AuditEntityType.INVENTORY_BALANCE, AuditAction.STOCK_RECEIVED,
                actorUserId, null, null, 10, 0
        );

        assertThat(logs).hasSize(1);
        AuditLog log = logs.getFirst();
        assertThat(log.getOrganizationId()).isEqualTo(orgId);
        assertThat(log.getActorUserId()).isEqualTo(actorUserId);
        assertThat(log.getEntityId()).isEqualTo(productA.getId());

        Map<String, Object> before = parseJson(log.getBeforeState());
        assertThat(before).containsEntry("on_hand", 100).containsEntry("reserved", 0);

        Map<String, Object> after = parseJson(log.getAfterState());
        assertThat(after).containsEntry("on_hand", 150).containsEntry("reserved", 0);

        Map<String, Object> metadata = parseJson(log.getMetadata());
        assertThat(metadata)
                .containsEntry("productId", productA.getId().toString())
                .containsEntry("warehouseId", warehouse1.getId().toString())
                .containsEntry("quantityReceived", 50)
                .containsEntry("referenceId", receiptRef.toString())
                .containsEntry("notes", "Supplier PO-999");
    }

    @Test
    @DisplayName("3. Stock adjustment creates STOCK_ADJUSTED audit log with before/after state and metadata")
    void stockAdjustmentCreatesAuditLog() {
        inventoryService.recordOpeningBalance(
                orgId, actorUserId,
                new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 100L, "Init"),
                null
        );

        inventoryService.adjustStock(
                orgId,
                actorUserId,
                new AdjustStockRequest(productA.getId(), warehouse1.getId(), -20L, "Damaged in transit", null),
                null
        );

        List<AuditLog> logs = auditLogService.findAuditLogs(
                orgId, AuditEntityType.INVENTORY_BALANCE, AuditAction.STOCK_ADJUSTED,
                actorUserId, null, null, 10, 0
        );

        assertThat(logs).hasSize(1);
        AuditLog log = logs.getFirst();
        assertThat(log.getOrganizationId()).isEqualTo(orgId);
        assertThat(log.getActorUserId()).isEqualTo(actorUserId);

        Map<String, Object> before = parseJson(log.getBeforeState());
        assertThat(before).containsEntry("on_hand", 100).containsEntry("reserved", 0);

        Map<String, Object> after = parseJson(log.getAfterState());
        assertThat(after).containsEntry("on_hand", 80).containsEntry("reserved", 0);

        Map<String, Object> metadata = parseJson(log.getMetadata());
        assertThat(metadata)
                .containsEntry("productId", productA.getId().toString())
                .containsEntry("warehouseId", warehouse1.getId().toString())
                .containsEntry("onHandDelta", -20)
                .containsEntry("reason", "Damaged in transit");
    }

    // =========================================================================
    // 2. TRANSFER MUTATION AUDIT
    // =========================================================================

    @Test
    @DisplayName("4. Inter-warehouse transfer creates TRANSFER_COMPLETED audit log with metadata")
    void transferCreatesAuditLog() {
        inventoryService.recordOpeningBalance(
                orgId, actorUserId,
                new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 100L, "Init"),
                null
        );

        TransferResponse transfer = transferService.transferStock(
                orgId,
                actorUserId,
                new CreateTransferRequest(
                        warehouse1.getId(),
                        warehouse2.getId(),
                        List.of(new TransferItemRequest(productA.getId(), 35L)),
                        "Relocating stock to WH 2"
                ),
                null
        );

        List<AuditLog> logs = auditLogService.findAuditLogs(
                orgId, AuditEntityType.TRANSFER, AuditAction.TRANSFER_COMPLETED,
                actorUserId, null, null, 10, 0
        );

        assertThat(logs).hasSize(1);
        AuditLog log = logs.getFirst();
        assertThat(log.getEntityId()).isEqualTo(transfer.id());
        assertThat(log.getActorUserId()).isEqualTo(actorUserId);

        Map<String, Object> metadata = parseJson(log.getMetadata());
        assertThat(metadata)
                .containsEntry("sourceWarehouseId", warehouse1.getId().toString())
                .containsEntry("destWarehouseId", warehouse2.getId().toString())
                .containsEntry("itemCount", 1);
        assertThat(metadata.get("items")).isNotNull();
    }

    // =========================================================================
    // 3. ORDER & RESERVATION LIFECYCLE AUDITS
    // =========================================================================

    @Test
    @DisplayName("5. Order creation creates ORDER_CREATED audit log with after_state PENDING")
    void orderCreationCreatesAuditLog() {
        CreateOrderRequest request = new CreateOrderRequest(
                "CUST-AUD-001", "EXT-REF-001", "USD",
                List.of(new OrderItemRequest(productA.getId(), 10L, new BigDecimal("25.0000")))
        );

        OrderResponse order = orderService.createOrder(orgId, actorUserId, request, null);

        List<AuditLog> logs = auditLogService.findAuditLogs(
                orgId, AuditEntityType.ORDER, AuditAction.ORDER_CREATED,
                actorUserId, null, null, 10, 0
        );

        assertThat(logs).hasSize(1);
        AuditLog log = logs.getFirst();
        assertThat(log.getEntityId()).isEqualTo(order.id());

        Map<String, Object> after = parseJson(log.getAfterState());
        assertThat(after)
                .containsEntry("status", OrderStatus.PENDING.name())
                .containsEntry("currency", "USD")
                .containsEntry("lineItemCount", 1);

        Map<String, Object> metadata = parseJson(log.getMetadata());
        assertThat(metadata)
                .containsEntry("orderNumber", order.orderNumber())
                .containsEntry("customerReference", "CUST-AUD-001");
    }

    @Test
    @DisplayName("6. Order confirmation creates ORDER_CONFIRMED audit log with before/after state")
    void orderConfirmationCreatesAuditLog() {
        inventoryService.recordOpeningBalance(
                orgId, actorUserId,
                new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 50L, "Init"),
                null
        );

        CreateOrderRequest request = new CreateOrderRequest(
                "CUST-AUD-002", "EXT-REF-002", "USD",
                List.of(new OrderItemRequest(productA.getId(), 10L, new BigDecimal("25.0000")))
        );
        OrderResponse order = orderService.createOrder(orgId, actorUserId, request, null);
        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, order.id(), null);

        orderService.confirmOrder(orgId, actorUserId, order.id(), null);

        List<AuditLog> logs = auditLogService.findAuditLogs(
                orgId, AuditEntityType.ORDER, AuditAction.ORDER_CONFIRMED,
                actorUserId, null, null, 10, 0
        );

        assertThat(logs).hasSize(1);
        AuditLog log = logs.getFirst();
        assertThat(log.getEntityId()).isEqualTo(order.id());

        Map<String, Object> before = parseJson(log.getBeforeState());
        assertThat(before).containsEntry("status", OrderStatus.PENDING.name());

        Map<String, Object> after = parseJson(log.getAfterState());
        assertThat(after).containsEntry("status", OrderStatus.CONFIRMED.name());

        Map<String, Object> metadata = parseJson(log.getMetadata());
        assertThat(metadata)
                .containsEntry("orderNumber", order.orderNumber())
                .containsEntry("reservationId", res.id().toString());
    }

    @Test
    @DisplayName("7. Order cancellation with active reservation creates ORDER_CANCELLED and RESERVATION_RELEASED audit logs")
    void orderCancellationWithReservationCreatesBothAuditLogs() {
        inventoryService.recordOpeningBalance(
                orgId, actorUserId,
                new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 50L, "Init"),
                null
        );

        CreateOrderRequest request = new CreateOrderRequest(
                "CUST-AUD-003", "EXT-REF-003", "USD",
                List.of(new OrderItemRequest(productA.getId(), 15L, new BigDecimal("25.0000")))
        );
        OrderResponse order = orderService.createOrder(orgId, actorUserId, request, null);
        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, order.id(), null);

        orderService.cancelOrder(orgId, actorUserId, order.id(), null);

        // 1. ORDER_CANCELLED audit log
        List<AuditLog> orderLogs = auditLogService.findAuditLogs(
                orgId, AuditEntityType.ORDER, AuditAction.ORDER_CANCELLED,
                actorUserId, null, null, 10, 0
        );
        assertThat(orderLogs).hasSize(1);
        AuditLog orderLog = orderLogs.getFirst();
        assertThat(orderLog.getEntityId()).isEqualTo(order.id());

        Map<String, Object> orderBefore = parseJson(orderLog.getBeforeState());
        assertThat(orderBefore).containsEntry("status", OrderStatus.PENDING.name());

        Map<String, Object> orderAfter = parseJson(orderLog.getAfterState());
        assertThat(orderAfter).containsEntry("status", OrderStatus.CANCELLED.name());

        Map<String, Object> orderMeta = parseJson(orderLog.getMetadata());
        assertThat(orderMeta)
                .containsEntry("orderNumber", order.orderNumber())
                .containsEntry("reason", "Order cancelled")
                .containsEntry("hadReservation", true);

        // 2. RESERVATION_RELEASED audit log
        List<AuditLog> resLogs = auditLogService.findAuditLogs(
                orgId, AuditEntityType.RESERVATION, AuditAction.RESERVATION_RELEASED,
                actorUserId, null, null, 10, 0
        );
        assertThat(resLogs).hasSize(1);
        AuditLog resLog = resLogs.getFirst();
        assertThat(resLog.getEntityId()).isEqualTo(res.id());

        Map<String, Object> resBefore = parseJson(resLog.getBeforeState());
        assertThat(resBefore).containsEntry("status", ReservationStatus.ACTIVE.name());

        Map<String, Object> resAfter = parseJson(resLog.getAfterState());
        assertThat(resAfter).containsEntry("status", ReservationStatus.RELEASED.name());

        Map<String, Object> resMeta = parseJson(resLog.getMetadata());
        assertThat(resMeta)
                .containsEntry("orderId", order.id().toString())
                .containsEntry("reason", "Order cancelled");
        assertThat(resMeta.get("transactionId")).isNotNull();
        UUID txId = UUID.fromString(resMeta.get("transactionId").toString());
        Integer txCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_transactions WHERE id = ?",
                Integer.class,
                txId
        );
        assertThat(txCount).isEqualTo(1);
    }

    @Test
    @DisplayName("8. Order fulfillment creates ORDER_FULFILLED and RESERVATION_CONSUMED audit logs")
    void orderFulfillmentCreatesBothAuditLogs() {
        inventoryService.recordOpeningBalance(
                orgId, actorUserId,
                new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 50L, "Init"),
                null
        );

        CreateOrderRequest request = new CreateOrderRequest(
                "CUST-AUD-004", "EXT-REF-004", "USD",
                List.of(new OrderItemRequest(productA.getId(), 20L, new BigDecimal("25.0000")))
        );
        OrderResponse order = orderService.createOrder(orgId, actorUserId, request, null);
        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, order.id(), null);
        orderService.confirmOrder(orgId, actorUserId, order.id(), null);

        orderService.fulfillOrder(orgId, actorUserId, order.id(), null);

        // 1. ORDER_FULFILLED audit log
        List<AuditLog> orderLogs = auditLogService.findAuditLogs(
                orgId, AuditEntityType.ORDER, AuditAction.ORDER_FULFILLED,
                actorUserId, null, null, 10, 0
        );
        assertThat(orderLogs).hasSize(1);
        AuditLog orderLog = orderLogs.getFirst();
        assertThat(orderLog.getEntityId()).isEqualTo(order.id());

        Map<String, Object> orderBefore = parseJson(orderLog.getBeforeState());
        assertThat(orderBefore).containsEntry("status", OrderStatus.CONFIRMED.name());

        Map<String, Object> orderAfter = parseJson(orderLog.getAfterState());
        assertThat(orderAfter).containsEntry("status", OrderStatus.FULFILLED.name());

        // 2. RESERVATION_CONSUMED audit log
        List<AuditLog> resLogs = auditLogService.findAuditLogs(
                orgId, AuditEntityType.RESERVATION, AuditAction.RESERVATION_CONSUMED,
                actorUserId, null, null, 10, 0
        );
        assertThat(resLogs).hasSize(1);
        AuditLog resLog = resLogs.getFirst();
        assertThat(resLog.getEntityId()).isEqualTo(res.id());

        Map<String, Object> resBefore = parseJson(resLog.getBeforeState());
        assertThat(resBefore).containsEntry("status", ReservationStatus.ACTIVE.name());

        Map<String, Object> resAfter = parseJson(resLog.getAfterState());
        assertThat(resAfter).containsEntry("status", "CONSUMED");

        Map<String, Object> resMeta = parseJson(resLog.getMetadata());
        assertThat(resMeta).containsEntry("orderId", order.id().toString());
    }

    @Test
    @DisplayName("9. Reservation creation creates RESERVATION_CREATED audit log with after_state ACTIVE")
    void reservationCreationCreatesAuditLog() {
        inventoryService.recordOpeningBalance(
                orgId, actorUserId,
                new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 50L, "Init"),
                null
        );

        CreateOrderRequest request = new CreateOrderRequest(
                "CUST-AUD-005", "EXT-REF-005", "USD",
                List.of(new OrderItemRequest(productA.getId(), 10L, new BigDecimal("25.0000")))
        );
        OrderResponse order = orderService.createOrder(orgId, actorUserId, request, null);
        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, order.id(), null);

        List<AuditLog> logs = auditLogService.findAuditLogs(
                orgId, AuditEntityType.RESERVATION, AuditAction.RESERVATION_CREATED,
                actorUserId, null, null, 10, 0
        );

        assertThat(logs).hasSize(1);
        AuditLog log = logs.getFirst();
        assertThat(log.getEntityId()).isEqualTo(res.id());

        Map<String, Object> after = parseJson(log.getAfterState());
        assertThat(after).containsEntry("status", ReservationStatus.ACTIVE.name());

        Map<String, Object> metadata = parseJson(log.getMetadata());
        assertThat(metadata)
                .containsEntry("orderId", order.id().toString());
    }

    @Test
    @DisplayName("10. Background reservation expiration worker creates RESERVATION_EXPIRED audit log with null system actor")
    void reservationExpirationWorkerCreatesAuditLogWithSystemActor() {
        inventoryService.recordOpeningBalance(
                orgId, actorUserId,
                new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 50L, "Init"),
                null
        );

        CreateOrderRequest request = new CreateOrderRequest(
                "CUST-AUD-006", "EXT-REF-006", "USD",
                List.of(new OrderItemRequest(productA.getId(), 15L, new BigDecimal("25.0000")))
        );
        OrderResponse order = orderService.createOrder(orgId, actorUserId, request, null);
        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, order.id(), null);

        // Simulate expiration in PostgreSQL database clock
        jdbcTemplate.update("UPDATE reservations SET expires_at = NOW() - INTERVAL '5 minutes' WHERE id = ?", res.id());

        // Execute batch expiration sweep
        ExpirationBatchResult result = expirationService.sweepExpiredReservations(orgId);
        assertThat(result.examinedCount()).isEqualTo(1);
        assertThat(result.successCount()).isEqualTo(1);

        // Find audit log for RESERVATION_EXPIRED
        List<AuditLog> logs = auditLogService.findAuditLogs(
                orgId, AuditEntityType.RESERVATION, AuditAction.RESERVATION_EXPIRED,
                null, null, null, 10, 0
        );

        assertThat(logs).hasSize(1);
        AuditLog log = logs.getFirst();
        assertThat(log.getEntityId()).isEqualTo(res.id());
        assertThat(log.getActorUserId()).isNull(); // Explicit system actor

        Map<String, Object> before = parseJson(log.getBeforeState());
        assertThat(before).containsEntry("status", ReservationStatus.ACTIVE.name());

        Map<String, Object> after = parseJson(log.getAfterState());
        assertThat(after).containsEntry("status", ReservationStatus.EXPIRED.name());

        Map<String, Object> metadata = parseJson(log.getMetadata());
        assertThat(metadata)
                .containsEntry("orderId", order.id().toString())
                .containsEntry("trigger", "SYSTEM_BACKGROUND_WORKER");
    }

    // =========================================================================
    // 4. ORGANIZATION MEMBERSHIP MUTATION AUDITS
    // =========================================================================

    @Test
    @DisplayName("11. Membership mutations create MEMBERSHIP_CREATED, ROLE_UPDATED, and DEACTIVATED audit logs")
    void membershipMutationsCreateAuditLogs() {
        User targetUser = userRepository.save(User.create("target.user." + UUID.randomUUID() + "@stockguard.com", "hash"));

        // 1. Add member
        membershipService.addMember(orgId, actorUserId, targetUser.getId(), Role.VIEWER);

        List<AuditLog> addLogs = auditLogService.findAuditLogs(
                orgId, AuditEntityType.ORGANIZATION_MEMBERSHIP, AuditAction.MEMBERSHIP_CREATED,
                actorUserId, null, null, 10, 0
        );
        assertThat(addLogs).hasSize(1);
        AuditLog addLog = addLogs.getFirst();
        assertThat(addLog.getEntityId()).isEqualTo(targetUser.getId());
        Map<String, Object> addAfter = parseJson(addLog.getAfterState());
        assertThat(addAfter).containsEntry("role", Role.VIEWER.name()).containsEntry("active", true);

        // 2. Update role
        membershipService.updateMemberRole(orgId, actorUserId, targetUser.getId(), Role.OPERATOR);

        List<AuditLog> updateLogs = auditLogService.findAuditLogs(
                orgId, AuditEntityType.ORGANIZATION_MEMBERSHIP, AuditAction.MEMBERSHIP_ROLE_UPDATED,
                actorUserId, null, null, 10, 0
        );
        assertThat(updateLogs).hasSize(1);
        AuditLog updateLog = updateLogs.getFirst();
        assertThat(updateLog.getEntityId()).isEqualTo(targetUser.getId());
        Map<String, Object> updateBefore = parseJson(updateLog.getBeforeState());
        assertThat(updateBefore).containsEntry("role", Role.VIEWER.name());
        Map<String, Object> updateAfter = parseJson(updateLog.getAfterState());
        assertThat(updateAfter).containsEntry("role", Role.OPERATOR.name());

        // 3. Deactivate member
        membershipService.deactivateMember(orgId, actorUserId, targetUser.getId());

        List<AuditLog> deactLogs = auditLogService.findAuditLogs(
                orgId, AuditEntityType.ORGANIZATION_MEMBERSHIP, AuditAction.MEMBERSHIP_DEACTIVATED,
                actorUserId, null, null, 10, 0
        );
        assertThat(deactLogs).hasSize(1);
        AuditLog deactLog = deactLogs.getFirst();
        assertThat(deactLog.getEntityId()).isEqualTo(targetUser.getId());
        Map<String, Object> deactBefore = parseJson(deactLog.getBeforeState());
        assertThat(deactBefore).containsEntry("active", true);
        Map<String, Object> deactAfter = parseJson(deactLog.getAfterState());
        assertThat(deactAfter).containsEntry("active", false);
    }

    @Test
    @DisplayName("11b. Membership mutations reject null actor without mutating data or auditing")
    void membershipMutationsRejectNullActorWithoutMutatingDataOrAuditing() {
        User targetUser = userRepository.save(User.create("target.nullactor." + UUID.randomUUID() + "@stockguard.com", "hash"));

        // 1. addMember with null actor throws NullPointerException
        assertThatThrownBy(() -> membershipService.addMember(orgId, null, targetUser.getId(), Role.VIEWER))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorUserId must not be null");

        // Verify no membership was created
        assertThat(membershipRepository.findByOrganizationIdAndUserId(orgId, targetUser.getId())).isEmpty();

        // 2. Add member legitimately first
        membershipService.addMember(orgId, actorUserId, targetUser.getId(), Role.VIEWER);

        // 3. updateMemberRole with null actor throws NullPointerException
        assertThatThrownBy(() -> membershipService.updateMemberRole(orgId, null, targetUser.getId(), Role.OPERATOR))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorUserId must not be null");

        // Verify role was not changed
        OrganizationMembership mem = membershipRepository.findByOrganizationIdAndUserId(orgId, targetUser.getId()).orElseThrow();
        assertThat(mem.getRole()).isEqualTo(Role.VIEWER);

        // 4. deactivateMember with null actor throws NullPointerException
        assertThatThrownBy(() -> membershipService.deactivateMember(orgId, null, targetUser.getId()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorUserId must not be null");

        // Verify active state was not changed
        mem = membershipRepository.findByOrganizationIdAndUserId(orgId, targetUser.getId()).orElseThrow();
        assertThat(mem.isActive()).isTrue();

        // Verify only 1 audit log was ever created (the legitimate addMember)
        List<AuditLog> logs = auditLogService.findAuditLogs(
                orgId, AuditEntityType.ORGANIZATION_MEMBERSHIP, null,
                null, null, null, 10, 0
        );
        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst().getAction()).isEqualTo(AuditAction.MEMBERSHIP_CREATED);
    }

    // =========================================================================
    // 5. TRANSACTIONAL INTEGRITY & IDEMPOTENCY
    // =========================================================================

    @Test
    @DisplayName("12. Business transaction rollback rolls back associated audit log")
    void businessTransactionRollbackLeavesNoAuditLog() {
        inventoryService.recordOpeningBalance(
                orgId, actorUserId,
                new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 100L, "Init"),
                null
        );

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> txTemplate.execute(status -> {
            inventoryService.adjustStock(
                    orgId,
                    actorUserId,
                    new AdjustStockRequest(productA.getId(), warehouse1.getId(), -10L, "Temporary adjustment", null),
                    null
            );
            throw new RuntimeException("Forced rollback for testing transactional atomicity");
        })).isInstanceOf(RuntimeException.class).hasMessageContaining("Forced rollback");

        // Verify that the audit log for STOCK_ADJUSTED was NOT persisted
        List<AuditLog> logs = auditLogService.findAuditLogs(
                orgId, AuditEntityType.INVENTORY_BALANCE, AuditAction.STOCK_ADJUSTED,
                actorUserId, null, null, 10, 0
        );
        assertThat(logs).isEmpty();

        // Verify balance remained 100
        Long onHand = jdbcTemplate.queryForObject(
                "SELECT on_hand FROM inventory_balances WHERE organization_id = ? AND product_id = ? AND warehouse_id = ?",
                Long.class, orgId, productA.getId(), warehouse1.getId()
        );
        assertThat(onHand).isEqualTo(100L);
    }

    @Test
    @DisplayName("13. Idempotent replay produces exactly 1 audit log and no duplicates")
    void idempotentReplayProducesNoDuplicateAuditLog() {
        inventoryService.recordOpeningBalance(
                orgId, actorUserId,
                new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 200L, "Init"),
                null
        );

        String idempotencyKey = "idem-transfer-audit-" + UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(
                warehouse1.getId(),
                warehouse2.getId(),
                List.of(new TransferItemRequest(productA.getId(), 40L)),
                "Idempotent transfer test"
        );

        // First attempt -> executes and audits
        TransferResponse res1 = transferService.transferStock(orgId, actorUserId, request, idempotencyKey);
        assertThat(res1).isNotNull();

        Integer auditCount1 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE organization_id = ? AND entity_id = ? AND action = ?",
                Integer.class, orgId, res1.id(), AuditAction.TRANSFER_COMPLETED
        );
        assertThat(auditCount1).isEqualTo(1);

        // Replay attempt with same key and same payload -> returns cached response, lambda does not re-run
        TransferResponse res2 = transferService.transferStock(orgId, actorUserId, request, idempotencyKey);
        assertThat(res2.id()).isEqualTo(res1.id());

        Integer auditCount2 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE organization_id = ? AND entity_id = ? AND action = ?",
                Integer.class, orgId, res1.id(), AuditAction.TRANSFER_COMPLETED
        );
        assertThat(auditCount2).isEqualTo(1);
    }

    @Test
    @DisplayName("14. User-initiated audit methods reject null actorUserId with NullPointerException")
    void userInitiatedAuditRejectsNullActor() {
        UUID dummyId = UUID.randomUUID();

        assertThatThrownBy(() -> auditLogService.logOpeningBalance(orgId, null, dummyId, dummyId, 10L, dummyId))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorUserId must not be null");

        assertThatThrownBy(() -> auditLogService.logStockReceipt(orgId, null, dummyId, dummyId, 10L, dummyId))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorUserId must not be null");

        assertThatThrownBy(() -> auditLogService.logInventoryAdjustment(orgId, null, dummyId, dummyId, 10L, "Reason", dummyId))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorUserId must not be null");

        assertThatThrownBy(() -> auditLogService.logTransferExecuted(orgId, null, dummyId, dummyId, dummyId, 1, dummyId))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorUserId must not be null");

        assertThatThrownBy(() -> auditLogService.logOrderCreated(orgId, null, dummyId, "ORD-1", BigDecimal.TEN, 1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorUserId must not be null");

        assertThatThrownBy(() -> auditLogService.logOrderConfirmed(orgId, null, dummyId, "ORD-1", dummyId))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorUserId must not be null");

        assertThatThrownBy(() -> auditLogService.logOrderCancelled(orgId, null, dummyId, "ORD-1", "Reason"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorUserId must not be null");

        assertThatThrownBy(() -> auditLogService.logOrderFulfilled(orgId, null, dummyId, "ORD-1", dummyId, dummyId))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorUserId must not be null");

        assertThatThrownBy(() -> auditLogService.logReservationCreated(orgId, null, dummyId, dummyId, 1, 10L))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorUserId must not be null");

        assertThatThrownBy(() -> auditLogService.logReservationReleased(orgId, null, dummyId, dummyId, "Reason", dummyId))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorUserId must not be null");

        assertThatThrownBy(() -> auditLogService.logReservationConsumed(orgId, null, dummyId, dummyId, dummyId))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorUserId must not be null");

        assertThatThrownBy(() -> auditLogService.logMemberAdded(orgId, null, dummyId, "VIEWER", false))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorUserId must not be null");

        assertThatThrownBy(() -> auditLogService.logMemberRoleUpdated(orgId, null, dummyId, "VIEWER", "ADMIN"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorUserId must not be null");

        assertThatThrownBy(() -> auditLogService.logMemberDeactivated(orgId, null, dummyId))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorUserId must not be null");
    }
}
