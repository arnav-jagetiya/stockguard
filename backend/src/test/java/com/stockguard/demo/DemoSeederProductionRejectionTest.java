package com.stockguard.demo;

import com.stockguard.demo.config.DemoProperties;
import com.stockguard.demo.dto.DemoSeedResult;
import com.stockguard.demo.service.DemoEnvironmentSeeder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("prod")
class DemoSeederProductionRejectionTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("stockguard_test")
            .withUsername("test_user")
            .withPassword("test_pass")
            .withInitScript("init-test-roles.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "stockguard_app");
        registry.add("spring.datasource.password", () -> "test_app_pass");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "stockguard_owner");
        registry.add("spring.flyway.password", () -> "test_owner_pass");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("stockguard.security.jwt.active-key.secret", () -> "Mn2zX2VOPir/mbrZNjsKX0hxmrgJUQq2IVJIokt/x5U=");
        registry.add("stockguard.monitoring.scraper-password", () -> "prod-test-scraper-pass");
    }

    @Autowired
    private DemoEnvironmentSeeder demoEnvironmentSeeder;

    @Test
    @DisplayName("Seeder strictly rejects execution when prod profile is active")
    void rejectsExecutionInProductionProfile() {
        DemoProperties properties = new DemoProperties();
        properties.setSeedEnabled(true);
        properties.setOrgName("Acme Industrial Supply Pvt. Ltd.");

        assertThatThrownBy(() -> demoEnvironmentSeeder.seedDemoEnvironment(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Demo environment seeding is strictly forbidden in production");
    }

    @Test
    @DisplayName("Seeder cleanly skips when seedEnabled is false")
    void cleanlySkipsWhenDisabled() {
        DemoProperties properties = new DemoProperties();
        properties.setSeedEnabled(false);

        DemoSeedResult result = demoEnvironmentSeeder.seedDemoEnvironment(properties);
        assertThat(result.alreadySeeded()).isFalse();
        assertThat(result.message()).contains("Demo environment seeding is disabled");
    }
}
