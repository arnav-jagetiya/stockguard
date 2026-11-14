package com.stockguard.reservation.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring configuration enabling reservation properties and scheduling.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ReservationProperties.class)
public class ReservationConfig {
}
