package com.stockguard.security.context;

import java.util.Optional;

/**
 * Thread-local holder for the active request's {@link OrganizationContext}.
 * <p>
 * Cleared deterministically in {@link com.stockguard.security.filter.OrganizationContextFilter}
 * to prevent thread pool contamination in servlet containers.
 */
public final class OrganizationContextHolder {

    private static final ThreadLocal<OrganizationContext> CONTEXT = new ThreadLocal<>();

    private OrganizationContextHolder() {
    }

    public static void setContext(OrganizationContext context) {
        CONTEXT.set(context);
    }

    public static Optional<OrganizationContext> getContext() {
        return Optional.ofNullable(CONTEXT.get());
    }

    public static OrganizationContext requireContext() {
        OrganizationContext ctx = CONTEXT.get();
        if (ctx == null) {
            throw new IllegalStateException("No active organization context found on the current execution thread");
        }
        return ctx;
    }

    public static void clearContext() {
        CONTEXT.remove();
    }
}
