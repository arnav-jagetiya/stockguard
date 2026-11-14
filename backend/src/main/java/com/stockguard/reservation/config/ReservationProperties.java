package com.stockguard.reservation.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe configuration properties for the STOCKGUARD reservation engine.
 * <p>
 * Binds properties under the {@code stockguard.reservation} prefix.
 */
@ConfigurationProperties(prefix = "stockguard.reservation")
@Validated
public class ReservationProperties {

    @Min(value = 1, message = "stockguard.reservation.default-ttl-minutes must be strictly greater than zero")
    private long defaultTtlMinutes = 15;

    @Valid
    private Expiration expiration = new Expiration();

    @PostConstruct
    public void validate() {
        if (defaultTtlMinutes <= 0) {
            throw new IllegalStateException("stockguard.reservation.default-ttl-minutes must be strictly greater than zero, but was: " + defaultTtlMinutes);
        }
        if (expiration != null) {
            expiration.validate();
        }
    }

    public long getDefaultTtlMinutes() {
        return defaultTtlMinutes;
    }

    public void setDefaultTtlMinutes(long defaultTtlMinutes) {
        this.defaultTtlMinutes = defaultTtlMinutes;
    }

    public Expiration getExpiration() {
        return expiration;
    }

    public void setExpiration(Expiration expiration) {
        this.expiration = expiration;
    }

    public static class Expiration {
        private boolean enabled = true;

        @Min(value = 1, message = "stockguard.reservation.expiration.fixed-delay-ms must be strictly greater than zero")
        private long fixedDelayMs = 30000;

        @Min(value = 0, message = "stockguard.reservation.expiration.initial-delay-ms must be non-negative")
        private long initialDelayMs = 10000;

        @Min(value = 1, message = "stockguard.reservation.expiration.batch-size must be strictly greater than zero")
        private int batchSize = 50;

        public void validate() {
            if (fixedDelayMs <= 0) {
                throw new IllegalStateException("stockguard.reservation.expiration.fixed-delay-ms must be strictly greater than zero, but was: " + fixedDelayMs);
            }
            if (initialDelayMs < 0) {
                throw new IllegalStateException("stockguard.reservation.expiration.initial-delay-ms must be non-negative, but was: " + initialDelayMs);
            }
            if (batchSize <= 0) {
                throw new IllegalStateException("stockguard.reservation.expiration.batch-size must be strictly greater than zero, but was: " + batchSize);
            }
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getFixedDelayMs() {
            return fixedDelayMs;
        }

        public void setFixedDelayMs(long fixedDelayMs) {
            this.fixedDelayMs = fixedDelayMs;
        }

        public long getPollingIntervalMs() {
            return fixedDelayMs;
        }

        public void setPollingIntervalMs(long pollingIntervalMs) {
            this.fixedDelayMs = pollingIntervalMs;
        }

        public long getInitialDelayMs() {
            return initialDelayMs;
        }

        public void setInitialDelayMs(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }
}
