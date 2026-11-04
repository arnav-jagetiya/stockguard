package com.stockguard.infrastructure;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lightweight configuration unit test validating that Spring Boot's
 * {@link DataSourceAutoConfiguration} processes database properties and registers
 * a configured {@link DataSource} bean.
 * <p>
 * Notice: This test validates configuration parsing and bean instantiation only.
 * It does NOT establish or verify live database connectivity. Real PostgreSQL connectivity
 * and query execution are verified separately in {@link TestcontainersPostgresTest}.
 */
class DataSourceConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues(
                    "spring.datasource.url=jdbc:postgresql://localhost:5432/stockguard",
                    "spring.datasource.username=stockguard",
                    "spring.datasource.password=stockguard",
                    "spring.datasource.driver-class-name=org.postgresql.Driver",
                    "spring.datasource.hikari.initialization-fail-timeout=-1"
            )
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class));

    @Test
    @DisplayName("DataSource bean and properties are created from environment configuration without live database connectivity")
    void dataSourceBeanIsConfigured() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DataSource.class);

            DataSource dataSource = context.getBean(DataSource.class);
            assertThat(dataSource).isInstanceOf(HikariDataSource.class);

            HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
            assertThat(hikariDataSource.getJdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/stockguard");
            assertThat(hikariDataSource.getUsername()).isEqualTo("stockguard");
            assertThat(hikariDataSource.getDriverClassName()).isEqualTo("org.postgresql.Driver");
        });
    }
}
