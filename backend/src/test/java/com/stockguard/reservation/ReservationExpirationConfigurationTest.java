package com.stockguard.reservation;

import com.stockguard.reservation.config.ReservationConfig;
import com.stockguard.reservation.config.ReservationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationExpirationConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ReservationConfig.class);

    @Test
    @DisplayName("E24: Default configuration properties match approved specifications (15m TTL, 30s delay, 50 batch size)")
    void defaultConfigurationValues() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ReservationProperties props = context.getBean(ReservationProperties.class);
            assertThat(props.getDefaultTtlMinutes()).isEqualTo(15L);
            assertThat(props.getExpiration().isEnabled()).isTrue();
            assertThat(props.getExpiration().getFixedDelayMs()).isEqualTo(30000L);
            assertThat(props.getExpiration().getPollingIntervalMs()).isEqualTo(30000L);
            assertThat(props.getExpiration().getInitialDelayMs()).isEqualTo(10000L);
            assertThat(props.getExpiration().getBatchSize()).isEqualTo(50);
        });
    }

    @Test
    @DisplayName("E25: Custom external configuration values bind cleanly")
    void customConfigurationValues() {
        contextRunner
                .withPropertyValues(
                        "stockguard.reservation.default-ttl-minutes=45",
                        "stockguard.reservation.expiration.enabled=true",
                        "stockguard.reservation.expiration.fixed-delay-ms=60000",
                        "stockguard.reservation.expiration.initial-delay-ms=5000",
                        "stockguard.reservation.expiration.batch-size=100"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ReservationProperties props = context.getBean(ReservationProperties.class);
                    assertThat(props.getDefaultTtlMinutes()).isEqualTo(45L);
                    assertThat(props.getExpiration().getFixedDelayMs()).isEqualTo(60000L);
                    assertThat(props.getExpiration().getInitialDelayMs()).isEqualTo(5000L);
                    assertThat(props.getExpiration().getBatchSize()).isEqualTo(100);
                });
    }

    @Test
    @DisplayName("E26: Startup validation rejects zero, negative, or invalid configuration values")
    void startupValidationRejectsInvalidValues() {
        // 1. Programmatic validation in ReservationProperties and Expiration
        ReservationProperties zeroDelayProps = new ReservationProperties();
        zeroDelayProps.getExpiration().setFixedDelayMs(0);
        org.assertj.core.api.Assertions.assertThatThrownBy(zeroDelayProps::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fixed-delay-ms");

        ReservationProperties negDelayProps = new ReservationProperties();
        negDelayProps.getExpiration().setFixedDelayMs(-1000);
        org.assertj.core.api.Assertions.assertThatThrownBy(negDelayProps::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fixed-delay-ms");

        ReservationProperties zeroBatchProps = new ReservationProperties();
        zeroBatchProps.getExpiration().setBatchSize(0);
        org.assertj.core.api.Assertions.assertThatThrownBy(zeroBatchProps::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batch-size");

        ReservationProperties negBatchProps = new ReservationProperties();
        negBatchProps.getExpiration().setBatchSize(-5);
        org.assertj.core.api.Assertions.assertThatThrownBy(negBatchProps::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batch-size");

        ReservationProperties negInitDelayProps = new ReservationProperties();
        negInitDelayProps.getExpiration().setInitialDelayMs(-1);
        org.assertj.core.api.Assertions.assertThatThrownBy(negInitDelayProps::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("initial-delay-ms");

        // 2. ApplicationContext startup failure when invalid properties are configured
        // Zero fixed delay
        contextRunner
                .withPropertyValues("stockguard.reservation.expiration.fixed-delay-ms=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class);
                    assertThat(context.getStartupFailure().getMessage())
                            .contains("stockguard.reservation");
                });

        // Negative fixed delay
        contextRunner
                .withPropertyValues("stockguard.reservation.expiration.fixed-delay-ms=-1000")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class);
                });

        // Zero batch size
        contextRunner
                .withPropertyValues("stockguard.reservation.expiration.batch-size=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class);
                });

        // Negative batch size
        contextRunner
                .withPropertyValues("stockguard.reservation.expiration.batch-size=-5")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class);
                });

        // Negative initial delay
        contextRunner
                .withPropertyValues("stockguard.reservation.expiration.initial-delay-ms=-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class);
                });

        // Negative default TTL
        contextRunner
                .withPropertyValues("stockguard.reservation.default-ttl-minutes=-10")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class);
                });
    }

    @Test
    @DisplayName("Worker: Scheduled execution delegates to expiration service when enabled, skips when disabled, and catches exceptions safely")
    void workerExecutionBehavior() {
        com.stockguard.reservation.service.ReservationExpirationService mockService =
                org.mockito.Mockito.mock(com.stockguard.reservation.service.ReservationExpirationService.class);
        ReservationProperties properties = new ReservationProperties();

        com.stockguard.reservation.worker.ReservationExpirationWorker worker =
                new com.stockguard.reservation.worker.ReservationExpirationWorker(mockService, properties);

        // 1. When enabled, invokes service
        org.mockito.Mockito.when(mockService.sweepExpiredReservations())
                .thenReturn(new com.stockguard.reservation.dto.ExpirationBatchResult(5, 5, 0, 0, 12));

        var resEnabled = worker.runExpirationSweep();
        assertThat(resEnabled.examinedCount()).isEqualTo(5);
        assertThat(resEnabled.successCount()).isEqualTo(5);
        org.mockito.Mockito.verify(mockService).sweepExpiredReservations();

        // 2. When disabled, skips invocation
        properties.getExpiration().setEnabled(false);
        var resDisabled = worker.runExpirationSweep();
        assertThat(resDisabled.examinedCount()).isEqualTo(0);
        org.mockito.Mockito.verifyNoMoreInteractions(mockService);

        // 3. When service throws unexpected exception, catches and reports failure safely without propagating
        properties.getExpiration().setEnabled(true);
        org.mockito.Mockito.when(mockService.sweepExpiredReservations())
                .thenThrow(new RuntimeException("Simulated database failure"));

        var resError = worker.runExpirationSweep();
        assertThat(resError.failureCount()).isEqualTo(1);
    }
}
