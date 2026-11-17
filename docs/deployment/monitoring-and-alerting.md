# STOCKGUARD — Prometheus Metrics, SLIs & Alerting Specification

**Document Version:** 1.0.0  
**Status:** Authoritative  
**Reference ADR:** ADR-014 (Observability, Prometheus Metrics, and Security)  

---

## 1. Observability Architecture

STOCKGUARD exposes operational metrics via Spring Boot Actuator and Micrometer on an isolated management port (`8081`). Prometheus scrapes `/actuator/prometheus` over the dedicated internal bridge network `stockguard-monitoring` using HTTP Basic Authentication.

Actuator endpoints are strictly blocked on the public reverse proxy.

---

## 2. Micrometer Metrics Catalog

STOCKGUARD registers 14 domain and infrastructure metrics for continuous telemetry:

### A. Business Domain Metrics
1. `stockguard_inventory_receipts_total` (Counter): Cumulative count of completed inbound inventory receipts.
2. `stockguard_inventory_adjustments_total` (Counter): Cumulative inventory quantity adjustments tagged by reason.
3. `stockguard_transfers_total` (Counter): Inter-warehouse transfer executions tagged by status.
4. `stockguard_orders_total` (Counter): Order lifecycle progressions (`PENDING`, `RESERVED`, `ALLOCATED`, `COMPLETED`, `CANCELLED`, `EXPIRED`).
5. `stockguard_reservations_total` (Counter): Reservation state transitions (`ACTIVE`, `CONSUMED`, `RELEASED`, `EXPIRED`).
6. `stockguard_reservation_expiration_failures_total` (Counter): Failures encountered during automated reservation expiration background sweeps.
7. `stockguard_reconciliation_sweeps_total` (Counter): Automated MVCC reconciliation runs.
8. `stockguard_reconciliation_inspected_balances_total` (Counter): Total balances audited during reconciliation.
9. `stockguard_reconciliation_discrepant_balances_total` (Counter / Gauge): Discrepancies identified between balance tables and ledger entries.

### B. Runtime & Infrastructure Metrics
10. `http_server_requests_seconds` (Timer): Request latency, counts, and status codes across all API endpoints.
11. `hikaricp_connections_active` (Gauge): Currently utilized active PostgreSQL connections.
12. `hikaricp_connections_max` (Gauge): Configured maximum database connection pool size.
13. `jvm_memory_used_bytes` (Gauge): JVM heap and non-heap memory consumption.
14. `process_uptime_seconds` (Gauge): Backend container uptime.

---

## 3. Production Alerting Rules & SLIs

Alerting rules are defined in [prometheus/alerts.yml](../../prometheus/alerts.yml):

| Alert Name | SLI Target | Prometheus Expression | Duration | Severity |
| :--- | :--- | :--- | :--- | :--- |
| `StockGuardServiceDown` | Availability = 100% | `up{job="stockguard"} == 0` | 1m | **Critical** |
| `HighHttp5xxErrorRate` | 5xx Rate < 1% | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(...) * 100 > 1.0` | 3m | **Critical** |
| `HighP95Latency` | P95 Latency < 500ms | `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le)) > 0.500` | 5m | **Warning** |
| `HikariPoolSaturation` | Pool Usage < 80% | `hikaricp_connections_active / hikaricp_connections_max * 100 > 80.0` | 2m | **Warning** |
| `JvmHeapMemoryPressure` | Heap Usage < 85% | `jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} * 100 > 85.0` | 3m | **Warning** |
| `ReconciliationDiscrepancyDetected` | Discrepant Balances = 0 | `stockguard_reconciliation_discrepant_balances_total > 0` | 1m | **Critical** |
| `ReservationExpirationFailed` | Expiration Failures = 0 | `rate(stockguard_reservation_expiration_failures_total[5m]) > 0` | 1m | **Critical** |

---

## 4. Alert Routing Architecture

In production, attach Prometheus Alertmanager to route alerts:
- **Critical Alerts:** Dispatched via Webhook to PagerDuty / Opsgenie for immediate on-call notification.
- **Warning Alerts:** Dispatched to operational Slack / Microsoft Teams channels (`#alerts-stockguard`).
