package com.stockguard.organization.domain;

/**
 * Approved organization membership roles per ADR-011 and database migration V1.
 * <p>
 * Strictly constrained to:
 * <ul>
 *   <li>{@link #ADMIN} - Organization administrator (manages memberships, policies, operations)</li>
 *   <li>{@link #MANAGER} - Inventory and operations manager</li>
 *   <li>{@link #OPERATOR} - Day-to-day warehouse and inventory operator</li>
 *   <li>{@link #VIEWER} - Read-only auditor and observer</li>
 * </ul>
 */
public enum Role {
    ADMIN,
    MANAGER,
    OPERATOR,
    VIEWER
}
