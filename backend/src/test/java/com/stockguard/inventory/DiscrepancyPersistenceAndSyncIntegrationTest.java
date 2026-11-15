package com.stockguard.inventory;

import com.stockguard.audit.domain.AuditAction;
import com.stockguard.audit.domain.AuditEntityType;
import com.stockguard.audit.domain.AuditLog;
import com.stockguard.audit.repository.AuditLogRepository;
import com.stockguard.inventory.domain.DiscrepancyStatus;
import com.stockguard.inventory.domain.DiscrepancyType;
import com.stockguard.inventory.domain.ReconciliationDiscrepancyEntity;
import com.stockguard.inventory.dto.DiscrepancySyncResult;
import com.stockguard.inventory.dto.ReconciliationDiscrepancy;
import com.stockguard.inventory.dto.ReconciliationReport;
import com.stockguard.inventory.repository.JdbcDiscrepancyOperations;
import com.stockguard.inventory.repository.ReconciliationDiscrepancyRepository;
import com.stockguard.inventory.service.DiscrepancyManagementService;
import com.stockguard.inventory.service.InventoryReconciliationService;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.product.domain.Product;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.UserRepository;
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

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class DiscrepancyPersistenceAndSyncIntegrationTest {

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
        registry.add("stockguard.reconciliation.scheduled.enabled", () -> "false");
    }

    @Autowired
    private DiscrepancyManagementService discrepancyManagementService;

    @Autowired
    private InventoryReconciliationService reconciliationService;

    @Autowired
    private JdbcDiscrepancyOperations jdbcDiscrepancyOperations;

    @Autowired
    private ReconciliationDiscrepancyRepository discrepancyRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID orgId;
    private UUID managerUserId;
    private UUID productId;
    private UUID warehouseId;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(Organization.create("Sync Org " + UUID.randomUUID()));
        orgId = org.getId();

        User user = User.create("sync.mgr." + UUID.randomUUID() + "@stockguard.com", "hash");
        user = userRepository.save(user);
        managerUserId = user.getId();

        membershipRepository.save(OrganizationMembership.create(orgId, managerUserId, Role.MANAGER));

        Product product = productRepository.save(new Product(orgId, "SKU-SYNC-" + UUID.randomUUID().toString().substring(0, 6), "Sync Product", null));
        productId = product.getId();

        Warehouse warehouse = warehouseRepository.save(new Warehouse(orgId, "WH-SYNC-" + UUID.randomUUID().toString().substring(0, 6), "Sync Warehouse", "Loc", 1));
        warehouseId = warehouse.getId();
    }

    @Test
    @DisplayName("New discrepancy detection inserts active OPEN record and logs audit atomically")
    void newDiscrepancyInsertedAndAudited() {
        ReconciliationDiscrepancy disc = new ReconciliationDiscrepancy(
                productId,
                warehouseId,
                100,
                90,
                20,
                20,
                "DELTA_MISMATCH"
        );

        ReconciliationReport report = new ReconciliationReport(
                orgId,
                1,
                0,
                1,
                List.of(disc),
                Instant.now()
        );

        DiscrepancySyncResult result = discrepancyManagementService.syncDiscrepancies(orgId, report);

        assertThat(result.newDiscrepancies()).isEqualTo(1);
        assertThat(result.repeatedObservations()).isEqualTo(0);
        assertThat(result.autoResolved()).isEqualTo(0);

        List<ReconciliationDiscrepancyEntity> active = discrepancyRepository.findByOrganizationIdAndStatusIn(
                orgId,
                List.of(DiscrepancyStatus.OPEN)
        );
        assertThat(active).hasSize(1);
        ReconciliationDiscrepancyEntity entity = active.get(0);
        assertThat(entity.getProductId()).isEqualTo(productId);
        assertThat(entity.getWarehouseId()).isEqualTo(warehouseId);
        assertThat(entity.getStatus()).isEqualTo(DiscrepancyStatus.OPEN);
        assertThat(entity.getConsecutiveDetectionCount()).isEqualTo(1);
        assertThat(entity.getObservedBalanceOnHand()).isEqualTo(100);
        assertThat(entity.getObservedLedgerOnHand()).isEqualTo(90);

        // Verify audit log inside Tx2
        List<AuditLog> auditLogs = auditLogRepository.findAll().stream()
                .filter(a -> a.getOrganizationId().equals(orgId))
                .filter(a -> AuditAction.RECONCILIATION_DISCREPANCY_DETECTED.equals(a.getAction()))
                .toList();
        assertThat(auditLogs).hasSize(1);
        AuditLog auditLog = auditLogs.get(0);
        assertThat(auditLog.getEntityType()).isEqualTo(AuditEntityType.RECONCILIATION);
        assertThat(auditLog.getActorUserId()).isNull(); // System actor
        assertThat(auditLog.getMetadata()).contains("totalDiscrepancyCount");
        assertThat(auditLog.getMetadata()).contains("1");
    }

    @Test
    @DisplayName("Repeated mismatch increments detection count and updates quantities while preserving status and assignment")
    void repeatedMismatchUpdatesDetectionCountAndPreservesAssignment() {
        // Initial detection
        ReconciliationDiscrepancy disc1 = new ReconciliationDiscrepancy(
                productId, warehouseId, 100, 90, 0, 0, "DELTA_MISMATCH"
        );
        discrepancyManagementService.syncDiscrepancies(orgId, new ReconciliationReport(orgId, 1, 0, 1, List.of(disc1), Instant.now()));

        ReconciliationDiscrepancyEntity entity = discrepancyRepository.findByOrganizationIdAndStatusIn(orgId, List.of(DiscrepancyStatus.OPEN)).get(0);

        // Transition to INVESTIGATING with assigned user and notes
        entity.setStatus(DiscrepancyStatus.INVESTIGATING);
        entity.setAssignedToUserId(managerUserId);
        entity.setInvestigationNotes("Checking physical shelf A1");
        discrepancyRepository.save(entity);

        // Second sweep with updated quantities
        ReconciliationDiscrepancy disc2 = new ReconciliationDiscrepancy(
                productId, warehouseId, 100, 85, 0, 0, "DELTA_MISMATCH"
        );
        DiscrepancySyncResult result = discrepancyManagementService.syncDiscrepancies(orgId, new ReconciliationReport(orgId, 1, 0, 1, List.of(disc2), Instant.now()));

        assertThat(result.newDiscrepancies()).isEqualTo(0);
        assertThat(result.repeatedObservations()).isEqualTo(1);

        ReconciliationDiscrepancyEntity updated = discrepancyRepository.findById(entity.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(DiscrepancyStatus.INVESTIGATING); // PRESERVED
        assertThat(updated.getAssignedToUserId()).isEqualTo(managerUserId); // PRESERVED
        assertThat(updated.getInvestigationNotes()).isEqualTo("Checking physical shelf A1"); // PRESERVED
        assertThat(updated.getConsecutiveDetectionCount()).isEqualTo(2); // INCREMENTED
        assertThat(updated.getObservedLedgerOnHand()).isEqualTo(85); // UPDATED
    }

    @Test
    @DisplayName("Clean parity auto-resolves OPEN discrepancy with SYSTEM source and logs audit")
    void cleanParityAutoResolvesOpenDiscrepancy() {
        // Initial detection: OPEN
        ReconciliationDiscrepancy disc = new ReconciliationDiscrepancy(
                productId, warehouseId, 100, 90, 0, 0, "DELTA_MISMATCH"
        );
        discrepancyManagementService.syncDiscrepancies(orgId, new ReconciliationReport(orgId, 1, 0, 1, List.of(disc), Instant.now()));

        // Second sweep: 0 discrepancies (clean parity observed)
        ReconciliationReport cleanReport = new ReconciliationReport(
                orgId, 1, 1, 0, Collections.emptyList(), Instant.now()
        );
        DiscrepancySyncResult result = discrepancyManagementService.syncDiscrepancies(orgId, cleanReport);

        assertThat(result.autoResolved()).isEqualTo(1);

        // Verify entity state
        List<ReconciliationDiscrepancyEntity> active = discrepancyRepository.findByOrganizationIdAndStatusIn(
                orgId, List.of(DiscrepancyStatus.OPEN, DiscrepancyStatus.INVESTIGATING)
        );
        assertThat(active).isEmpty();

        List<ReconciliationDiscrepancyEntity> all = discrepancyRepository.findAll().stream()
                .filter(d -> d.getOrganizationId().equals(orgId))
                .toList();
        assertThat(all).hasSize(1);
        ReconciliationDiscrepancyEntity resolved = all.get(0);
        assertThat(resolved.getStatus()).isEqualTo(DiscrepancyStatus.RESOLVED);
        assertThat(resolved.getResolutionSource().name()).isEqualTo("SYSTEM");
        assertThat(resolved.getResolvedByUserId()).isNull();
        assertThat(resolved.getResolvedAt()).isNotNull();
        assertThat(resolved.getResolutionReason()).contains("System auto-resolved");
        assertThat(resolved.getLastVerifiedAt()).isNotNull();
        assertThat(resolved.getConsecutiveDetectionCount()).isEqualTo(1); // FROZEN, NOT RESET

        // Verify audit log contains RECONCILIATION_COMPLETED
        List<AuditLog> completedLogs = auditLogRepository.findAll().stream()
                .filter(a -> a.getOrganizationId().equals(orgId))
                .filter(a -> AuditAction.RECONCILIATION_COMPLETED.equals(a.getAction()))
                .toList();
        assertThat(completedLogs).hasSize(1);
    }

    @Test
    @DisplayName("Clean parity preserves ACKNOWLEDGED and INVESTIGATING discrepancies without auto-resolving")
    void cleanParityPreservesAcknowledgedAndInvestigatingDiscrepancies() {
        // Create product 2
        Product product2 = productRepository.save(new Product(orgId, "SKU-SYNC-2-" + UUID.randomUUID().toString().substring(0, 6), "Sync Product 2", null));

        // Position 1: ACKNOWLEDGED
        jdbcDiscrepancyOperations.upsertActiveDiscrepancy(orgId, productId, warehouseId, DiscrepancyType.DELTA_MISMATCH, 100, 90, 0, 0);
        jdbcTemplate.update("UPDATE reconciliation_discrepancies SET status = 'ACKNOWLEDGED' WHERE organization_id = ? AND product_id = ?", orgId, productId);

        // Position 2: INVESTIGATING
        jdbcDiscrepancyOperations.upsertActiveDiscrepancy(orgId, product2.getId(), warehouseId, DiscrepancyType.DELTA_MISMATCH, 50, 40, 0, 0);
        jdbcTemplate.update("UPDATE reconciliation_discrepancies SET status = 'INVESTIGATING', assigned_to_user_id = ? WHERE organization_id = ? AND product_id = ?",
                managerUserId, orgId, product2.getId());

        // Sweep with clean parity
        ReconciliationReport cleanReport = new ReconciliationReport(orgId, 2, 2, 0, Collections.emptyList(), Instant.now());
        DiscrepancySyncResult result = discrepancyManagementService.syncDiscrepancies(orgId, cleanReport);

        assertThat(result.autoResolved()).isEqualTo(0);
        assertThat(result.parityVerifiedPreserved()).isEqualTo(2);

        // Verify both positions remain active
        ReconciliationDiscrepancyEntity p1 = discrepancyRepository.findActiveByPosition(orgId, productId, warehouseId, List.of(DiscrepancyStatus.ACKNOWLEDGED)).orElseThrow();
        assertThat(p1.getStatus()).isEqualTo(DiscrepancyStatus.ACKNOWLEDGED);
        assertThat(p1.getLastVerifiedAt()).isNotNull();

        ReconciliationDiscrepancyEntity p2 = discrepancyRepository.findActiveByPosition(orgId, product2.getId(), warehouseId, List.of(DiscrepancyStatus.INVESTIGATING)).orElseThrow();
        assertThat(p2.getStatus()).isEqualTo(DiscrepancyStatus.INVESTIGATING);
        assertThat(p2.getAssignedToUserId()).isEqualTo(managerUserId);
        assertThat(p2.getLastVerifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("Atomic rollback: if Tx2 fails, both discrepancy records and audit log records roll back completely")
    void atomicRollbackOnTx2Failure() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> txTemplate.execute(status -> {
            ReconciliationDiscrepancy disc = new ReconciliationDiscrepancy(
                    productId, warehouseId, 100, 90, 0, 0, "DELTA_MISMATCH"
            );
            ReconciliationReport report = new ReconciliationReport(orgId, 1, 0, 1, List.of(disc), Instant.now());

            discrepancyManagementService.syncDiscrepancies(orgId, report);

            // Simulate unexpected failure in Tx2
            throw new RuntimeException("Simulated Tx2 transaction failure!");
        })).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated Tx2 transaction failure!");

        // Verify ZERO discrepancies persisted
        List<ReconciliationDiscrepancyEntity> discrepancies = discrepancyRepository.findAll().stream()
                .filter(d -> d.getOrganizationId().equals(orgId))
                .toList();
        assertThat(discrepancies).isEmpty();

        // Verify ZERO audit logs persisted for this organization
        List<AuditLog> auditLogs = auditLogRepository.findAll().stream()
                .filter(a -> a.getOrganizationId().equals(orgId))
                .toList();
        assertThat(auditLogs).isEmpty();
    }

    @Test
    @DisplayName("Concurrent upserts for the same position do not produce duplicate active discrepancy records")
    void concurrentUpsertsDoNotDuplicateActiveRecords() throws InterruptedException {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    jdbcDiscrepancyOperations.upsertActiveDiscrepancy(
                            orgId,
                            productId,
                            warehouseId,
                            DiscrepancyType.DELTA_MISMATCH,
                            100,
                            90,
                            0,
                            0
                    );
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // Log error if any
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount);

        // Crucial invariant: exactly ONE active discrepancy record exists for this position!
        List<ReconciliationDiscrepancyEntity> active = discrepancyRepository.findByOrganizationIdAndStatusIn(
                orgId,
                List.of(DiscrepancyStatus.OPEN, DiscrepancyStatus.ACKNOWLEDGED, DiscrepancyStatus.INVESTIGATING)
        );
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getConsecutiveDetectionCount()).isEqualTo(threadCount);
    }

    @Test
    @DisplayName("Stale report protection: clean parity report with observation timestamp before last detection does not auto-resolve discrepancy")
    void staleReconciliationReportDoesNotResolveNewerDiscrepancy() {
        // Initial detection
        ReconciliationDiscrepancy disc = new ReconciliationDiscrepancy(
                productId, warehouseId, 100, 90, 0, 0, "DELTA_MISMATCH"
        );
        discrepancyManagementService.syncDiscrepancies(orgId, new ReconciliationReport(orgId, 1, 0, 1, List.of(disc), Instant.now()));

        ReconciliationDiscrepancyEntity entity = discrepancyRepository.findByOrganizationIdAndStatusIn(orgId, List.of(DiscrepancyStatus.OPEN)).get(0);
        assertThat(entity.getStatus()).isEqualTo(DiscrepancyStatus.OPEN);

        // Advance last_detected_at in database to simulated future
        Instant newerDetectionTime = Instant.now().plusSeconds(600);
        jdbcTemplate.update(
                "UPDATE reconciliation_discrepancies SET last_detected_at = ? WHERE id = ?",
                java.sql.Timestamp.from(newerDetectionTime),
                entity.getId()
        );

        // Stale report generated with current timestamp (strictly older than newerDetectionTime)
        Instant staleReportTime = Instant.now();
        ReconciliationReport staleCleanReport = new ReconciliationReport(
                orgId, 1, 1, 0, Collections.emptyList(), staleReportTime
        );

        DiscrepancySyncResult staleResult = discrepancyManagementService.syncDiscrepancies(orgId, staleCleanReport);

        // Stale clean report MUST NOT auto-resolve the newer discrepancy!
        assertThat(staleResult.autoResolved()).isEqualTo(0);

        ReconciliationDiscrepancyEntity unchanged = discrepancyRepository.findById(entity.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(DiscrepancyStatus.OPEN);

        // Fresh report with timestamp after newerDetectionTime
        Instant freshReportTime = newerDetectionTime.plusSeconds(10);
        ReconciliationReport freshCleanReport = new ReconciliationReport(
                orgId, 1, 1, 0, Collections.emptyList(), freshReportTime
        );

        DiscrepancySyncResult freshResult = discrepancyManagementService.syncDiscrepancies(orgId, freshCleanReport);

        // Fresh clean report successfully auto-resolves
        assertThat(freshResult.autoResolved()).isEqualTo(1);
        ReconciliationDiscrepancyEntity resolved = discrepancyRepository.findById(entity.getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(DiscrepancyStatus.RESOLVED);
    }

    @Test
    @DisplayName("Database-grounded observation timestamp prevents clean reconciliation report from resolving newer discrepancy")
    void databaseGroundedReconciliationReportProtectsAgainstNewerDiscrepancies() {
        // 1. Generate clean reconciliation report directly through InventoryReconciliationService (Tx1)
        // Tx1 query acquires snapshot and returns report.reconciledAt() from PostgreSQL CURRENT_TIMESTAMP.
        ReconciliationReport cleanReport = reconciliationService.reconcile(orgId);
        assertThat(cleanReport.discrepancies()).isEmpty();
        assertThat(cleanReport.reconciledAt()).isNotNull();

        // 2. Subsequent to Tx1 snapshot, an active discrepancy is detected and recorded in PostgreSQL
        // with last_detected_at generated strictly after the reconciliation snapshot timestamp.
        JdbcDiscrepancyOperations.UpsertResult upsert = jdbcDiscrepancyOperations.upsertActiveDiscrepancy(
                orgId,
                productId,
                warehouseId,
                DiscrepancyType.DELTA_MISMATCH,
                100, 90, 0, 0
        );
        assertThat(upsert.status()).isEqualTo(DiscrepancyStatus.OPEN);

        // Ensure in database that the discrepancy's last_detected_at is strictly after the Tx1 report timestamp
        jdbcTemplate.update(
                "UPDATE reconciliation_discrepancies SET last_detected_at = ? WHERE id = ?",
                java.sql.Timestamp.from(cleanReport.reconciledAt().plusMillis(500)),
                upsert.id()
        );

        // 3. Pass cleanReport (with Tx1 database snapshot timestamp) to DiscrepancyManagementService (Tx2)
        DiscrepancySyncResult syncResult = discrepancyManagementService.syncDiscrepancies(orgId, cleanReport);

        // 4. Invariant: The newer discrepancy must NOT be auto-resolved because last_detected_at > report.reconciledAt()
        assertThat(syncResult.autoResolved()).isEqualTo(0);

        ReconciliationDiscrepancyEntity entity = discrepancyRepository.findById(upsert.id()).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(DiscrepancyStatus.OPEN);
        assertThat(entity.getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("Clock divergence: exposes flaw where application Instant.now() would falsely auto-resolve newer discrepancy, while database-grounded timestamp protects it")
    void clockDivergenceExposesFlawInApplicationGeneratedTimestamp() {
        // 1. Run real database reconciliation (Tx1) to get the authoritative database snapshot timestamp (T_db)
        ReconciliationReport databaseGroundedReport = reconciliationService.reconcile(orgId);
        Instant dbSnapshotTime = databaseGroundedReport.reconciledAt();
        assertThat(dbSnapshotTime).isNotNull();

        // 2. An active discrepancy is recorded with last_detected_at shortly after the database snapshot (T_db + 2s)
        JdbcDiscrepancyOperations.UpsertResult upsert = jdbcDiscrepancyOperations.upsertActiveDiscrepancy(
                orgId,
                productId,
                warehouseId,
                DiscrepancyType.DELTA_MISMATCH,
                100, 90, 0, 0
        );
        Instant discrepancyDetectionTime = dbSnapshotTime.plusSeconds(2);
        jdbcTemplate.update(
                "UPDATE reconciliation_discrepancies SET last_detected_at = ? WHERE id = ?",
                java.sql.Timestamp.from(discrepancyDetectionTime),
                upsert.id()
        );

        // 3. Simulate clock skew: If the application server clock was skewed ahead by 30 seconds,
        // the previous implementation's Instant.now() would have produced (T_db + 30s).
        Instant skewedApplicationTime = dbSnapshotTime.plusSeconds(30);
        ReconciliationReport flawedApplicationReport = new ReconciliationReport(
                orgId, 0, 0, 0, Collections.emptyList(), skewedApplicationTime
        );

        // Under the flawed application-clock timestamp, last_detected_at (T_db + 2s) <= reportObservationTime (T_db + 30s)
        // would evaluate to TRUE, exposing the bug by improperly resolving the discrepancy!
        assertThat(discrepancyDetectionTime.isBefore(flawedApplicationReport.reconciledAt())).isTrue();

        // 4. Now execute sync with the real database-grounded report:
        // Because report.reconciledAt() comes directly from PostgreSQL (T_db),
        // discrepancyDetectionTime (T_db + 2s) is strictly AFTER report.reconciledAt() (T_db)!
        assertThat(discrepancyDetectionTime.isAfter(databaseGroundedReport.reconciledAt())).isTrue();

        DiscrepancySyncResult syncResult = discrepancyManagementService.syncDiscrepancies(orgId, databaseGroundedReport);

        // The discrepancy is protected and NOT auto-resolved:
        assertThat(syncResult.autoResolved()).isEqualTo(0);

        ReconciliationDiscrepancyEntity entity = discrepancyRepository.findById(upsert.id()).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(DiscrepancyStatus.OPEN);
        assertThat(entity.getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("Concurrent MVCC visibility race: Tx A uncommitted during Tx1 snapshot establishment, commits before Tx2; falsely auto-resolved")
    void concurrentMvccVisibilityRaceCausesFalseAutoResolution() throws Exception {
        CountDownLatch txAStartedAndInserted = new CountDownLatch(1);
        CountDownLatch tx1SnapshotEstablished = new CountDownLatch(1);
        CountDownLatch txACommitted = new CountDownLatch(1);

        UUID discrepancyId = UUID.randomUUID();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        executor.submit(() -> {
            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            txTemplate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            txTemplate.execute(status -> {
                // Tx A begins before Tx1, so Tx A's CURRENT_TIMESTAMP is earlier than Tx1
                jdbcTemplate.update("""
                    INSERT INTO reconciliation_discrepancies (
                        id, organization_id, product_id, warehouse_id,
                        discrepancy_type, observed_balance_on_hand, observed_ledger_on_hand,
                        observed_balance_reserved, observed_ledger_reserved,
                        status, consecutive_detection_count, first_detected_at, last_detected_at,
                        version, created_at, updated_at
                    ) VALUES (
                        ?, ?, ?, ?,
                        'DELTA_MISMATCH', 100, 90, 0, 0,
                        'OPEN', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    )
                    """, discrepancyId, orgId, productId, warehouseId);

                // Signal that Tx A has inserted the row
                txAStartedAndInserted.countDown();

                // Wait until Tx1 establishes its REPEATABLE READ snapshot
                try {
                    tx1SnapshotEstablished.await(5, TimeUnit.SECONDS);
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
            txACommitted.countDown();
        });

        // 1. Wait for Tx A to insert the discrepancy row while uncommitted
        assertThat(txAStartedAndInserted.await(5, TimeUnit.SECONDS)).isTrue();

        // 2. Tx1 runs under REPEATABLE READ snapshot isolation while Tx A is still uncommitted.
        // Tx1's snapshot cannot see Tx A's uncommitted discrepancy.
        ReconciliationReport report = reconciliationService.reconcile(orgId);
        assertThat(report.discrepancies()).isEmpty();

        // 3. Signal Tx A to commit and wait for commit completion
        tx1SnapshotEstablished.countDown();
        assertThat(txACommitted.await(5, TimeUnit.SECONDS)).isTrue();

        // 4. Verify Tx A's discrepancy is committed and visible, with last_detected_at <= report.reconciledAt()
        ReconciliationDiscrepancyEntity entityBeforeSync = discrepancyRepository.findById(discrepancyId).orElseThrow();
        assertThat(entityBeforeSync.getStatus()).isEqualTo(DiscrepancyStatus.OPEN);
        assertThat(entityBeforeSync.getLastDetectedAt()).isBeforeOrEqualTo(report.reconciledAt());

        // 5. Tx2 runs: syncDiscrepancies using report from Tx1
        DiscrepancySyncResult syncResult = discrepancyManagementService.syncDiscrepancies(orgId, report);

        // 6. In the flawed implementation, Tx2 falsely auto-resolves this discrepancy!
        ReconciliationDiscrepancyEntity entityAfterSync = discrepancyRepository.findById(discrepancyId).orElseThrow();
        System.out.println("=== MVCC RACE OBSERVATION ===");
        System.out.println("Discrepancy status after sync: " + entityAfterSync.getStatus());
        System.out.println("Auto-resolved count: " + syncResult.autoResolved());
        System.out.println("=============================");

        // In a correct system, the discrepancy must REMAIN OPEN because it was never visible in Tx1's snapshot!
        assertThat(entityAfterSync.getStatus())
                .as("Discrepancy committed after Tx1 snapshot must NOT be auto-resolved by Tx2")
                .isEqualTo(DiscrepancyStatus.OPEN);

        // 7. Verify that in a subsequent sweep, where Tx1 establishes a snapshot AFTER Tx A committed:
        // Tx1 now sees the discrepancy in its snapshot, and since clean parity holds, Tx2 safely auto-resolves it.
        ReconciliationReport sweep2Report = reconciliationService.reconcile(orgId);
        assertThat(sweep2Report.snapshotActiveDiscrepancyIds()).contains(discrepancyId);

        DiscrepancySyncResult sweep2Result = discrepancyManagementService.syncDiscrepancies(orgId, sweep2Report);
        assertThat(sweep2Result.autoResolved()).isEqualTo(1);

        ReconciliationDiscrepancyEntity entityAfterSweep2 = discrepancyRepository.findById(discrepancyId).orElseThrow();
        assertThat(entityAfterSweep2.getStatus()).isEqualTo(DiscrepancyStatus.RESOLVED);

        executor.shutdown();
    }

    @Test
    @DisplayName("MVCC upsert protection: older reconciliation sweep does not overwrite discrepancy created after snapshot")
    void olderReconciliationSweepDoesNotOverwriteDiscrepancyCreatedAfterSnapshot() {
        // 1. Initial snapshot time in the past
        Instant snapshotTime = Instant.now().minusSeconds(10);
        ReconciliationDiscrepancy olderDisc = new ReconciliationDiscrepancy(
                productId, warehouseId, 100, 80, 0, 0, "DELTA_MISMATCH"
        );
        ReconciliationReport olderReport = new ReconciliationReport(
                orgId, 1, 0, 1, List.of(olderDisc), snapshotTime,
                Collections.emptyMap(), Collections.emptySet()
        );

        // 2. Concurrently, a newer discrepancy is recorded after snapshotTime with newer observations (120, 95)
        JdbcDiscrepancyOperations.UpsertResult newerUpsert = jdbcDiscrepancyOperations.upsertActiveDiscrepancy(
                orgId, productId, warehouseId, DiscrepancyType.DELTA_MISMATCH,
                120, 95, 0, 0
        );
        assertThat(newerUpsert.isNewInsert()).isTrue();

        // 3. Now Tx2 runs with olderReport (from snapshotTime prior to newerUpsert)
        DiscrepancySyncResult syncResult = discrepancyManagementService.syncDiscrepancies(orgId, olderReport);

        // 4. Verify that the newer discrepancy was NOT overwritten with older observations (100, 80)
        ReconciliationDiscrepancyEntity preserved = discrepancyRepository.findById(newerUpsert.id()).orElseThrow();
        assertThat(preserved.getObservedBalanceOnHand()).isEqualTo(120);
        assertThat(preserved.getObservedLedgerOnHand()).isEqualTo(95);
        assertThat(preserved.getConsecutiveDetectionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("MVCC version protection: concurrent transaction advances discrepancy from version 3 to 4; older Tx1 report cannot overwrite version 4")
    void concurrentTransactionAdvancesVersionStaleReportCannotOverwriteVersion4() throws Exception {
        // Setup: create balance=100, ledger=80 for (orgId, productId, warehouseId) so reconcile() detects mismatch
        jdbcTemplate.update("""
            INSERT INTO inventory_balances (id, organization_id, product_id, warehouse_id, on_hand, reserved, updated_at)
            VALUES (?, ?, ?, ?, 100, 0, CURRENT_TIMESTAMP)
            """, UUID.randomUUID(), orgId, productId, warehouseId);

        UUID txId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO inventory_transactions (id, organization_id, transaction_type, reference_type, reference_id, notes, created_at)
            VALUES (?, ?, 'ADJUSTMENT', NULL, NULL, 'Initial ledger setup', CURRENT_TIMESTAMP)
            """, txId, orgId);
        jdbcTemplate.update("""
            INSERT INTO inventory_ledger_entries (id, organization_id, transaction_id, entry_number, product_id, warehouse_id, on_hand_delta, reserved_delta, created_at)
            VALUES (?, ?, ?, 1, ?, ?, 80, 0, CURRENT_TIMESTAMP)
            """, UUID.randomUUID(), orgId, txId, productId, warehouseId);

        // Advance discrepancy to version 3 in the database (initial insert is version 0; 3 updates advance to version 3)
        JdbcDiscrepancyOperations.UpsertResult u1 = jdbcDiscrepancyOperations.upsertActiveDiscrepancy(
                orgId, productId, warehouseId, DiscrepancyType.DELTA_MISMATCH, 100, 80, 0, 0);
        JdbcDiscrepancyOperations.UpsertResult u2 = jdbcDiscrepancyOperations.upsertActiveDiscrepancy(
                orgId, productId, warehouseId, DiscrepancyType.DELTA_MISMATCH, 100, 80, 0, 0);
        JdbcDiscrepancyOperations.UpsertResult u3 = jdbcDiscrepancyOperations.upsertActiveDiscrepancy(
                orgId, productId, warehouseId, DiscrepancyType.DELTA_MISMATCH, 100, 80, 0, 0);
        JdbcDiscrepancyOperations.UpsertResult u4 = jdbcDiscrepancyOperations.upsertActiveDiscrepancy(
                orgId, productId, warehouseId, DiscrepancyType.DELTA_MISMATCH, 100, 80, 0, 0);

        ReconciliationDiscrepancyEntity v3Entity = discrepancyRepository.findById(u1.id()).orElseThrow();
        assertThat(v3Entity.getVersion()).isEqualTo(3);
        assertThat(v3Entity.getConsecutiveDetectionCount()).isEqualTo(4);

        // 1. Tx1 runs and captures D1 at version 3 in its REPEATABLE READ snapshot
        ReconciliationReport tx1Report = reconciliationService.reconcile(orgId);
        assertThat(tx1Report.discrepancies()).hasSize(1);
        assertThat(tx1Report.discrepancies().get(0).balanceOnHand()).isEqualTo(100);
        assertThat(tx1Report.discrepancies().get(0).ledgerOnHandSum()).isEqualTo(80);

        // 2. Concurrently, a separate transaction detects newer observations (150, 120) and advances D1 to version 4
        CountDownLatch concurrentTxDone = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            txTemplate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            txTemplate.execute(status -> {
                jdbcDiscrepancyOperations.upsertActiveDiscrepancy(
                        orgId, productId, warehouseId, DiscrepancyType.DELTA_MISMATCH,
                        150, 120, 0, 0
                );
                return null;
            });
            concurrentTxDone.countDown();
        });
        assertThat(concurrentTxDone.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Verify D1 is now version 4 with observations (150, 120)
        ReconciliationDiscrepancyEntity v4Entity = discrepancyRepository.findById(u1.id()).orElseThrow();
        assertThat(v4Entity.getVersion()).isEqualTo(4);
        assertThat(v4Entity.getObservedBalanceOnHand()).isEqualTo(150);
        assertThat(v4Entity.getObservedLedgerOnHand()).isEqualTo(120);
        assertThat(v4Entity.getConsecutiveDetectionCount()).isEqualTo(5);

        // 3. Tx2 processes the older Tx1 report (which observed 100, 80 for D1 at version 3)
        DiscrepancySyncResult syncResult = discrepancyManagementService.syncDiscrepancies(orgId, tx1Report);

        // 4. Invariant: Tx2 MUST NOT overwrite version 4 with stale version 3 observations!
        ReconciliationDiscrepancyEntity entityAfterTx2 = discrepancyRepository.findById(u1.id()).orElseThrow();
        System.out.println("=== VERSION OVERWRITE OBSERVATION ===");
        System.out.println("Version after Tx2: " + entityAfterTx2.getVersion());
        System.out.println("Observed balance after Tx2: " + entityAfterTx2.getObservedBalanceOnHand());
        System.out.println("Observed ledger after Tx2: " + entityAfterTx2.getObservedLedgerOnHand());
        System.out.println("Consecutive count after Tx2: " + entityAfterTx2.getConsecutiveDetectionCount());
        System.out.println("=====================================");

        assertThat(entityAfterTx2.getVersion()).as("Version 4 must remain intact").isEqualTo(4);
        assertThat(entityAfterTx2.getObservedBalanceOnHand()).as("Newer balance 150 must remain intact").isEqualTo(150);
        assertThat(entityAfterTx2.getObservedLedgerOnHand()).as("Newer ledger 120 must remain intact").isEqualTo(120);
        assertThat(entityAfterTx2.getConsecutiveDetectionCount()).as("Consecutive count 5 must remain intact").isEqualTo(5);
    }

    @Test
    @DisplayName("Scenario A reproduction: Tx1 captures D1 at v3; operator resolves D1 to terminal RESOLVED; Tx2 processing stale Tx1 report must not create D2")
    void scenarioA_concurrentTerminalTransition_staleReportMustNotCreateD2() throws Exception {
        // Setup: create balance=100, ledger=80 for (orgId, productId, warehouseId) so reconcile() detects mismatch
        jdbcTemplate.update("""
            INSERT INTO inventory_balances (id, organization_id, product_id, warehouse_id, on_hand, reserved, updated_at)
            VALUES (?, ?, ?, ?, 100, 0, CURRENT_TIMESTAMP)
            """, UUID.randomUUID(), orgId, productId, warehouseId);

        UUID txId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO inventory_transactions (id, organization_id, transaction_type, reference_type, reference_id, notes, created_at)
            VALUES (?, ?, 'ADJUSTMENT', NULL, NULL, 'Initial ledger setup', CURRENT_TIMESTAMP)
            """, txId, orgId);
        jdbcTemplate.update("""
            INSERT INTO inventory_ledger_entries (id, organization_id, transaction_id, entry_number, product_id, warehouse_id, on_hand_delta, reserved_delta, created_at)
            VALUES (?, ?, ?, 1, ?, ?, 80, 0, CURRENT_TIMESTAMP)
            """, UUID.randomUUID(), orgId, txId, productId, warehouseId);

        // Discrepancy D1 is created and advances to version 3
        JdbcDiscrepancyOperations.UpsertResult u1 = jdbcDiscrepancyOperations.upsertActiveDiscrepancy(
                orgId, productId, warehouseId, DiscrepancyType.DELTA_MISMATCH, 100, 80, 0, 0);
        jdbcDiscrepancyOperations.upsertActiveDiscrepancy(
                orgId, productId, warehouseId, DiscrepancyType.DELTA_MISMATCH, 100, 80, 0, 0);
        jdbcDiscrepancyOperations.upsertActiveDiscrepancy(
                orgId, productId, warehouseId, DiscrepancyType.DELTA_MISMATCH, 100, 80, 0, 0);
        jdbcDiscrepancyOperations.upsertActiveDiscrepancy(
                orgId, productId, warehouseId, DiscrepancyType.DELTA_MISMATCH, 100, 80, 0, 0);

        UUID d1Id = u1.id();
        ReconciliationDiscrepancyEntity d1Entity = discrepancyRepository.findById(d1Id).orElseThrow();
        assertThat(d1Entity.getVersion()).isEqualTo(3);
        assertThat(d1Entity.getStatus()).isEqualTo(DiscrepancyStatus.OPEN);

        // 1. Tx1 runs and captures D1 at version 3 in its REPEATABLE READ snapshot
        ReconciliationReport tx1Report = reconciliationService.reconcile(orgId);
        assertThat(tx1Report.discrepancies()).hasSize(1);
        assertThat(tx1Report.snapshotActiveDiscrepanciesByPosition()).containsKey(productId + ":" + warehouseId);

        // 2. Concurrently, an operator resolves D1 to terminal RESOLVED
        jdbcTemplate.update("""
            UPDATE reconciliation_discrepancies
            SET status = 'RESOLVED',
                resolution_source = 'HUMAN',
                resolved_by_user_id = ?,
                resolved_at = CURRENT_TIMESTAMP,
                resolution_reason = 'Count verified physically by warehouse supervisor',
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id = ?
            """, managerUserId, d1Id);

        ReconciliationDiscrepancyEntity resolvedD1 = discrepancyRepository.findById(d1Id).orElseThrow();
        assertThat(resolvedD1.getStatus()).isEqualTo(DiscrepancyStatus.RESOLVED);
        assertThat(resolvedD1.getVersion()).isEqualTo(4);

        // 3. Tx2 processes the older Tx1 report
        DiscrepancySyncResult syncResult = discrepancyManagementService.syncDiscrepancies(orgId, tx1Report);

        // Check if a new discrepancy D2 was created:
        List<ReconciliationDiscrepancyEntity> allDiscrepancies = discrepancyRepository.findAll();
        List<ReconciliationDiscrepancyEntity> activeDiscrepancies = allDiscrepancies.stream()
                .filter(d -> d.getProductId().equals(productId) && d.getWarehouseId().equals(warehouseId) && d.getStatus() != DiscrepancyStatus.RESOLVED)
                .toList();

        System.out.println("=== SCENARIO A EXECUTION RESULTS ===");
        System.out.println("SyncResult newDiscrepancies: " + syncResult.newDiscrepancies());
        System.out.println("SyncResult repeatedObservations: " + syncResult.repeatedObservations());
        System.out.println("Total discrepancies in DB for position: " + allDiscrepancies.size());
        System.out.println("Active discrepancies in DB for position: " + activeDiscrepancies.size());
        for (ReconciliationDiscrepancyEntity d : allDiscrepancies) {
            System.out.println(" - Discrepancy id=" + d.getId() + ", status=" + d.getStatus() + ", version=" + d.getVersion() + ", created_at=" + d.getCreatedAt());
        }
        System.out.println("====================================");

        assertThat(activeDiscrepancies).as("A stale report must NOT create a new active discrepancy D2 after human resolution!").isEmpty();
        assertThat(syncResult.newDiscrepancies()).as("newDiscrepancies must be 0").isEqualTo(0);
    }
}
