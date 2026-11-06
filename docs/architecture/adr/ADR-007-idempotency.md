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
  2. `principal_type` (actor type, e.g. `'USER'`)
  3. `principal_id` (authenticated user or API client)
  4. `operation_scope` (API endpoint or action name)
  5. `idempotency_key` (client-supplied header)
- The server computes a SHA-256 `request_hash` across all semantically relevant request inputs: the HTTP method, request path, normalized request body payload, normalized query parameters, and relevant headers where applicable.

### 2. Request Handling & Concurrency Rules
- **Same Key, Same Payload:** If a completed record exists with matching scope and identical `request_hash`, the server immediately returns the previously recorded HTTP response status code and body without re-executing business logic.
- **Same Key, Different Payload:** If a record exists with matching scope but a differing `request_hash`, the server rejects the request with HTTP `409 Conflict`. An idempotency key cannot be reused across different payloads or modified parameters.
- **Initial Insert & Ownership Establishment:**
  - Mutating transactions begin by inserting an initial idempotency record with `response_status = NULL`, `response_body = NULL`, and `RETURNING id`:
    ```sql
    INSERT INTO idempotency_records (
      id, organization_id, principal_type, principal_id, operation_scope, idempotency_key, request_hash, response_status, response_body, created_at, expires_at
    ) VALUES (
      :idemp_id, :org_id, 'USER', :principal_id, :scope, :key, :hash, NULL, NULL, NOW(), NOW() + INTERVAL '7 days'
    ) ON CONFLICT (organization_id, principal_type, principal_id, operation_scope, idempotency_key) DO NOTHING
    RETURNING id;
    ```
  - This statement produces a result set and must be consumed via a query/`ResultSet` API (e.g., `PreparedStatement.executeQuery()`), rather than an update-count API.
- **PostgreSQL READ COMMITTED Concurrency Dynamics:**
  1. **Waiting on a Concurrent Unique-Index Conflict:** When Request 2 executes the initial insert while Request 1 is actively executing with the same scoped key, Request 2 encounters a unique index conflict on `(organization_id, principal_type, principal_id, operation_scope, idempotency_key)` and **waits** on the index conflict until Request 1's transaction terminates.
  2. **Reading/Locking the Committed Record After Commit:** If Request 1 successfully commits, Request 2's insert does not insert a row (`DO NOTHING` handles the conflict) and returns 0 rows. Request 2 unblocks, follows the duplicate/replay path, and reads the existing committed record with a row lock (`SELECT ... FOR UPDATE`). It verifies `request_hash`:
     - If `request_hash` matches and `response_status IS NOT NULL`: Request 2 replays the stored response status and body immediately and rolls back without re-executing business logic. Concurrent duplicates **never** execute twice.
     - If `request_hash` differs: Request 2 immediately returns HTTP `409 Conflict` and rolls back.
  3. **Becoming the Owner Through the Waiting Insert Itself Upon Rollback:** If Request 1 encounters an error or timeout and rolls back, PostgreSQL's MVCC transaction rollback eliminates Request 1's speculative row and releases its unique index lock. Request 2's waiting insert immediately unblocks and **successfully inserts its own row**, returning 1 row (`RETURNING id` yields its ID). Request 2 becomes the primary key owner directly through its waiting insert without needing a second insert statement, and proceeds immediately with business logic.
- **Transactional Consistency:**
  - The idempotency record creation, business operation mutations, and response persistence occur within the exact same database transaction, ensuring strict atomicity. Response updates filter by `WHERE id = :idemp_id AND organization_id = :org_id`.

### 3. Proposed Initial Retention & Cleanup Requirement
- **7-day retention:** As an initial design decision, idempotency records are retained for **7 days** (`expires_at = created_at + INTERVAL '7 days'`).
- **Cleanup before key reuse:** Because of the unique constraint on `(organization_id, principal_type, principal_id, operation_scope, idempotency_key)`, expired records must be pruned and cleaned up by an asynchronous background job before a unique key can be safely reused.

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
