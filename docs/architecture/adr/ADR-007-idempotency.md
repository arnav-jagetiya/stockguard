# ADR-007: PostgreSQL-Backed Idempotency

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-02 (Updated 2026-11-03) |

> [!IMPORTANT]
> This is an architectural decision record. Nothing described here has been implemented or tested.

## Context

Distributed clients and network failures can cause duplicate requests. A client may:

- Retry a request after a timeout, not knowing the original succeeded.
- Submit the same form or API payload twice due to UI or client retry logic.
- Replay requests during recovery from a network disruption.

Without idempotency, duplicate inventory operations cause **incorrect balances** — a stock adjustment applied twice doubles the intended change, and an order reservation processed twice holds double the required inventory.

Alternative approaches considered:

- **Redis-backed idempotency** — adds an external operational dependency; introduces a consistency window between Redis and PostgreSQL.
- **Application-level deduplication** — fragile; relies on in-memory state that does not survive server restarts or horizontal scaling.
- **Client-side deduplication only** — insufficient; the server must guarantee idempotency regardless of client behavior.

## Decision

Use **PostgreSQL-backed idempotency records** for all mutating API operations (`POST`, `PUT`, `PATCH`, `DELETE`):

### 1. Multi-Tenant Scoping and Payload Hashing
- Idempotency is explicitly scoped by:
  1. `organization_id` (tenant boundary)
  2. `principal_id` (authenticated user or API client)
  3. `operation_scope` (API endpoint or action name)
  4. `idempotency_key` (client-supplied header)
- The server computes a SHA-256 `request_hash` across the HTTP method, request path, and normalized request payload.

### 2. Request Handling & Concurrency Rules
- **Same Key, Same Payload:** If a completed record exists with matching scope and identical `request_hash`, the server immediately returns the previously recorded HTTP response status code and body without re-executing business logic.
- **Same Key, Different Payload:** If a record exists with matching scope but a differing `request_hash`, the server rejects the request with HTTP `409 Conflict`. An idempotency key cannot be reused across different payloads or modified parameters.
- **Concurrent Duplicate Requests (Wait-and-Replay):**
  - When Request 1 begins, it inserts an in-flight idempotency record (`status = 'IN_PROGRESS'`) within its atomic business transaction.
  - If a concurrent Request 2 arrives with the same key and payload, it attempts to insert or lock the record and **waits** on PostgreSQL row/index locking for Request 1's transaction to resolve.
  - When Request 1 successfully commits, Request 2 unblocks, observes the committed record (`status = 'COMPLETED'`), verifies the matching `request_hash`, and replays the stored response.
  - **Crucial Rule:** Concurrent duplicates must **never** execute the business operation twice, but they are **not** automatically rejected with HTTP 409. They wait and return the original successful response upon completion.
- **Rollback and Safe Retry:**
  - If Request 1 encounters an error, database failure, or business validation failure and rolls back, PostgreSQL's MVCC transaction rollback eliminates the uncommitted `IN_PROGRESS` row.
  - A subsequent request or client retry can then immediately proceed, create a new idempotency record, and safely execute the business operation.
- **Transactional Consistency:**
  - The idempotency record creation, business operation mutations, and response persistence occur within the exact same database transaction, ensuring strict atomicity.

### 3. Proposed Initial Retention & Cleanup Requirement
- **7-day retention:** As an initial design decision, idempotency records are retained for **7 days** (`expires_at = created_at + INTERVAL '7 days'`).
- **Cleanup before key reuse:** Because of the unique constraint on `(organization_id, principal_id, operation_scope, idempotency_key)`, expired records must be pruned and cleaned up by an asynchronous background job before a unique key can be safely reused.

### 4. Client Disconnect and Failure Scenarios
- **Client Disconnect Post-Commit:** A client disconnect or network timeout occurring *after* a successful database commit must not cause duplicate execution. The business mutations are already committed and the response is stored. When the client reconnects and retries with the same key and payload, the server replays the committed response immediately.
- **Unresolved Concurrency Edge Case:** Indefinite hang or transaction timeout while holding an in-progress idempotency record is explicitly marked as an unresolved architectural design question.

## Consequences

### Benefits

- **Prevents duplicate inventory mutations** — double reservations, duplicate fulfillments, and repeated stock adjustments are eliminated.
- **Multi-tenant isolation:** Scoping by organization and authenticated principal prevents cross-tenant or cross-user key collision.
- **Payload safety:** Payload hash verification detects accidental key reuse across distinct payloads.
- **Concurrency safe:** Race conditions on duplicate requests are guarded at the database level.
- **No external dependency:** Leverages existing PostgreSQL infrastructure without introducing external caching tiers.

### Risks and Trade-offs

- **Database write overhead:** Every mutating API request requires database interaction for idempotency recording.
- **Storage growth:** Retaining request and response payloads for 7 days requires database storage and routine cleanup.

## Unresolved Questions

| # | Question |
|---|---|
| 1 | Cleanup execution mechanism — scheduled Spring Boot worker (`@Scheduled`) vs. PostgreSQL `pg_cron` extension vs. partitioned tables with automated drop. |
| 2 | In-flight request timeout handling — maximum wait duration when a concurrent duplicate request blocks waiting for the in-flight transaction to commit. |
