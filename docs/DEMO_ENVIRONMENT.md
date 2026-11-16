# STOCKGUARD — Demonstration Environment & Synthetic Business Data Architecture
## Milestone 9A: Acme Industrial Supply Pvt. Ltd.

**Version:** 1.0.0  
**Baseline:** Milestone 9A (Realistic Demo Environment, Synthetic Business Data & Operational Readiness)  
**Status:** Accepted & Verified  

---

## 1. Executive Summary

To enable high-fidelity demonstrations, investor reviews, and portfolio presentations without exposing confidential production datasets, STOCKGUARD includes a realistic, fully interconnected synthetic business environment: **Acme Industrial Supply Pvt. Ltd.**.

Unlike mock or superficial UI-only fixtures, Acme Industrial Supply is provisioned entirely through STOCKGUARD's authoritative domain services into PostgreSQL. Every product, warehouse, inventory balance, order, reservation, stock transfer, reconciliation discrepancy, and audit event strictly honors the core architectural invariants of the platform:
- Dual-delta inventory model: $\text{Available} = \text{On-Hand} - \text{Reserved}$.
- Immutable double-entry ledger movements accompanying all physical inventory shifts.
- $100\%$ mathematical reconciliation between physical inventory balances and ledger delta sums.
- Atomic inter-facility stock transfers without intermediate floating states.
- Multi-tenant tenant isolation and RBAC role boundaries.

---

## 2. Activation & Security Controls

Demo environment seeding is strictly opt-in and safeguarded against accidental activation in production:

| Configuration Property | Default | Production (`prod` Profile) | Description |
|---|---|---|---|
| `stockguard.demo.seed-enabled` | `false` | **FORBIDDEN (Throws `IllegalStateException`)** | Opt-in flag to trigger demo environment seeding on startup. |
| `stockguard.demo.admin-email` | `acme-admin@stockguard.dev` | Disallowed | Initial demo administrator email address. |
| `stockguard.demo.admin-password` | `RedactedAdminSecret2026!` | Disallowed | Initial demo administrator password (BCrypt hashed). |
| `stockguard.demo.admin-full-name` | `Aarav Mehta` | Disallowed | Demo administrator display name. |
| `stockguard.demo.org-name` | `Acme Industrial Supply Pvt. Ltd.` | Disallowed | Target demonstration organization name. |

### Enabling Demo Seeding Locally

Set the environment variable or command-line parameter when starting the backend:

```powershell
$env:STOCKGUARD_DEMO_SEED_ENABLED="true"
cd backend
.\mvnw.cmd spring-boot:run -DDB_PORT=5433
```

Or pass via Spring Boot property:
```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--stockguard.demo.seed-enabled=true"
```

### Safety & Concurrency Protections
1. **Production Rejection:** `DemoSeedRunner` inspects the active Spring environment. If the `prod` profile is active, an `IllegalStateException` is thrown immediately, halting initialization.
2. **PostgreSQL Advisory Lock:** Seeding is protected across distributed or containerized replicas via a dedicated-connection session advisory lock:
   $$\text{Lock Key} = \text{0x41434D4553454544L} \quad (\text{'ACMESEED'})$$
   Managed by `PostgresAdvisoryLockService` per ADR-012. If another instance is currently seeding, subsequent nodes log cleanly and skip execution without deadlock or race conditions.
3. **Idempotency & Non-Destructive Operation:** Re-running the application with `seed-enabled=true` checks for the completion marker `DEMO_SEED_COMPLETED` in the audit log. Existing organizations, products, and users (including `StockGuard Demo Organization`) are completely preserved without `DROP` or `TRUNCATE`.

---

## 3. Synthetic Business Entity Topology

### A. Organization & Currency
- **Name:** Acme Industrial Supply Pvt. Ltd.
- **Operating Currency:** `INR` (Indian Rupee, ₹)
- **Business Profile:** Multi-facility industrial distributor supplying fasteners, heavy machinery, electrical components, safety equipment, packaging, and maintenance supplies.

### B. Team & RBAC Structure (8 Members)
All users are created with BCrypt-hashed passwords and assigned explicit tenant memberships:

| Full Name | Email Address | Role | Operational Responsibilities |
|---|---|---|---|
| **Aarav Mehta** | `acme-admin@stockguard.dev` | `ADMIN` | Full tenant management, audit oversight, policy enforcement. |
| **Priya Sharma** | `priya.sharma@acme-industrial.dev` | `MANAGER` | Bengaluru DC manager, reconciliation, inventory adjustments. |
| **Rajesh Patel** | `rajesh.patel@acme-industrial.dev` | `MANAGER` | Pune hub manager, cycle count investigations, discrepancy resolution. |
| **Vikram Singh** | `vikram.singh@acme-industrial.dev` | `OPERATOR` | Bengaluru logistics supervisor, stock receipts and pick/pack. |
| **Ananya Iyer** | `ananya.iyer@acme-industrial.dev` | `OPERATOR` | Pune fulfillment lead, outbound shipments and allocations. |
| **Rohan Verma** | `rohan.verma@acme-industrial.dev` | `OPERATOR` | NCR warehouse lead, inter-facility transfers and receiving. |
| **Neha Gupta** | `neha.gupta@acme-industrial.dev` | `VIEWER` | Internal compliance auditor, read-only analytics. |
| **Amit Kumar** | `amit.kumar@acme-industrial.dev` | `VIEWER` | Inventory and financial analyst, read-only reporting. |

---

### C. Facilities (3 Warehouses)
1. **`WH-BLR-01` — Bengaluru Main Distribution Center**
   - Location: Electronic City, Bengaluru, Karnataka
   - Capacity: 50,000 sq ft (High-volume central distribution hub)
2. **`WH-PUN-02` — Pune Regional Fulfillment Hub**
   - Location: Chakan Industrial Area, Pune, Maharashtra
   - Capacity: 28,000 sq ft (Automotive and manufacturing fulfillment)
3. **`WH-NCR-03` — NCR Satellite Storage Facility**
   - Location: IMT Manesar, Gurugram, Haryana
   - Capacity: 14,000 sq ft (Rapid satellite dispatch center)

---

### D. Product Catalog (30 SKUs across 6 Categories)

1. **Fasteners & Industrial Hardware**
   - `FAST-HEX-M10` — Hex Bolt M10 x 50mm High-Tensile (Grade 8.8)
   - `FAST-HEX-M12` — Hex Bolt M12 x 70mm High-Tensile (Grade 10.9)
   - `FAST-SS-M08` — Stainless Steel Socket Head Cap Screw M8
   - `FAST-NYL-M10` — Nylon Insert Lock Nut M10 Zinc-Plated
   - `FAST-WSH-FL10` — Heavy-Duty Flat Washer M10 Spring Steel
2. **Heavy Machinery & Equipment**
   - `EQP-HYD-LIFT` — Hydraulic Pallet Truck 2.5 Ton Heavy-Duty
   - `EQP-AIR-COMP` — Industrial Rotary Screw Air Compressor 15HP
   - `EQP-DRL-MAG` — Magnetic Base Core Drill Machine 50mm
   - `EQP-WLD-MIG` — Industrial MIG/MAG Inverter Welding Station 300A
   - `EQP-HOIST-2T` — Electric Chain Hoist 2-Ton Dual Speed
3. **Electrical & Power Distribution**
   - `ELEC-CBL-4CX16` — 4-Core Armoured Copper Power Cable 16 sq mm
   - `ELEC-MCCB-100A` — Molded Case Circuit Breaker (MCCB) 100A 36kA
   - `ELEC-VFD-11KW` — Variable Frequency Drive (VFD) 11kW 3-Phase
   - `ELEC-LGT-100W` — High-Bay LED Industrial Luminaire 100W IP65
   - `ELEC-SEN-PROX` — Inductive Proximity Sensor M18 PNP Normally Open
4. **Industrial Safety & PPE**
   - `SAFE-HELM-YEL` — High-Density Polyethylene Industrial Safety Helmet
   - `SAFE-BOOT-S3` — Steel-Toe Leather Safety Footwear S3 SRC
   - `SAFE-GLV-NIT` — Cut-Resistant Nitrile Coated Heavy Grip Gloves
   - `SAFE-RES-FPP3` — Reusable Half-Face Industrial Respirator FFP3
   - `SAFE-EYE-UV` — Anti-Fog UV Protection Safety Goggles EN166
5. **Packaging & Material Handling**
   - `PKG-STR-ROLL` — Machine-Grade LLDPE Stretch Wrap Film 23 Micron
   - `PKG-PLT-WOOD` — Heat-Treated Heavy Euro Pallet (1200x800mm)
   - `PKG-BOX-5PLY` — Heavy-Duty Corrugated Packaging Carton 5-Ply
   - `PKG-BBL-ROLL` — Anti-Static Heavy Bubble Cushioning Wrap 100m
   - `PKG-STR-PET` — High-Tension Polyester (PET) Strapping Roll 16mm
6. **Facility Maintenance & Consumables**
   - `MNT-GREASE-EP2` — Heavy-Duty Extreme Pressure Grease EP-2 (18kg)
   - `MNT-OIL-HYD68` — Premium Anti-Wear Hydraulic Fluid ISO VG 68
   - `MNT-CLNR-IND` — Biodegradable Heavy-Duty Industrial Degreaser 20L
   - `MNT-SEAL-RTV` — High-Temperature Silicone RTV Gasket Sealant
   - `MNT-RST-CVTR` — Rapid Industrial Rust Converter & Primer Aerosol

---

### E. Inventory & Dual-Delta Balance Integrity

Opening stock was established across facilities using domain services. For all 30 SKUs across all 3 facilities:
- $\text{Balance On-Hand} = \text{Available} + \text{Reserved}$.
- Every unit in `inventory_balances` corresponds to exact balancing records in `inventory_ledger`.
- Zero orphaned or unaccounted units.

---

### F. Orders & Reservation Lifecycle (22 Orders)

The 22 synthetic orders represent customer activity across all possible lifecycle states:
- **Pending Orders (5):** Active reservations held; orders awaiting credit or picker assignment.
- **Confirmed Orders (6):** Active reservations held; orders verified and released for dispatch.
- **Fulfilled Orders (6):** Reservations consumed, stock decremented, goods shipped to clients.
- **Cancelled Orders (3):** Reservations released back to available balance; zero stock loss.
- **Expired Orders (2):** Reservation TTL elapsed; reservation status `EXPIRED`, released cleanly by background sweep.

---

### G. Inter-Facility Transfers (4 Atomic Movements)

Transfers were executed via `TransferService` between facilities to simulate logistics balancing:
1. `WH-BLR-01` $\rightarrow$ `WH-PUN-02`: High-tensile fasteners (50 units) and safety helmets (20 units).
2. `WH-BLR-01` $\rightarrow$ `WH-NCR-03`: Euro pallets (30 units) and industrial degreaser (15 units).
3. `WH-PUN-02` $\rightarrow$ `WH-BLR-01`: Armoured copper cable (10 units) and high-bay lighting (8 units).
4. `WH-NCR-03` $\rightarrow$ `WH-PUN-02`: Anti-static bubble roll (12 units) and stretch wrap film (25 units).

All transfers executed atomically: source warehouse decremented `(-Q, 0)`, destination warehouse incremented `(+Q, 0)` with double-entry ledger lines. Total system stock is strictly conserved.

---

### H. Reconciliation Discrepancy Scenarios (4 Scenarios)

Demonstrates the real-time reconciliation engine and human-in-the-loop audit workflows:
1. **`OPEN`:** M10 hex bolts in `WH-BLR-01`. Balance On-Hand is 282, Ledger is 280 (+2 variance detected during morning cycle count).
2. **`ACKNOWLEDGED`:** 4-Core Armoured Cable in `WH-PUN-02`. Balance On-Hand is 148, Ledger is 150 (-2 variance acknowledged by Pune logistics supervisor).
3. **`INVESTIGATING`:** Heavy-Duty Hydraulic Pallet Trucks in `WH-NCR-03`. Assigned to Manager **Rajesh Patel** with notes: *"Physical recount assigned to Rajesh Patel on Bay C-12."*
4. **`RESOLVED`:** High-Bay LED Luminaires in `WH-BLR-01`. Physical recount verified and resolved with reason: *"Verified physical tally matches ledger; supplier return invoice adjusted."*

---

### I. Audit Trail & Completion Marker
All administrative, inventory, order, transfer, and discrepancy actions are recorded immutably in `audit_logs`. The process concludes with:
- **Action:** `DEMO_SEED_COMPLETED`
- **Entity Type:** `SYSTEM`
- **Metadata:**
  ```json
  {
    "organizationName": "Acme Industrial Supply Pvt. Ltd.",
    "warehouses": 3,
    "products": 30,
    "orders": 22,
    "transfers": 4,
    "discrepancies": 4,
    "version": "1.0.0"
  }
  ```

---

## 4. Verification Evidence & Quality Acceptance

| Verification Area | Total Verified | Failures / Errors | Status |
|---|---|---|---|
| **Backend Regression Test Suite** | 476 tests | 0 Failures, 0 Errors, 0 Skipped | **PASSED** (10m 58s) |
| **Demo Seeder Integration Tests** | 5 targeted tests | 0 Failures, 0 Errors | **PASSED** |
| **Frontend Unit Tests (`vitest`)** | 17 tests | 0 Failures | **PASSED** (4.78s) |
| **Frontend Build (`tsc` + `vite`)** | Clean compile | 0 TypeScript / Bundle Errors | **PASSED** (2.82s) |
| **Frontend Linter (`oxlint`)** | 79 files | 0 Errors | **PASSED** |
| **End-to-End Browser Acceptance** | 15 scenarios | 0 Errors | **PASSED** |

### Evidence Screenshots
Saved in `frontend/verification_evidence/demo_environment/`:
1. `01_login_demo_credentials.png` — Automatic demo credential retrieval and form prefill.
2. `02_dashboard_overview_dark.png` — Operations dashboard in Midnight Mineral dark theme.
3. `03_dashboard_overview_light.png` — Operations dashboard in Warm Mineral light theme.
4. `04_products_catalog.png` — Products catalog displaying 30 industrial SKUs with categories.
5. `05_warehouses_facilities.png` — 3 operational warehouse facilities in Bengaluru, Pune, and NCR.
6. `06_inventory_balances.png` — Inventory balance table showing on-hand, available, and reserved quantities.
7. `07_orders_lifecycle.png` — Orders table displaying 22 orders across all lifecycle statuses.
8. `08_reservations_active.png` — Active and consumed stock reservations.
9. `09_transfers_history.png` — Inter-facility stock transfers conserving system stock.
10. `10_reconciliation_discrepancies.png` — Reconciliation discrepancies across OPEN, ACKNOWLEDGED, INVESTIGATING, and RESOLVED.
11. `11_audit_logs_trail.png` — Immutable audit log trail culminating in `DEMO_SEED_COMPLETED`.
12. `12_team_members.png` — Team management showing all 8 synthetic employees and RBAC roles.
13. `13_orders_filtered_view.png` — Interactive order filter and modal interaction.
14. `14_responsive_tablet_768.png` — Tablet viewport (768x1024) responsive layout.
15. `15_responsive_mobile_390.png` — Mobile viewport (390x844) responsive layout.
