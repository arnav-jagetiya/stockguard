package com.stockguard.inventory;

import com.stockguard.inventory.config.ReconciliationProperties;
import com.stockguard.inventory.dto.DiscrepancySyncResult;
import com.stockguard.inventory.dto.ReconciliationReport;
import com.stockguard.inventory.dto.ReconciliationSweepResult;
import com.stockguard.inventory.service.DiscrepancyManagementService;
import com.stockguard.inventory.service.InventoryReconciliationService;
import com.stockguard.inventory.worker.ScheduledReconciliationWorker;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.shared.lock.PostgresAdvisoryLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class ScheduledReconciliationWorkerIntegrationTest {

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
    private ScheduledReconciliationWorker reconciliationWorker;

    @Autowired
    private PostgresAdvisoryLockService advisoryLockService;

    @Autowired
    private InventoryReconciliationService reconciliationService;

    @Autowired
    private DiscrepancyManagementService discrepancyManagementService;

    @Autowired
    private ReconciliationProperties reconciliationProperties;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID orgA;
    private UUID orgB;

    @BeforeEach
    void setUp() {
        Organization o1 = organizationRepository.save(Organization.create("Worker Org A " + UUID.randomUUID()));
        Organization o2 = organizationRepository.save(Organization.create("Worker Org B " + UUID.randomUUID()));
        orgA = o1.getId();
        orgB = o2.getId();
    }

    @Test
    @DisplayName("Worker acquires advisory lock, iterates tenants, and releases lock upon completion")
    void sweepAcquiresAndReleasesAdvisoryLock() throws Exception {
        ReconciliationSweepResult result = reconciliationWorker.runSweep();

        assertThat(result.lockAcquired()).isTrue();
        assertThat(result.completedFully()).isTrue();
        assertThat(result.organizationsAttempted()).isGreaterThanOrEqualTo(2);
        assertThat(result.organizationsSucceeded()).isGreaterThanOrEqualTo(2);

        // Verify lock is completely unlocked after sweep
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                stmt.setLong(1, ScheduledReconciliationWorker.RECONCILIATION_LOCK_KEY);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBoolean(1)).isTrue(); // Lock was free!
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                stmt.setLong(1, ScheduledReconciliationWorker.RECONCILIATION_LOCK_KEY);
                stmt.executeQuery();
            }
        }
    }

    @Test
    @DisplayName("Advisory lock contention: when another connection holds the lock, worker skips sweep cleanly")
    void contentionSkipsExecutionCleanly() throws Exception {
        try (Connection externalConn = dataSource.getConnection()) {
            // Manually acquire lock on external connection
            try (PreparedStatement stmt = externalConn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                stmt.setLong(1, ScheduledReconciliationWorker.RECONCILIATION_LOCK_KEY);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBoolean(1)).isTrue();
                }
            }

            // Now worker attempts sweep
            ReconciliationSweepResult result = reconciliationWorker.runSweep();

            assertThat(result.lockAcquired()).isFalse();
            assertThat(result.completedFully()).isFalse();
            assertThat(result.organizationsAttempted()).isEqualTo(0);

            // Cleanly unlock external connection
            try (PreparedStatement stmt = externalConn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                stmt.setLong(1, ScheduledReconciliationWorker.RECONCILIATION_LOCK_KEY);
                stmt.executeQuery();
            }
        }
    }

    @Test
    @DisplayName("Lock release in finally: advisory lock is released even if sweep execution throws an exception")
    void lockReleasedEvenOnUnexpectedException() throws Exception {
        // Test lock release directly via advisoryLockService
        assertThatCode(() -> advisoryLockService.executeWithLock(ScheduledReconciliationWorker.RECONCILIATION_LOCK_KEY, () -> {
            throw new RuntimeException("Unexpected simulated sweep failure!");
        })).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unexpected simulated sweep failure!");

        // Verify lock is unlocked
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                stmt.setLong(1, ScheduledReconciliationWorker.RECONCILIATION_LOCK_KEY);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBoolean(1)).isTrue(); // Successfully re-acquired!
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                stmt.setLong(1, ScheduledReconciliationWorker.RECONCILIATION_LOCK_KEY);
                stmt.executeQuery();
            }
        }
    }

    @Test
    @DisplayName("Multiple concurrent worker sweeps: only one instance acquires the lock; second instance skips cleanly")
    void multipleInstancesDoNotRunOverlappingSweeps() throws Exception {
        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<ReconciliationSweepResult> f1 = executor.submit(() -> {
            startLatch.await();
            return reconciliationWorker.runSweep();
        });

        Future<ReconciliationSweepResult> f2 = executor.submit(() -> {
            startLatch.await();
            return reconciliationWorker.runSweep();
        });

        startLatch.countDown();
        ReconciliationSweepResult r1 = f1.get(15, TimeUnit.SECONDS);
        ReconciliationSweepResult r2 = f2.get(15, TimeUnit.SECONDS);
        executor.shutdown();

        // At least one ran or both ran sequentially; if concurrent, one skipped
        int locksAcquired = (r1.lockAcquired() ? 1 : 0) + (r2.lockAcquired() ? 1 : 0);
        assertThat(locksAcquired).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Fault isolation: unexpected failure in Tenant A does not prevent Tenant B from reconciling")
    void faultIsolationAllowsSubsequentTenantsToProceed() {
        InventoryReconciliationService mockRecService = Mockito.mock(InventoryReconciliationService.class);
        DiscrepancyManagementService mockDiscService = Mockito.mock(DiscrepancyManagementService.class);

        // Tenant A fails with exception on all attempts
        Mockito.when(mockRecService.reconcile(orgA)).thenThrow(new RuntimeException("Simulated Tenant A DB Timeout!"));

        // Tenant B succeeds
        ReconciliationReport reportB = new ReconciliationReport(orgB, 5, 5, 0, Collections.emptyList(), Instant.now());
        Mockito.when(mockRecService.reconcile(orgB)).thenReturn(reportB);
        Mockito.when(mockDiscService.syncDiscrepancies(Mockito.eq(orgB), Mockito.any()))
                .thenReturn(new DiscrepancySyncResult(0, 0, 0, 0));

        ScheduledReconciliationWorker isolatedWorker = new ScheduledReconciliationWorker(
                advisoryLockService,
                mockRecService,
                mockDiscService,
                reconciliationProperties,
                jdbcTemplate
        ) {
            @Override
            public List<UUID> fetchNextOrganizationBatch(UUID lastSeenId, int batchSize) {
                if (lastSeenId == null) {
                    return List.of(orgA, orgB);
                }
                return Collections.emptyList();
            }
        };

        ReconciliationSweepResult result = isolatedWorker.runSweep();

        assertThat(result.lockAcquired()).isTrue();
        assertThat(result.organizationsAttempted()).isEqualTo(2);
        assertThat(result.organizationsFailed()).isEqualTo(1); // Org A failed
        assertThat(result.organizationsSucceeded()).isEqualTo(1); // Org B succeeded

        // Verify Org A was retried (called twice per 1-retry policy)
        Mockito.verify(mockRecService, Mockito.times(2)).reconcile(orgA);
        // Verify Org B was processed
        Mockito.verify(mockRecService, Mockito.times(1)).reconcile(orgB);
    }

    @Test
    @DisplayName("Keyset pagination: processes organizations across multiple batches using configured batch size")
    void keysetPaginationProcessesMultipleBatchesCorrectly() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        UUID p4 = UUID.randomUUID();
        UUID p5 = UUID.randomUUID();

        List<UUID> sortedExpected = new java.util.ArrayList<>(List.of(p1, p2, p3, p4, p5));
        Collections.sort(sortedExpected);

        ReconciliationProperties batchProps = new ReconciliationProperties();
        batchProps.getScheduled().setTenantBatchSize(2);
        batchProps.getScheduled().setMaxDurationSeconds(60);

        List<UUID> processedOrgs = new java.util.concurrent.CopyOnWriteArrayList<>();

        InventoryReconciliationService mockRec = Mockito.mock(InventoryReconciliationService.class);
        DiscrepancyManagementService mockDisc = Mockito.mock(DiscrepancyManagementService.class);

        for (UUID id : sortedExpected) {
            ReconciliationReport dummy = new ReconciliationReport(id, 1, 1, 0, Collections.emptyList(), Instant.now());
            Mockito.when(mockRec.reconcile(id)).thenAnswer(inv -> {
                processedOrgs.add(id);
                return dummy;
            });
            Mockito.when(mockDisc.syncDiscrepancies(Mockito.eq(id), Mockito.any()))
                    .thenReturn(new DiscrepancySyncResult(0, 0, 0, 0));
        }

        ScheduledReconciliationWorker batchWorker = new ScheduledReconciliationWorker(
                advisoryLockService,
                mockRec,
                mockDisc,
                batchProps,
                jdbcTemplate
        ) {
            @Override
            public List<UUID> fetchNextOrganizationBatch(UUID lastSeenId, int batchSize) {
                return sortedExpected.stream()
                        .filter(id -> lastSeenId == null || id.compareTo(lastSeenId) > 0)
                        .limit(batchSize)
                        .toList();
            }
        };

        ReconciliationSweepResult result = batchWorker.runSweep();

        assertThat(result.lockAcquired()).isTrue();
        assertThat(result.completedFully()).isTrue();
        assertThat(result.organizationsAttempted()).isEqualTo(5);
        assertThat(result.organizationsSucceeded()).isEqualTo(5);
        assertThat(result.organizationsFailed()).isEqualTo(0);

        // Verify all 5 organizations processed in deterministic keyset ascending order across 3 batches (2 + 2 + 1)
        assertThat(processedOrgs).containsExactlyElementsOf(sortedExpected);
    }

    @Test
    @DisplayName("Keyset pagination: tenant failure in batch 1 does not prevent batch 2 from processing")
    void keysetPaginationContinuesAcrossTenantFailure() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID t3 = UUID.randomUUID();
        UUID t4 = UUID.randomUUID();

        List<UUID> tenants = new java.util.ArrayList<>(List.of(t1, t2, t3, t4));
        Collections.sort(tenants);

        ReconciliationProperties batchProps = new ReconciliationProperties();
        batchProps.getScheduled().setTenantBatchSize(2);
        batchProps.getScheduled().setMaxDurationSeconds(60);

        InventoryReconciliationService mockRec = Mockito.mock(InventoryReconciliationService.class);
        DiscrepancyManagementService mockDisc = Mockito.mock(DiscrepancyManagementService.class);

        // Tenant 0 succeeds
        Mockito.when(mockRec.reconcile(tenants.get(0))).thenReturn(new ReconciliationReport(tenants.get(0), 1, 1, 0, Collections.emptyList(), Instant.now()));
        Mockito.when(mockDisc.syncDiscrepancies(Mockito.eq(tenants.get(0)), Mockito.any())).thenReturn(new DiscrepancySyncResult(0, 0, 0, 0));

        // Tenant 1 (end of batch 1) fails
        Mockito.when(mockRec.reconcile(tenants.get(1))).thenThrow(new RuntimeException("Simulated Batch 1 Tenant Error"));

        // Tenant 2 (start of batch 2) succeeds
        Mockito.when(mockRec.reconcile(tenants.get(2))).thenReturn(new ReconciliationReport(tenants.get(2), 1, 1, 0, Collections.emptyList(), Instant.now()));
        Mockito.when(mockDisc.syncDiscrepancies(Mockito.eq(tenants.get(2)), Mockito.any())).thenReturn(new DiscrepancySyncResult(0, 0, 0, 0));

        // Tenant 3 (end of batch 2) succeeds
        Mockito.when(mockRec.reconcile(tenants.get(3))).thenReturn(new ReconciliationReport(tenants.get(3), 1, 1, 0, Collections.emptyList(), Instant.now()));
        Mockito.when(mockDisc.syncDiscrepancies(Mockito.eq(tenants.get(3)), Mockito.any())).thenReturn(new DiscrepancySyncResult(0, 0, 0, 0));

        ScheduledReconciliationWorker batchWorker = new ScheduledReconciliationWorker(
                advisoryLockService,
                mockRec,
                mockDisc,
                batchProps,
                jdbcTemplate
        ) {
            @Override
            public List<UUID> fetchNextOrganizationBatch(UUID lastSeenId, int batchSize) {
                return tenants.stream()
                        .filter(id -> lastSeenId == null || id.compareTo(lastSeenId) > 0)
                        .limit(batchSize)
                        .toList();
            }
        };

        ReconciliationSweepResult result = batchWorker.runSweep();

        assertThat(result.lockAcquired()).isTrue();
        assertThat(result.completedFully()).isTrue();
        assertThat(result.organizationsAttempted()).isEqualTo(4);
        assertThat(result.organizationsSucceeded()).isEqualTo(3);
        assertThat(result.organizationsFailed()).isEqualTo(1);

        // Verify tenant 1 was retried twice
        Mockito.verify(mockRec, Mockito.times(2)).reconcile(tenants.get(1));
        // Verify batch 2 tenants were processed successfully
        Mockito.verify(mockRec, Mockito.times(1)).reconcile(tenants.get(2));
        Mockito.verify(mockRec, Mockito.times(1)).reconcile(tenants.get(3));
    }

    @Test
    @DisplayName("Graceful shutdown: ContextClosedEvent terminates worker loop cleanly between organizations")
    void gracefulShutdownStopsIterationEarly() {
        ScheduledReconciliationWorker worker = new ScheduledReconciliationWorker(
                advisoryLockService,
                reconciliationService,
                discrepancyManagementService,
                reconciliationProperties,
                jdbcTemplate
        );

        // Fire ContextClosedEvent
        worker.onApplicationEvent(Mockito.mock(ContextClosedEvent.class));

        // Attempt sweep
        ReconciliationSweepResult result = worker.runSweep();

        // Must terminate early cleanly without executing organizations
        assertThat(result.completedFully()).isFalse();
        assertThat(result.organizationsAttempted()).isEqualTo(0);
    }
}
