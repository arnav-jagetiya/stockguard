package com.stockguard.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe configuration properties for the synthetic demonstration environment.
 */
@ConfigurationProperties(prefix = "stockguard.demo")
public class DemoProperties {

    /**
     * Whether demo environment seeding is enabled. Strictly disabled by default.
     */
    private boolean seedEnabled = false;

    /**
     * Fictional demo organization name.
     */
    private String orgName = "Acme Industrial Supply Pvt. Ltd.";

    /**
     * Demo administrator email address.
     */
    private String adminEmail = "acme-admin@stockguard.dev";

    /**
     * Demo administrator password for local development access.
     * When unset or null, seeder generates an unpredictable high-entropy credential.
     */
    private String adminPassword;

    /**
     * Demo administrator full name.
     */
    private String adminFullName = "Aarav Mehta";

    public boolean isSeedEnabled() {
        return seedEnabled;
    }

    public void setSeedEnabled(boolean seedEnabled) {
        this.seedEnabled = seedEnabled;
    }

    public String getOrgName() {
        return orgName;
    }

    public void setOrgName(String orgName) {
        this.orgName = orgName;
    }

    public String getAdminEmail() {
        return adminEmail;
    }

    public void setAdminEmail(String adminEmail) {
        this.adminEmail = adminEmail;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }

    public String getAdminFullName() {
        return adminFullName;
    }

    public void setAdminFullName(String adminFullName) {
        this.adminFullName = adminFullName;
    }
}
