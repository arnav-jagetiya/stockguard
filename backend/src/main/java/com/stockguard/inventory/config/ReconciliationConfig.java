package com.stockguard.inventory.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration enabling reconciliation properties per ADR-012.
 */
@Configuration
@EnableConfigurationProperties(ReconciliationProperties.class)
public class ReconciliationConfig {
}
