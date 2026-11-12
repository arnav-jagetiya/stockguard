package com.stockguard.infrastructure;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that Spring Boot's datasource and Flyway autoconfigurations process
 * separated database identities, isolating runtime queries (stockguard_app)
 * from migration execution (stockguard_owner).
 */
class DataSourceConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues(
                    "spring.datasource.url=jdbc:postgresql://localhost:5432/stockguard",
                    "spring.datasource.username=stockguard_app",
                    "spring.datasource.password=test_app_pass",
                    "spring.datasource.driver-class-name=org.postgresql.Driver",
                    "spring.datasource.hikari.initialization-fail-timeout=-1",
                    "spring.flyway.url=jdbc:postgresql://localhost:5432/stockguard",
                    "spring.flyway.user=stockguard_owner",
                    "spring.flyway.password=test_owner_pass",
                    "spring.flyway.locations=classpath:db/migration"
            )
            .withBean(FlywayMigrationStrategy.class, () -> flyway -> {}) // No-op migration strategy for isolated unit testing
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    FlywayAutoConfiguration.class
            ));

    @Test
    @DisplayName("Runtime datasource uses stockguard_app and is isolated from Flyway owner identity")
    void runtimeDataSourceUsesAppRole() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DataSource.class);

            DataSource dataSource = context.getBean(DataSource.class);
            assertThat(dataSource).isInstanceOf(HikariDataSource.class);

            HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
            assertThat(hikariDataSource.getUsername()).isEqualTo("stockguard_app");
            assertThat(hikariDataSource.getUsername()).isNotEqualTo("stockguard_owner");
            assertThat(hikariDataSource.getJdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/stockguard");
        });
    }

    @Test
    @DisplayName("Flyway configuration uses dedicated stockguard_owner migration credentials")
    void flywayUsesOwnerRole() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(Flyway.class);

            Flyway flyway = context.getBean(Flyway.class);
            DataSource flywayDataSource = flyway.getConfiguration().getDataSource();
            assertThat(flywayDataSource).isInstanceOf(org.springframework.jdbc.datasource.SimpleDriverDataSource.class);

            org.springframework.jdbc.datasource.SimpleDriverDataSource simpleDs =
                    (org.springframework.jdbc.datasource.SimpleDriverDataSource) flywayDataSource;
            assertThat(simpleDs.getUsername()).isEqualTo("stockguard_owner");
            assertThat(simpleDs.getUsername()).isNotEqualTo("stockguard_app");
            assertThat(simpleDs.getUrl()).isEqualTo("jdbc:postgresql://localhost:5432/stockguard");

            org.springframework.boot.flyway.autoconfigure.FlywayProperties flywayProperties =
                    context.getBean(org.springframework.boot.flyway.autoconfigure.FlywayProperties.class);
            assertThat(flywayProperties.getUser()).isEqualTo("stockguard_owner");
            assertThat(flywayProperties.getUser()).isNotEqualTo("stockguard_app");
            assertThat(flywayProperties.getUrl()).isEqualTo("jdbc:postgresql://localhost:5432/stockguard");
        });
    }
}
