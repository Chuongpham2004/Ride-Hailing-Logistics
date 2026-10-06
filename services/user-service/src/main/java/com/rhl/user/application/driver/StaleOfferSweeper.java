package com.rhl.user.application.driver;

import com.rhl.user.UserServiceProperties;
import com.rhl.user.infrastructure.persistence.DriverProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * A driver must never stay OFFERED (README §5.3). trip-service always closes an offer within its
 * timeout, but if that event is lost or arrives out of order, this frees the driver once the
 * offer is well past its expiry. Each driver is handled in its own transaction; a concurrent
 * change wins and the driver is looked at again on the next run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleOfferSweeper {

    private static final int BATCH = 100;

    private final DriverProfileRepository profiles;
    private final TripAvailabilityProjection projection;
    private final UserServiceProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${rhl.driver.stale-offer-check-interval}")
    public void sweep() {
        Duration grace = properties.driver().staleOfferGrace();
        try {
            for (UUID driverId : profiles.findStaleOffered(clock.instant().minus(grace), BATCH)) {
                try {
                    projection.releaseStaleOffer(driverId, grace);
                } catch (DataAccessException e) {
                    log.debug("Stale offer of a driver not released this run: {}", e.getMessage());
                }
            }
        } catch (DataAccessException e) {
            log.warn("Stale offer sweep failed: {}", e.getMostSpecificCause().getMessage());
        }
    }
}
