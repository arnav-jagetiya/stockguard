package com.stockguard.infrastructure.metrics;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for Prometheus monitoring and HTTP Basic authentication per ADR-014.
 */
@ConfigurationProperties(prefix = "stockguard.monitoring")
@Validated
public class MonitoringProperties {

    @NotBlank
    private String scraperUsername = "stockguard-prometheus";

    @NotBlank
    private String scraperPassword = "dev-scrape-pass";

    public String getScraperUsername() {
        return scraperUsername;
    }

    public void setScraperUsername(String scraperUsername) {
        this.scraperUsername = scraperUsername;
    }

    public String getScraperPassword() {
        return scraperPassword;
    }

    public void setScraperPassword(String scraperPassword) {
        this.scraperPassword = scraperPassword;
    }
}
