package com.stockguard.order.dto;

import java.util.UUID;

/**
 * Standard payload representation used for computing SHA-256 idempotency request hashes
 * on order lifecycle actions (confirm, cancel, fulfill).
 */
public record OrderLifecycleActionRequest(UUID orderId) {
}
