/**
 * Shared module: cross-cutting infrastructure, PostgreSQL-backed scoped idempotency,
 * common error envelopes, and base configuration.
 * <p>
 * Managed database tables:
 * <ul>
 *   <li>{@code idempotency_records}</li>
 * </ul>
 */
package com.stockguard.shared;
