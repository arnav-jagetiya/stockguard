package com.stockguard.reservation.worker;

import com.stockguard.reservation.config.ReservationProperties;
import com.stockguard.reservation.dto.ExpirationBatchResult;
import com.stockguard.reservation.service.ReservationExpirationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled background worker executing automated reservation expiration sweeps.
 * <p>
 * Configuration:
 * <ul>
 *   <li>{@code stockguard.reservation.expiration.enabled}: Controls worker activation.</li>
 *   <li>{@code stockguard.reservation.expiration.fixed-delay-ms}: Interval between completion of previous sweep and start of next.</li>
 *   <li>{@code stockguard.reservation.expiration.initial-delay-ms}: Delay before first sweep on application startup.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(
        name = "stockguard.reservation.expiration.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReservationExpirationWorker {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirationWorker.class);

    private final ReservationExpirationService expirationService;
    private final ReservationProperties properties;

    @Autowired
    public ReservationExpirationWorker(
            ReservationExpirationService expirationService,
            ReservationProperties properties
    ) {
        this.expirationService = expirationService;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${stockguard.reservation.expiration.fixed-delay-ms:30000}",
            initialDelayString = "${stockguard.reservation.expiration.initial-delay-ms:10000}"
    )
    public ExpirationBatchResult runExpirationSweep() {
        if (!properties.getExpiration().isEnabled()) {
            log.debug("Reservation expiration worker is disabled by property.");
            return new ExpirationBatchResult(0, 0, 0, 0, 0);
        }

        try {
            log.debug("Starting scheduled reservation expiration sweep...");
            ExpirationBatchResult result = expirationService.sweepExpiredReservations();
            log.debug("Scheduled reservation expiration sweep completed: {}", result);
            return result;
        } catch (Exception ex) {
            log.error("Unexpected error in reservation expiration sweep: {}", ex.getMessage(), ex);
            return new ExpirationBatchResult(0, 0, 0, 1, 0);
        }
    }
}
