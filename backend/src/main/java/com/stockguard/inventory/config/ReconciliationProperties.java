package com.stockguard.inventory.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe configuration properties for the STOCKGUARD inventory reconciliation subsystem per ADR-012.
 */
@ConfigurationProperties(prefix = "stockguard.reconciliation")
@Validated
public class ReconciliationProperties {

    @Valid
    private Scheduled scheduled = new Scheduled();

    @PostConstruct
    public void validate() {
        if (scheduled != null) {
            scheduled.validate();
        }
    }

    public Scheduled getScheduled() {
        return scheduled;
    }

    public void setScheduled(Scheduled scheduled) {
        this.scheduled = scheduled;
    }

    public static class Scheduled {

        private boolean enabled = true;

        @NotBlank(message = "stockguard.reconciliation.scheduled.cron must not be blank")
        private String cron = "0 0 2 * * *";

        @Min(value = 0, message = "stockguard.reconciliation.scheduled.initial-delay-ms must be non-negative")
        private long initialDelayMs = 30000;

        @Min(value = 1, message = "stockguard.reconciliation.scheduled.max-duration-seconds must be strictly greater than zero")
        private long maxDurationSeconds = 1800;

        @Min(value = 1, message = "stockguard.reconciliation.scheduled.query-timeout-seconds must be strictly greater than zero")
        private int queryTimeoutSeconds = 60;

        @Min(value = 1, message = "stockguard.reconciliation.scheduled.tenant-batch-size must be strictly greater than zero")
        private int tenantBatchSize = 100;

        public void validate() {
            if (cron == null || cron.trim().isEmpty()) {
                throw new IllegalStateException("stockguard.reconciliation.scheduled.cron must not be blank");
            }
            if (maxDurationSeconds <= 0) {
                throw new IllegalStateException("stockguard.reconciliation.scheduled.max-duration-seconds must be positive");
            }
            if (tenantBatchSize <= 0) {
                throw new IllegalStateException("stockguard.reconciliation.scheduled.tenant-batch-size must be positive");
            }
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public long getInitialDelayMs() {
            return initialDelayMs;
        }

        public void setInitialDelayMs(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
        }

        public long getMaxDurationSeconds() {
            return maxDurationSeconds;
        }

        public void setMaxDurationSeconds(long maxDurationSeconds) {
            this.maxDurationSeconds = maxDurationSeconds;
        }

        public int getQueryTimeoutSeconds() {
            return queryTimeoutSeconds;
        }

        public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
            this.queryTimeoutSeconds = queryTimeoutSeconds;
        }

        public int getTenantBatchSize() {
            return tenantBatchSize;
        }

        public void setTenantBatchSize(int tenantBatchSize) {
            this.tenantBatchSize = tenantBatchSize;
        }
    }
}
