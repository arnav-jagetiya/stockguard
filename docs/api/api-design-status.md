# API Design Status

> **Status: PROPOSED** — API design is in the planning phase. No endpoints have been implemented. Endpoint paths, request/response schemas, and error formats are subject to change.

## Accepted Architectural Decisions

| Decision | Summary |
|---|---|
| Idempotency keys required on all mutating endpoints | All `POST`, `PUT`, `PATCH`, and `DELETE` requests must include an `Idempotency-Key` header with a 7-day retention period. Scoped by `(organization_id, principal_id, operation_scope, idempotency_key)`. |
| Payload hash validation & concurrency | Incoming requests with an existing key are verified against `request_hash`. Same payload returns cached response; differing payload returns HTTP `409 Conflict`. Concurrent identical requests wait for the in-flight transaction to complete and then replay the cached response (not an automatic 409). If the in-flight transaction rolls back, retrying requests safely proceed. |
| Organization-scoped endpoints | All endpoints operate strictly within the authenticated user's organization boundary. Initial launch operates with a single organization. No cross-tenant access is permitted. |

## Proposed Design

### API Style
- **Protocol:** HTTP/HTTPS
- **Format:** RESTful JSON request/response
- **Authentication:** Strategy TBD (stateless JWT bearer tokens vs. session cookies — see Unresolved Questions)

---

## Planned API Groups

### 1. Authentication & Tenant Access
| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/auth/register` | Register a new user and organization |
| `POST` | `/api/auth/login` | Authenticate and receive session/token |
| `POST` | `/api/auth/logout` | Invalidate current session/token |
| `GET` | `/api/organizations/current/memberships` | List organizations accessible to current user |
| `POST` | `/api/organizations/current/api-clients` | Provision machine-to-machine API credentials (deferred to post-MVP unless external machine API integration is a launch requirement) |

### 2. Products
| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/products` | Create a new product (includes normalized `sku` unique to org, `name`, etc.) |
| `GET` | `/api/products` | List products (paginated, filterable by `sku`, `is_active`) |
| `GET` | `/api/products/{id}` | Get product details |
| `PUT` | `/api/products/{id}` | Update product information |
| `DELETE` | `/api/products/{id}` | Deactivate product (soft-delete) |

### 3. Warehouses
| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/warehouses` | Create warehouse with priority rank and code |
| `GET` | `/api/warehouses` | List warehouses ordered by priority |
| `GET` | `/api/warehouses/{id}` | Get warehouse details |
| `PUT` | `/api/warehouses/{id}` | Update warehouse name, location, or priority |
| `DELETE` | `/api/warehouses/{id}` | Deactivate warehouse |

### 4. Inventory Operations & Ledger
| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/inventory/receive` | Record stock receipt (or `OPENING_BALANCE` transaction if initial stock) |
| `POST` | `/api/inventory/adjust` | Manual stock adjustment with mandatory reason |
| `GET` | `/api/inventory/balances` | Query balances (`on_hand`, `reserved`, `available = on_hand - reserved`) |
| `GET` | `/api/inventory/ledger` | Query `inventory_ledger_entries` with transaction context |
| `POST` | `/api/inventory/reconcile` | Trigger mathematical reconciliation ($\sum \text{deltas} \stackrel{?}{=} \text{balance}$) |

### 5. Orders & Fulfillment
| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/orders` | Create an order with line items (`PENDING`) |
| `GET` | `/api/orders` | List orders (paginated, filterable by status) |
| `GET` | `/api/orders/{id}` | Get order details and item lines (with `sku_snapshot`, `name_snapshot`, `unit_price`) |
| `PUT` | `/api/orders/{id}` | Update draft order details |
| `POST` | `/api/orders/{id}/confirm` | Confirm order (`PENDING → CONFIRMED`; protects associated active reservation from auto-expiration) |
| `POST` | `/api/orders/{id}/fulfill` | Fulfill order (`CONFIRMED → FULFILLING → FULFILLED`; consumes reservation `ACTIVE → CONSUMED`, decreases `on_hand` and `reserved`) |
| `POST` | `/api/orders/{id}/cancel` | Cancel order (`→ CANCELLED`; releases associated active reservation `ACTIVE → RELEASED`) |

### 6. Reservations
| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/reservations` | Create reservation for order (status: `ACTIVE`; associated with `PENDING` order, subject to expiration; applies canonical lock order `product_id ASC, warehouse_id ASC`) |
| `GET` | `/api/reservations/{id}` | Get reservation status (`ACTIVE`, `CONSUMED`, `RELEASED`, `EXPIRED`) and allocations |
| `POST` | `/api/reservations/{id}/release` | Manually release reservation (`ACTIVE → RELEASED`; decreases `reserved` balance, returns stock to available) |

### 7. Warehouse Transfers
| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/transfers` | Initiate and complete stock transfer across warehouses |
| `GET` | `/api/transfers` | List historical warehouse transfers |
| `GET` | `/api/transfers/{id}` | Get transfer details including `transfer_items` |

### 8. Dashboard & Configuration
| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/dashboard/summary` | Real-time overview of inventory, alerts, and pending orders |
| `GET` | `/api/reports/inventory` | Inventory movements and valuation reports |
| `GET` | `/api/settings` | Query operational settings (deferred to post-MVP; MVP uses application configuration) |
| `PUT` | `/api/settings/{key}` | Update organization-specific setting (deferred to post-MVP) |

### 9. Audit Logs & Security Compliance (Milestone 6)
| Method | Endpoint | Required Permission | Allowed Roles | Description |
|---|---|---|---|---|
| `GET` | `/api/audit-logs` | `audit:view` | `ADMIN`, `MANAGER` | Retrieve paginated, deterministic tenant audit logs with filtering by action, entityType, entityId, actorUserId, date range. Sorted newest-first (`ORDER BY created_at DESC, id DESC`). |

---

## API Conventions

### Error Response Format (Proposed)
```json
{
  "error": {
    "code": "INSUFFICIENT_STOCK",
    "message": "Not enough available stock for product WIDGET-001 at Warehouse A.",
    "details": {
      "product_id": "8f8b89d5-783a-4467-bc22-a169b14ea988",
      "sku": "WIDGET-001",
      "warehouse_id": "4b33c035-7ffc-4613-81ce-0348f95c1847",
      "requested_quantity": 50,
      "available_quantity": 20
    }
  }
}
```

### HTTP Status Codes
| Code | Usage |
|---|---|
| `200 OK` | Successful read or update |
| `201 Created` | Successful resource creation |
| `400 Bad Request` | Malformed request body or invalid query parameters |
| `401 Unauthorized` | Missing or invalid authentication token/session |
| `403 Forbidden` | Authenticated but lacking permission for operation |
| `404 Not Found` | Resource does not exist within the organization |
| `409 Conflict` | State conflict (e.g., idempotency payload mismatch, terminal reservation mutation) |
| `422 Unprocessable Entity` | Business invariant violation (e.g., partial reservation failure, downward stock adjustment driving `on_hand < reserved`) |
| `429 Too Many Requests` | Rate limit exceeded |
| `500 Internal Server Error` | Unexpected server failure |

---

## Unresolved API Decisions

> [!WARNING]
> The following API decisions remain genuinely **unresolved** and must not be pre-empted without explicit direction:

| # | Question | Context | Status |
|---|---|---|---|
| 1 | **JWT versus session authentication** | Stateless JWT bearer tokens vs. session cookies. Token refresh strategy. | **Unresolved** |
| 2 | **Role-to-permission mapping** | Role-based access control (RBAC) mapping per endpoint (`ADMIN`, `MANAGER`, `OPERATOR`, `VIEWER`). | **Unresolved** |
| 3 | **Reservation TTL configuration and default duration** | Default TTL duration for pending order's active reservation before background worker expiration. | **Unresolved** |
| 4 | **Deadlock retry and backoff policy** | Whether API gateway / controller layer transparently retries deadlocks before responding. | **Unresolved** |
| 5 | **Inter-module communication strategy** | Webhook emission or domain event publication upon order fulfillment or stock depletion vs. synchronous calls. | **Unresolved** |
| 6 | **Handling an HTTP timeout while an idempotency transaction is executing** | Client retry handling and recovery when an HTTP client times out while server transaction is in-flight. | **Unresolved** |

### Future Implementation Considerations

The following operational API design details will be finalized in later milestones:
- **Pagination Standard:** Cursor-based vs. offset/page-based pagination across list endpoints.
- **API Versioning Strategy:** URL path prefixing (`/api/v1/...`) vs. custom header (`Accept: application/vnd.stockguard.v1+json`).
