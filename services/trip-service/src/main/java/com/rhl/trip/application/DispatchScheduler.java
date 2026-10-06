package com.rhl.trip.application;

import com.rhl.trip.TripServiceProperties;
import com.rhl.trip.infrastructure.persistence.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Safety net behind the after-commit triggers: expires overdue offers, then runs a round for
 * every MATCHING trip without an open offer (including ones whose trigger was lost in a crash),
 * which is also where trips past their deadline end as NO_DRIVER. Safe on several instances.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DispatchScheduler {

    private final OfferService offers;
    private final Dispatcher dispatcher;
    private final TripRepository trips;
    private final TripServiceProperties properties;

    @Scheduled(fixedDelayString = "${rhl.matching.tick-interval}")
    public void tick() {
        int batch = properties.matching().batchSize();
        try {
            while (offers.expireDue(batch) == batch) {
                // keep draining
            }
            trips.findWaitingForOffer(batch).forEach(this::dispatchSafely);
        } catch (DataAccessException e) {
            log.warn("Dispatch tick failed: {}", e.getMostSpecificCause().getMessage());
        }
    }

    /** One broken trip must not keep the rest of the batch from being dispatched. */
    private void dispatchSafely(UUID tripId) {
        try {
            dispatcher.dispatch(tripId);
        } catch (RuntimeException e) {
            log.error("Dispatch of trip {} failed", tripId, e);
        }
    }
}
