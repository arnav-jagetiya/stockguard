package com.stockguard.demo.service;

import com.stockguard.audit.service.AuditLogService;
import com.stockguard.demo.config.DemoProperties;
import com.stockguard.demo.dto.DemoSeedResult;
import com.stockguard.inventory.domain.DiscrepancyStatus;
import com.stockguard.inventory.domain.DiscrepancyType;
import com.stockguard.inventory.domain.ReconciliationDiscrepancyEntity;
import com.stockguard.inventory.dto.*;
import com.stockguard.inventory.repository.JdbcInventoryOperations;
import com.stockguard.inventory.repository.ReconciliationDiscrepancyRepository;
import com.stockguard.inventory.service.DiscrepancyManagementService;
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
import com.stockguard.product.dto.CreateProductRequest;
import com.stockguard.product.dto.ProductResponse;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.product.service.ProductService;
import com.stockguard.reservation.dto.ReservationResponse;
import com.stockguard.reservation.service.ReservationExpirationService;
import com.stockguard.reservation.service.ReservationService;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.UserRepository;
import com.stockguard.transfer.dto.CreateTransferRequest;
import com.stockguard.transfer.dto.TransferItemRequest;
import com.stockguard.transfer.service.TransferService;
import com.stockguard.warehouse.domain.Warehouse;
import com.stockguard.warehouse.dto.CreateWarehouseRequest;
import com.stockguard.warehouse.dto.WarehouseResponse;
import com.stockguard.warehouse.repository.WarehouseRepository;
import com.stockguard.warehouse.service.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Robust, idempotent service provisioning the realistic Acme Industrial Supply Pvt. Ltd. demo organization.
 * Uses authoritative domain services to execute and record all inventory, order, reservation, transfer,
 * discrepancy and audit activities.
 */
@Service
@org.springframework.context.annotation.Profile("!test")
public class DemoEnvironmentSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoEnvironmentSeeder.class);

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final WarehouseRepository warehouseRepository;
    private final WarehouseService warehouseService;
    private final ProductRepository productRepository;
    private final ProductService productService;
    private final InventoryService inventoryService;
    private final JdbcInventoryOperations jdbcInventoryOperations;
    private final OrderService orderService;
    private final ReservationService reservationService;
    private final ReservationExpirationService reservationExpirationService;
    private final TransferService transferService;
    private final DiscrepancyManagementService discrepancyManagementService;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final AuditLogService auditLogService;
    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;

    public DemoEnvironmentSeeder(
            OrganizationRepository organizationRepository,
            UserRepository userRepository,
            OrganizationMembershipRepository membershipRepository,
            PasswordEncoder passwordEncoder,
            WarehouseRepository warehouseRepository,
            WarehouseService warehouseService,
            ProductRepository productRepository,
            ProductService productService,
            InventoryService inventoryService,
            JdbcInventoryOperations jdbcInventoryOperations,
            OrderService orderService,
            ReservationService reservationService,
            ReservationExpirationService reservationExpirationService,
            TransferService transferService,
            DiscrepancyManagementService discrepancyManagementService,
            ReconciliationDiscrepancyRepository discrepancyRepository,
            AuditLogService auditLogService,
            JdbcTemplate jdbcTemplate,
            Environment environment
    ) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.warehouseRepository = warehouseRepository;
        this.warehouseService = warehouseService;
        this.productRepository = productRepository;
        this.productService = productService;
        this.inventoryService = inventoryService;
        this.jdbcInventoryOperations = jdbcInventoryOperations;
        this.orderService = orderService;
        this.reservationService = reservationService;
        this.reservationExpirationService = reservationExpirationService;
        this.transferService = transferService;
        this.discrepancyManagementService = discrepancyManagementService;
        this.discrepancyRepository = discrepancyRepository;
        this.auditLogService = auditLogService;
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
    }

    public DemoSeedResult seedDemoEnvironment(DemoProperties properties) {
        if (!properties.isSeedEnabled()) {
            log.debug("Demo environment seeding is disabled by configuration.");
            return DemoSeedResult.skipped("Demo environment seeding is disabled.");
        }

        if (environment != null && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("Demo environment seeding is strictly forbidden in production (prod profile active).");
        }

        String orgName = properties.getOrgName() != null ? properties.getOrgName().trim() : "Acme Industrial Supply Pvt. Ltd.";

        // Idempotency check: if organization exists and has completed catalog seeding, skip to prevent mutations
        Optional<Organization> existingOrgOpt = organizationRepository.findByName(orgName);
        if (existingOrgOpt.isPresent()) {
            Organization existingOrg = existingOrgOpt.get();
            int existingProducts = productRepository.findAllByOrganizationIdOrderByNormalizedSkuAsc(existingOrg.getId()).size();
            if (existingProducts >= 25) {
                log.info("Demo environment for organization '{}' (id: {}) is already fully seeded (found {} products). Skipping.",
                        orgName, existingOrg.getId(), existingProducts);
                return DemoSeedResult.alreadySeeded(existingOrg.getId(), orgName);
            }
        }

        log.info("Initiating demo environment seeding for organization '{}'...", orgName);

        // 1. Organization & Team Provisioning
        Organization org = existingOrgOpt.orElseGet(() -> {
            Organization newOrg = Organization.create(orgName);
            return organizationRepository.saveAndFlush(newOrg);
        });
        UUID orgId = org.getId();

        Map<String, User> userMap = seedTeamMembers(orgId, properties);
        User adminUser = userMap.get("ADMIN");
        UUID adminUserId = adminUser.getId();

        // 2. Warehouses Provisioning (3 facilities)
        Map<String, WarehouseResponse> warehouseMap = seedWarehouses(orgId);

        // 3. Product Catalog Provisioning (30 SKUs)
        Map<String, ProductResponse> productMap = seedProducts(orgId);

        // 4. Inventory Balances, Inbound Receipts, and Adjustments
        int openingBalanceCount = seedInventory(orgId, adminUserId, warehouseMap, productMap);

        // 5. Orders and Reservations Lifecycle (22 orders)
        int orderCount = seedOrdersAndReservations(orgId, adminUserId, warehouseMap, productMap);

        // 6. Inter-Facility Stock Transfers (4 transfers)
        int transferCount = seedTransfers(orgId, adminUserId, warehouseMap, productMap);

        // 7. Demonstration Reconciliation Discrepancies (4 discrepancies)
        int discrepancyCount = seedDiscrepancies(orgId, adminUserId, userMap, warehouseMap, productMap);

        // 8. Record Completion Marker in Immutable Audit Trail
        insertSeedCompletionAuditLog(orgId, adminUserId, productMap.size(), warehouseMap.size(), orderCount);

        log.info("Demo environment for '{}' successfully seeded: 3 warehouses, {} products, {} orders, {} transfers, {} discrepancies.",
                orgName, productMap.size(), orderCount, transferCount, discrepancyCount);

        return new DemoSeedResult(
                orgId,
                orgName,
                adminUserId,
                adminUser.getEmail(),
                userMap.size(),
                warehouseMap.size(),
                productMap.size(),
                openingBalanceCount,
                orderCount,
                orderCount,
                transferCount,
                discrepancyCount,
                false,
                "Demo environment successfully initialized."
        );
    }

    private Map<String, User> seedTeamMembers(UUID orgId, DemoProperties properties) {
        Map<String, User> users = new HashMap<>();

        // Admin User
        String adminEmail = properties.getAdminEmail() != null ? properties.getAdminEmail().trim() : "acme-admin@stockguard.dev";
        String adminPassword = properties.getAdminPassword() != null ? properties.getAdminPassword() : "RedactedAdminSecret2026!";
        User admin = userRepository.findByNormalizedEmail(User.normalizeEmail(adminEmail)).orElseGet(() -> {
            User newUser = User.create(adminEmail, passwordEncoder.encode(adminPassword));
            return userRepository.saveAndFlush(newUser);
        });

        if (membershipRepository.findByOrganizationIdAndUserId(orgId, admin.getId()).isEmpty()) {
            OrganizationMembership membership = OrganizationMembership.create(orgId, admin.getId(), Role.ADMIN);
            membershipRepository.saveAndFlush(membership);
            auditLogService.logMemberAdded(orgId, admin.getId(), admin.getId(), Role.ADMIN.name(), false);
        }
        users.put("ADMIN", admin);

        // 7 Synthetic Team Members
        record SyntheticUserSpec(String key, String name, String email, Role role) {}
        List<SyntheticUserSpec> teamSpecs = List.of(
                new SyntheticUserSpec("MANAGER_1", "Priya Sharma", "priya.sharma@acme-industrial.example.in", Role.MANAGER),
                new SyntheticUserSpec("MANAGER_2", "Rajesh Patel", "rajesh.patel@acme-industrial.example.in", Role.MANAGER),
                new SyntheticUserSpec("OPERATOR_1", "Anil Kumar", "anil.kumar@acme-industrial.example.in", Role.OPERATOR),
                new SyntheticUserSpec("OPERATOR_2", "Sunita Rao", "sunita.rao@acme-industrial.example.in", Role.OPERATOR),
                new SyntheticUserSpec("OPERATOR_3", "Vikram Singh", "vikram.singh@acme-industrial.example.in", Role.OPERATOR),
                new SyntheticUserSpec("VIEWER_1", "Deepa Nair", "deepa.nair@acme-industrial.example.in", Role.VIEWER),
                new SyntheticUserSpec("VIEWER_2", "Manoj Verma", "manoj.verma@acme-industrial.example.in", Role.VIEWER)
        );

        for (SyntheticUserSpec spec : teamSpecs) {
            String normEmail = User.normalizeEmail(spec.email());
            User user = userRepository.findByNormalizedEmail(normEmail).orElseGet(() -> {
                String securePass = passwordEncoder.encode("RedactedDemoSecret2026#" + spec.key());
                User newUser = User.create(spec.email(), securePass);
                return userRepository.saveAndFlush(newUser);
            });

            if (membershipRepository.findByOrganizationIdAndUserId(orgId, user.getId()).isEmpty()) {
                OrganizationMembership membership = OrganizationMembership.create(orgId, user.getId(), spec.role());
                membershipRepository.saveAndFlush(membership);
                auditLogService.logMemberAdded(orgId, admin.getId(), user.getId(), spec.role().name(), false);
            }
            users.put(spec.key(), user);
        }

        return users;
    }

    private Map<String, WarehouseResponse> seedWarehouses(UUID orgId) {
        Map<String, WarehouseResponse> warehouses = new HashMap<>();

        record WhSpec(String code, String name, String location, int priority) {}
        List<WhSpec> specs = List.of(
                new WhSpec("WH-BLR-01", "Main Distribution Center", "Peenya Industrial Area, Bengaluru, Karnataka", 1),
                new WhSpec("WH-PUN-02", "Regional Fulfillment Hub", "Chakan MIDC, Pune, Maharashtra", 2),
                new WhSpec("WH-NCR-03", "Satellite Storage Facility", "IMT Manesar, Gurugram, Haryana", 3)
        );

        for (WhSpec spec : specs) {
            String normCode = Warehouse.normalizeCode(spec.code());
            WarehouseResponse res = warehouseRepository.findByOrganizationIdAndNormalizedCode(orgId, normCode)
                    .map(WarehouseResponse::fromDomain)
                    .orElseGet(() -> warehouseService.createWarehouse(orgId, new CreateWarehouseRequest(
                            spec.code(),
                            spec.name(),
                            spec.location(),
                            spec.priority()
                    )));
            warehouses.put(spec.code(), res);
        }

        return warehouses;
    }

    private Map<String, ProductResponse> seedProducts(UUID orgId) {
        Map<String, ProductResponse> products = new HashMap<>();

        record ProdSpec(String sku, String name, String description) {}
        List<ProdSpec> specs = List.of(
                // Fasteners and hardware
                new ProdSpec("SKU-FAST-001", "Grade 8.8 Hex Bolts M12x50", "[Category: Fasteners and hardware] Heavy-duty grade 8.8 zinc-plated carbon steel hexagonal head bolts M12x50mm. Pack of 100."),
                new ProdSpec("SKU-FAST-002", "Stainless Steel Flat Washers M12", "[Category: Fasteners and hardware] Corrosion-resistant AISI 304 stainless steel flat plain washers for M12 fasteners. Box of 500."),
                new ProdSpec("SKU-FAST-003", "High-Tensile Threaded Rod M16 1m", "[Category: Fasteners and hardware] Grade 8.8 high-tensile zinc-plated fully threaded rod 1 meter length M16 thread."),
                new ProdSpec("SKU-FAST-004", "Self-Tapping Sheet Metal Screws 25mm", "[Category: Fasteners and hardware] Phillips pan head hardened steel self-tapping screws 4.2x25mm for metal cladding. Box of 1000."),
                new ProdSpec("SKU-FAST-005", "Heavy Duty Wedge Anchor Bolts M20x150", "[Category: Fasteners and hardware] Through-bolt wedge expansion anchors for structural concrete installation M20x150mm. Pack of 20."),

                // Industrial equipment
                new ProdSpec("SKU-EQUIP-001", "3-Phase Induction Motor 5HP 1440RPM", "[Category: Industrial equipment] Totally enclosed fan-cooled (TEFC) foot-mounted 3-phase squirrel cage induction motor 415V 50Hz."),
                new ProdSpec("SKU-EQUIP-002", "Rotary Vane Vacuum Pump 250L/min", "[Category: Industrial equipment] Dual-stage rotary vane vacuum pump with integrated oil mist filter and anti-suckback valve."),
                new ProdSpec("SKU-EQUIP-003", "Hydraulic Hand Pallet Truck 2500kg", "[Category: Industrial equipment] Standard 1150x550mm tandem nylon roller manual hydraulic pallet jack with overload bypass valve."),
                new ProdSpec("SKU-EQUIP-004", "Centrifugal Industrial Water Pump 3HP", "[Category: Industrial equipment] Cast iron end-suction monoblock centrifugal industrial utility water pump 40x32mm."),
                new ProdSpec("SKU-EQUIP-005", "Heavy Duty Bench Grinder 200mm", "[Category: Industrial equipment] 750W dual-wheel workshop bench grinder with adjustable eye shields and cast iron base."),

                // Electrical components
                new ProdSpec("SKU-ELEC-001", "4-Pole Miniature Circuit Breaker 63A 10kA", "[Category: Electrical components] C-curve 415V AC 4P DIN rail mount miniature circuit breaker for industrial power distribution."),
                new ProdSpec("SKU-ELEC-002", "Copper Armoured Cable 4-Core 16 sq mm (100m)", "[Category: Electrical components] 1.1kV grade XLPE insulated steel wire armoured (SWA) heavy power cable drum."),
                new ProdSpec("SKU-ELEC-003", "Industrial Magnetic Contactor 32A 230V AC", "[Category: Electrical components] 3-pole AC-3 rated power contactor with 1NO+1NC auxiliary contacts for motor starters."),
                new ProdSpec("SKU-ELEC-004", "Digital Multimeter True-RMS 600V CAT III", "[Category: Electrical components] Rugged handheld digital electrical tester with backlit display, frequency and capacitance testing."),
                new ProdSpec("SKU-ELEC-005", "Industrial LED High-Bay Light 150W 6500K", "[Category: Electrical components] IP65 waterproof aluminum die-cast UFO high-bay warehouse luminaire 21000 lumens."),

                // Safety equipment
                new ProdSpec("SKU-SAFE-001", "Industrial Safety Helmet with Ratchet Suspension", "[Category: Safety equipment] High-density polyethylene (HDPE) shell with 6-point textile suspension and wheel ratchet adjustment."),
                new ProdSpec("SKU-SAFE-002", "Chemical Resistant Nitrile Gloves (Pack of 12 Pairs)", "[Category: Safety equipment] 15-mil flocked unlined green nitrile gauntlet gloves for solvent, acid, and oil handling."),
                new ProdSpec("SKU-SAFE-003", "Steel Toe Cap Safety Shoes Size 9", "[Category: Safety equipment] S3 rated water-resistant leather upper with 200J steel toe impact protection and anti-penetration sole."),
                new ProdSpec("SKU-SAFE-004", "Polycarbonate Anti-Scratch Safety Goggles", "[Category: Safety equipment] Panoramic wide-vision indirect-vent splash and impact protective industrial eyewear."),
                new ProdSpec("SKU-SAFE-005", "ABC Dry Powder Fire Extinguisher 6kg", "[Category: Safety equipment] Pressure gauge fitted multipurpose monoammonium phosphate fire extinguisher with wall bracket."),

                // Packaging materials
                new ProdSpec("SKU-PACK-001", "Heavy Duty Corrugated Shipping Boxes 450x300x300mm", "[Category: Packaging materials] Double wall 5-ply kraft paper corrugated outer shipping cartons. Bundle of 25."),
                new ProdSpec("SKU-PACK-002", "Industrial Stretch Wrap Film 500mm x 300m 23 Micron", "[Category: Packaging materials] High-clarity cast pallet wrap stretch film roll for freight securing and moisture protection."),
                new ProdSpec("SKU-PACK-003", "Polypropylene Strapping Band 12mm x 2000m Roll", "[Category: Packaging materials] High tensile embossed strapping band for manual and semi-automatic carton bundling machines."),
                new ProdSpec("SKU-PACK-004", "Heavy Duty Brown Packaging Tape 48mm x 65m (Pack of 6)", "[Category: Packaging materials] Strong adhesive BOPP industrial carton sealing pressure-sensitive tape."),
                new ProdSpec("SKU-PACK-005", "Wooden Heavy Duty Pallets 1200x1000mm Heat Treated", "[Category: Packaging materials] ISPM-15 compliant four-way entry pine wood storage pallet with 1500kg dynamic load capacity."),

                // Maintenance supplies
                new ProdSpec("SKU-MAINT-001", "Multi-Purpose EP2 Lithium Grease 5kg Bucket", "[Category: Maintenance supplies] Extreme pressure mineral oil lithium grease for high-load industrial bearing lubrication."),
                new ProdSpec("SKU-MAINT-002", "Industrial Penetrating Lubricant Spray 400ml (Pack of 4)", "[Category: Maintenance supplies] Rapid penetrant for rust loosening, moisture displacement, and anti-corrosion protection."),
                new ProdSpec("SKU-MAINT-003", "Anti-Seize Assembly Paste 500g", "[Category: Maintenance supplies] Copper-based high-temperature threaded connection lubricant operating up to 1100 deg C."),
                new ProdSpec("SKU-MAINT-004", "Heavy Duty Degreaser & Cleaner Concentrate 5L", "[Category: Maintenance supplies] Biodegradable water-based alkaline industrial cleaner for machinery, workshop floors, and tooling."),
                new ProdSpec("SKU-MAINT-005", "Microfiber Industrial Cleaning Rags (10kg Bale)", "[Category: Maintenance supplies] Lint-free absorbent commercial maintenance wipes for machinery overhaul and workshop upkeep.")
        );

        for (ProdSpec spec : specs) {
            String normSku = Product.normalizeSku(spec.sku());
            ProductResponse res = productRepository.findByOrganizationIdAndNormalizedSku(orgId, normSku)
                    .map(ProductResponse::fromDomain)
                    .orElseGet(() -> productService.createProduct(orgId, new CreateProductRequest(
                            spec.sku(),
                            spec.name(),
                            spec.description()
                    )));
            products.put(spec.sku(), res);
        }

        return products;
    }

    private int seedInventory(
            UUID orgId,
            UUID adminUserId,
            Map<String, WarehouseResponse> warehouseMap,
            Map<String, ProductResponse> productMap
    ) {
        WarehouseResponse wh1 = warehouseMap.get("WH-BLR-01");
        WarehouseResponse wh2 = warehouseMap.get("WH-PUN-02");
        WarehouseResponse wh3 = warehouseMap.get("WH-NCR-03");

        int openingCount = 0;

        for (Map.Entry<String, ProductResponse> entry : productMap.entrySet()) {
            String sku = entry.getKey();
            UUID prodId = entry.getValue().id();

            // Quantities tailored across warehouse tiers (WH1: primary, WH2: secondary, WH3: satellite)
            long q1;
            long q2;
            long q3;

            if (sku.startsWith("SKU-FAST-") || sku.startsWith("SKU-PACK-")) {
                q1 = 400L;
                q2 = 200L;
                q3 = 80L;
            } else if (sku.startsWith("SKU-SAFE-") || sku.startsWith("SKU-MAINT-")) {
                q1 = 150L;
                q2 = 75L;
                q3 = 30L;
            } else if (sku.startsWith("SKU-ELEC-")) {
                q1 = 70L;
                q2 = 35L;
                // Leave cable out-of-stock in satellite warehouse for realistic variety
                q3 = sku.equals("SKU-ELEC-002") ? 0L : 15L;
            } else {
                // Heavy industrial equipment
                q1 = 25L;
                q2 = 10L;
                // Zero stock in satellite facility for heavy pumps & motors
                q3 = (sku.equals("SKU-EQUIP-001") || sku.equals("SKU-EQUIP-002")) ? 0L : 4L;
            }

            if (q1 > 0 && !jdbcInventoryOperations.hasOpeningBalanceBeenRecorded(orgId, prodId, wh1.id())) {
                inventoryService.recordOpeningBalance(orgId, adminUserId,
                        new OpeningBalanceRequest(prodId, wh1.id(), q1, "Opening balance initial inventory"),
                        "INIT-" + sku + "-WH1");
                openingCount++;
            }
            if (q2 > 0 && !jdbcInventoryOperations.hasOpeningBalanceBeenRecorded(orgId, prodId, wh2.id())) {
                inventoryService.recordOpeningBalance(orgId, adminUserId,
                        new OpeningBalanceRequest(prodId, wh2.id(), q2, "Opening balance initial inventory"),
                        "INIT-" + sku + "-WH2");
                openingCount++;
            }
            if (q3 > 0 && !jdbcInventoryOperations.hasOpeningBalanceBeenRecorded(orgId, prodId, wh3.id())) {
                inventoryService.recordOpeningBalance(orgId, adminUserId,
                        new OpeningBalanceRequest(prodId, wh3.id(), q3, "Opening balance initial inventory"),
                        "INIT-" + sku + "-WH3");
                openingCount++;
            }
        }

        // Additional supplier receipts on WH1 and WH2
        ProductResponse fast1 = productMap.get("SKU-FAST-001");
        inventoryService.receiveStock(orgId, adminUserId,
                new ReceiveStockRequest(fast1.id(), wh1.id(), 50L, null, "Vendor batch delivery: PO-IN-8891"),
                "RCV-FAST1-WH1");

        ProductResponse safe3 = productMap.get("SKU-SAFE-003");
        inventoryService.receiveStock(orgId, adminUserId,
                new ReceiveStockRequest(safe3.id(), wh2.id(), 20L, null, "Safety boot restock shipment: PO-IN-8892"),
                "RCV-SAFE3-WH2");

        // Small cycle count adjustments
        ProductResponse pack1 = productMap.get("SKU-PACK-001");
        inventoryService.adjustStock(orgId, adminUserId,
                new AdjustStockRequest(pack1.id(), wh1.id(), -5L, "Damaged in transit packaging write-down", null),
                "ADJ-PACK1-WH1");

        return openingCount;
    }

    private int seedOrdersAndReservations(
            UUID orgId,
            UUID adminUserId,
            Map<String, WarehouseResponse> warehouseMap,
            Map<String, ProductResponse> productMap
    ) {
        // Price reference map
        Map<String, BigDecimal> prices = Map.ofEntries(
                Map.entry("SKU-FAST-001", new BigDecimal("450.0000")),
                Map.entry("SKU-FAST-002", new BigDecimal("320.0000")),
                Map.entry("SKU-FAST-003", new BigDecimal("275.5000")),
                Map.entry("SKU-FAST-004", new BigDecimal("580.0000")),
                Map.entry("SKU-FAST-005", new BigDecimal("1250.0000")),
                Map.entry("SKU-EQUIP-001", new BigDecimal("18500.0000")),
                Map.entry("SKU-EQUIP-002", new BigDecimal("24200.0000")),
                Map.entry("SKU-EQUIP-003", new BigDecimal("16800.0000")),
                Map.entry("SKU-EQUIP-004", new BigDecimal("12400.0000")),
                Map.entry("SKU-EQUIP-005", new BigDecimal("6750.0000")),
                Map.entry("SKU-ELEC-001", new BigDecimal("1850.0000")),
                Map.entry("SKU-ELEC-002", new BigDecimal("32000.0000")),
                Map.entry("SKU-ELEC-003", new BigDecimal("2150.0000")),
                Map.entry("SKU-ELEC-004", new BigDecimal("4200.0000")),
                Map.entry("SKU-ELEC-005", new BigDecimal("3400.0000")),
                Map.entry("SKU-SAFE-001", new BigDecimal("380.0000")),
                Map.entry("SKU-SAFE-002", new BigDecimal("720.0000")),
                Map.entry("SKU-SAFE-003", new BigDecimal("1650.0000")),
                Map.entry("SKU-SAFE-004", new BigDecimal("240.0000")),
                Map.entry("SKU-SAFE-005", new BigDecimal("1950.0000")),
                Map.entry("SKU-PACK-001", new BigDecimal("1100.0000")),
                Map.entry("SKU-PACK-002", new BigDecimal("850.0000")),
                Map.entry("SKU-PACK-003", new BigDecimal("1450.0000")),
                Map.entry("SKU-PACK-004", new BigDecimal("390.0000")),
                Map.entry("SKU-PACK-005", new BigDecimal("950.0000")),
                Map.entry("SKU-MAINT-001", new BigDecimal("1850.0000")),
                Map.entry("SKU-MAINT-002", new BigDecimal("680.0000")),
                Map.entry("SKU-MAINT-003", new BigDecimal("890.0000")),
                Map.entry("SKU-MAINT-004", new BigDecimal("1250.0000")),
                Map.entry("SKU-MAINT-005", new BigDecimal("1400.0000"))
        );

        record OrderSpec(
                String ref,
                String extRef,
                List<String> skus,
                List<Long> quantities,
                String targetState // PENDING_UNRESERVED, PENDING_RESERVED, CONFIRMED, FULFILLED, CANCELLED_RESERVED, CANCELLED_UNRESERVED, EXPIRED
        ) {}

        List<OrderSpec> specs = List.of(
                // 1. PENDING (Unreserved)
                new OrderSpec("Larsen & Toubro Site A", "PO-LT-901", List.of("SKU-FAST-001"), List.of(20L), "PENDING_UNRESERVED"),
                new OrderSpec("Godrej Construction", "PO-GD-902", List.of("SKU-ELEC-001", "SKU-ELEC-003"), List.of(5L, 4L), "PENDING_UNRESERVED"),

                // 2. PENDING (With Active Reservation)
                new OrderSpec("Tata Projects Hub", "PO-TP-903", List.of("SKU-SAFE-001", "SKU-SAFE-002"), List.of(15L, 10L), "PENDING_RESERVED"),
                new OrderSpec("Adani Logistics Terminal", "PO-AD-904", List.of("SKU-PACK-002"), List.of(25L), "PENDING_RESERVED"),

                // 3. CONFIRMED (With Active Non-Expiring Reservation)
                new OrderSpec("Mahindra Logistics Pune", "PO-MH-905", List.of("SKU-FAST-002"), List.of(30L), "CONFIRMED"),
                new OrderSpec("BHEL Bhopal Unit 4", "PO-BH-906", List.of("SKU-EQUIP-003"), List.of(2L), "CONFIRMED"),
                new OrderSpec("Schneider Electric India", "PO-SE-907", List.of("SKU-ELEC-004"), List.of(4L), "CONFIRMED"),
                new OrderSpec("Jindal Steel & Power", "PO-JS-908", List.of("SKU-SAFE-003"), List.of(8L), "CONFIRMED"),
                new OrderSpec("Reliance Retail Depot", "PO-RR-909", List.of("SKU-MAINT-001", "SKU-MAINT-002"), List.of(5L, 10L), "CONFIRMED"),

                // 4. FULFILLED (With CONSUMED Reservation & Fulfillment Ledger Movements)
                new OrderSpec("Bosch Chassis Systems", "PO-BS-910", List.of("SKU-FAST-001"), List.of(50L), "FULFILLED"),
                new OrderSpec("Hero MotoCorp Haridwar", "PO-HM-911", List.of("SKU-FAST-004"), List.of(40L), "FULFILLED"),
                new OrderSpec("Siemens Energy Aurangabad", "PO-SM-912", List.of("SKU-EQUIP-001"), List.of(3L), "FULFILLED"),
                new OrderSpec("Kirloskar Brothers Kirloskarvadi", "PO-KB-913", List.of("SKU-ELEC-002"), List.of(2L), "FULFILLED"),
                new OrderSpec("Wipro Infrastructure Engineering", "PO-WP-914", List.of("SKU-SAFE-005"), List.of(6L), "FULFILLED"),
                new OrderSpec("ITC Packaging Divison Chennai", "PO-IT-915", List.of("SKU-PACK-001"), List.of(30L), "FULFILLED"),
                new OrderSpec("Maruti Suzuki Manesar Plant", "PO-MS-916", List.of("SKU-MAINT-004"), List.of(8L), "FULFILLED"),

                // 5. CANCELLED (Reserved then Released, or Unreserved)
                new OrderSpec("Ashok Leyland Hosur", "PO-AL-917", List.of("SKU-FAST-005"), List.of(10L), "CANCELLED_RESERVED"),
                new OrderSpec("Voltas Electro-Mechanical", "PO-VT-918", List.of("SKU-EQUIP-004"), List.of(2L), "CANCELLED_RESERVED"),
                new OrderSpec("Blue Star Commercial Aircon", "PO-BS-919", List.of("SKU-PACK-003"), List.of(5L), "CANCELLED_UNRESERVED"),
                new OrderSpec("ABB India Nelamangala", "PO-AB-920", List.of("SKU-MAINT-003"), List.of(6L), "CANCELLED_UNRESERVED"),

                // 6. EXPIRED (With Expired Reservations & Stock Released)
                new OrderSpec("Thermax Energy Systems", "PO-TX-921", List.of("SKU-PACK-004"), List.of(15L), "EXPIRED"),
                new OrderSpec("Cummins India Kothrud", "PO-CM-922", List.of("SKU-SAFE-004"), List.of(20L), "EXPIRED")
        );

        int orderIdx = 0;
        for (OrderSpec spec : specs) {
            orderIdx++;
            List<OrderItemRequest> itemRequests = new ArrayList<>();
            for (int i = 0; i < spec.skus().size(); i++) {
                String sku = spec.skus().get(i);
                Long qty = spec.quantities().get(i);
                UUID prodId = productMap.get(sku).id();
                BigDecimal price = prices.get(sku);
                itemRequests.add(new OrderItemRequest(prodId, qty, price));
            }

            String orderIdemKey = "DEMO-ORD-" + orderIdx;
            OrderResponse order = orderService.createOrder(
                    orgId,
                    adminUserId,
                    new CreateOrderRequest(spec.ref(), spec.extRef(), "INR", itemRequests),
                    orderIdemKey
            );

            UUID orderId = order.id();

            switch (spec.targetState()) {
                case "PENDING_UNRESERVED" -> {
                    // Leaves order in PENDING without reservation
                }
                case "PENDING_RESERVED" -> {
                    reservationService.createReservation(orgId, adminUserId, orderId, "DEMO-RES-" + orderIdx);
                }
                case "CONFIRMED" -> {
                    reservationService.createReservation(orgId, adminUserId, orderId, "DEMO-RES-" + orderIdx);
                    orderService.confirmOrder(orgId, adminUserId, orderId, "DEMO-CONF-" + orderIdx);
                }
                case "FULFILLED" -> {
                    reservationService.createReservation(orgId, adminUserId, orderId, "DEMO-RES-" + orderIdx);
                    orderService.confirmOrder(orgId, adminUserId, orderId, "DEMO-CONF-" + orderIdx);
                    orderService.fulfillOrder(orgId, adminUserId, orderId, "DEMO-FULF-" + orderIdx);
                }
                case "CANCELLED_RESERVED" -> {
                    reservationService.createReservation(orgId, adminUserId, orderId, "DEMO-RES-" + orderIdx);
                    orderService.cancelOrder(orgId, adminUserId, orderId, "DEMO-CANC-" + orderIdx);
                }
                case "CANCELLED_UNRESERVED" -> {
                    orderService.cancelOrder(orgId, adminUserId, orderId, "DEMO-CANC-" + orderIdx);
                }
                case "EXPIRED" -> {
                    ReservationResponse res = reservationService.createReservation(orgId, adminUserId, orderId, "DEMO-RES-" + orderIdx);
                    // Artificially move expiration back 2 minutes so candidate finder identifies it
                    jdbcTemplate.update(
                            "UPDATE reservations SET expires_at = ? WHERE id = ?",
                            Timestamp.from(Instant.now().minusSeconds(120)),
                            res.id()
                    );
                    reservationExpirationService.expireSpecificCandidate(orgId, orderId);
                }
            }
        }

        return specs.size();
    }

    private int seedTransfers(
            UUID orgId,
            UUID adminUserId,
            Map<String, WarehouseResponse> warehouseMap,
            Map<String, ProductResponse> productMap
    ) {
        WarehouseResponse wh1 = warehouseMap.get("WH-BLR-01");
        WarehouseResponse wh2 = warehouseMap.get("WH-PUN-02");
        WarehouseResponse wh3 = warehouseMap.get("WH-NCR-03");

        ProductResponse fast1 = productMap.get("SKU-FAST-001");
        ProductResponse equip1 = productMap.get("SKU-EQUIP-001");
        ProductResponse pack2 = productMap.get("SKU-PACK-002");
        ProductResponse safe1 = productMap.get("SKU-SAFE-001");
        ProductResponse elec1 = productMap.get("SKU-ELEC-001");
        ProductResponse maint1 = productMap.get("SKU-MAINT-001");

        // Transfer 1: WH1 -> WH2
        transferService.transferStock(orgId, adminUserId, new CreateTransferRequest(
                wh1.id(),
                wh2.id(),
                List.of(
                        new TransferItemRequest(fast1.id(), 40L),
                        new TransferItemRequest(equip1.id(), 5L)
                ),
                "Regular inter-facility stock replenishment: Bengaluru to Pune"
        ), "DEMO-XFER-1");

        // Transfer 2: WH1 -> WH3
        transferService.transferStock(orgId, adminUserId, new CreateTransferRequest(
                wh1.id(),
                wh3.id(),
                List.of(
                        new TransferItemRequest(pack2.id(), 30L),
                        new TransferItemRequest(safe1.id(), 20L)
                ),
                "Packaging & safety equipment staging for NCR satellite depot"
        ), "DEMO-XFER-2");

        // Transfer 3: WH2 -> WH3
        transferService.transferStock(orgId, adminUserId, new CreateTransferRequest(
                wh2.id(),
                wh3.id(),
                List.of(
                        new TransferItemRequest(elec1.id(), 15L)
                ),
                "Electrical components transfer: Chakan to Manesar"
        ), "DEMO-XFER-3");

        // Transfer 4: WH3 -> WH1
        transferService.transferStock(orgId, adminUserId, new CreateTransferRequest(
                wh3.id(),
                wh1.id(),
                List.of(
                        new TransferItemRequest(maint1.id(), 8L)
                ),
                "Surplus maintenance grease consolidation back to Bengaluru"
        ), "DEMO-XFER-4");

        return 4;
    }

    private int seedDiscrepancies(
            UUID orgId,
            UUID adminUserId,
            Map<String, User> userMap,
            Map<String, WarehouseResponse> warehouseMap,
            Map<String, ProductResponse> productMap
    ) {
        WarehouseResponse wh1 = warehouseMap.get("WH-BLR-01");
        WarehouseResponse wh2 = warehouseMap.get("WH-PUN-02");
        WarehouseResponse wh3 = warehouseMap.get("WH-NCR-03");

        ProductResponse prodFast3 = productMap.get("SKU-FAST-003");
        ProductResponse prodPack1 = productMap.get("SKU-PACK-001");
        ProductResponse prodSafe2 = productMap.get("SKU-SAFE-002");
        ProductResponse prodElec3 = productMap.get("SKU-ELEC-003");

        User manager1 = userMap.get("MANAGER_1"); // Priya Sharma
        User manager2 = userMap.get("MANAGER_2"); // Rajesh Patel

        // 1. OPEN Discrepancy on WH-BLR-01 / SKU-FAST-003
        ReconciliationDiscrepancyEntity d1 = new ReconciliationDiscrepancyEntity(
                UUID.randomUUID(),
                orgId,
                prodFast3.id(),
                wh1.id(),
                DiscrepancyType.DELTA_MISMATCH,
                282L,
                280L,
                0L,
                0L
        );
        discrepancyRepository.saveAndFlush(d1);

        // 2. ACKNOWLEDGED Discrepancy on WH-PUN-02 / SKU-PACK-001
        ReconciliationDiscrepancyEntity d2 = new ReconciliationDiscrepancyEntity(
                UUID.randomUUID(),
                orgId,
                prodPack1.id(),
                wh2.id(),
                DiscrepancyType.DELTA_MISMATCH,
                148L,
                150L,
                0L,
                0L
        );
        d2 = discrepancyRepository.saveAndFlush(d2);
        discrepancyManagementService.updateDiscrepancy(
                orgId,
                manager1.getId(),
                d2.getId(),
                new UpdateDiscrepancyRequest(DiscrepancyStatus.ACKNOWLEDGED, null,
                        "Cycle count discrepancy acknowledged by Pune logistics supervisor.", null, d2.getVersion())
        );

        // 3. INVESTIGATING Discrepancy on WH-NCR-03 / SKU-SAFE-002
        ReconciliationDiscrepancyEntity d3 = new ReconciliationDiscrepancyEntity(
                UUID.randomUUID(),
                orgId,
                prodSafe2.id(),
                wh3.id(),
                DiscrepancyType.DELTA_MISMATCH,
                38L,
                40L,
                0L,
                0L
        );
        d3 = discrepancyRepository.saveAndFlush(d3);
        d3 = discrepancyRepository.findById(d3.getId()).orElseThrow();
        discrepancyManagementService.updateDiscrepancy(
                orgId,
                manager1.getId(),
                d3.getId(),
                new UpdateDiscrepancyRequest(DiscrepancyStatus.ACKNOWLEDGED, null,
                        "Variance detected during shift change inspection.", null, d3.getVersion())
        );
        d3 = discrepancyRepository.findById(d3.getId()).orElseThrow();
        discrepancyManagementService.updateDiscrepancy(
                orgId,
                manager1.getId(),
                d3.getId(),
                new UpdateDiscrepancyRequest(DiscrepancyStatus.INVESTIGATING, manager2.getId(),
                        "Physical recount assigned to Rajesh Patel on Bay C-12.", null, d3.getVersion())
        );

        // 4. RESOLVED Discrepancy on WH-BLR-01 / SKU-ELEC-003
        ReconciliationDiscrepancyEntity d4 = new ReconciliationDiscrepancyEntity(
                UUID.randomUUID(),
                orgId,
                prodElec3.id(),
                wh1.id(),
                DiscrepancyType.DELTA_MISMATCH,
                65L,
                65L,
                0L,
                0L
        );
        d4 = discrepancyRepository.saveAndFlush(d4);
        d4 = discrepancyRepository.findById(d4.getId()).orElseThrow();
        discrepancyManagementService.updateDiscrepancy(
                orgId,
                adminUserId,
                d4.getId(),
                new UpdateDiscrepancyRequest(DiscrepancyStatus.RESOLVED, null,
                        "Investigation concluded: physical stock and double-entry ledger matched.",
                        "Resolved: Verified physical tally matches ledger; supplier return invoice adjusted.",
                        d4.getVersion())
        );

        return 4;
    }

    private void insertSeedCompletionAuditLog(
            UUID orgId,
            UUID actorUserId,
            int products,
            int warehouses,
            int orders
    ) {
        String sql = "INSERT INTO audit_logs " +
                "(id, organization_id, actor_user_id, action, entity_type, entity_id, metadata, created_at) " +
                "VALUES (?, ?, ?, 'DEMO_SEED_COMPLETED', 'SYSTEM', ?, ?::jsonb, ?)";
        String metadataJson = String.format(
                "{\"event\": \"DEMO_ENVIRONMENT_INITIALIZED\", \"products\": %d, \"warehouses\": %d, \"orders\": %d, \"organization\": \"Acme Industrial Supply Pvt. Ltd.\"}",
                products, warehouses, orders
        );
        jdbcTemplate.update(
                sql,
                UUID.randomUUID(),
                orgId,
                actorUserId,
                orgId,
                metadataJson,
                Timestamp.from(Instant.now())
        );
    }
}
