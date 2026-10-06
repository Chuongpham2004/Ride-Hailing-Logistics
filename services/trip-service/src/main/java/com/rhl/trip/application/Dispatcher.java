package com.rhl.trip.application;

import com.rhl.common.id.UuidV7;
import com.rhl.trip.TripServiceProperties;
import com.rhl.trip.domain.DriverCandidate;
import com.rhl.trip.infrastructure.cache.DriverHoldStore;
import com.rhl.trip.infrastructure.client.LocationClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One dispatch round for a trip (README §5.2): ask location-service for nearby AVAILABLE
 * drivers, drop the ones already tried or busy, hold the best one in Redis and open an offer.
 * With nobody left, the radius grows step by step; at the maximum the trip waits for the next
 * tick until the matching deadline. Remote and Redis calls stay outside database transactions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Dispatcher {

    private final OfferService offers;
    private final LocationClient location;
    private final DriverHoldStore holds;
    private final TripServiceProperties properties;

    public void dispatch(UUID tripId) {
        try {
            while (true) {
                Optional<OfferService.Round> round = offers.prepareRound(tripId);
                if (round.isEmpty() || offerToBestCandidate(round.get()) || !offers.widenSearch(tripId)) {
                    return;
                }
            }
        } catch (LocationClient.LocationUnavailableException | DataAccessException e) {
            // Retried on the next tick; the matching deadline still applies.
            log.warn("Dispatch round for trip {} failed: {}", tripId, e.getMessage());
        }
    }

    /** @return {@code true} when an offer was opened or the trip no longer needs one */
    private boolean offerToBestCandidate(OfferService.Round round) {
        TripServiceProperties.Matching config = properties.matching();
        List<DriverCandidate> nearby = location.nearby(round.serviceType(), round.pickup(), round.radiusMeters(),
                config.candidateLimit());
        Set<UUID> excluded = new HashSet<>(round.excluded());
        excluded.addAll(offers.busy(nearby.stream().map(DriverCandidate::driverId).toList()));
        Duration holdTtl = config.holdTtl();

        for (DriverCandidate candidate : DriverCandidate.rank(nearby, excluded)) {
            UUID offerId = UuidV7.random();
            if (!holds.tryHold(candidate.driverId(), offerId, holdTtl)) {
                continue; // held for another trip's offer
            }
            boolean opened = false;
            try {
                opened = offers.open(round.tripId(), offerId, candidate);
                return true;
            } catch (DataAccessException e) {
                // Lost a race on a pending-offer unique index; the next round sorts it out.
                log.debug("Offer for trip {} not opened: {}", round.tripId(), e.getMessage());
                return true;
            } finally {
                if (!opened) {
                    holds.release(candidate.driverId(), offerId);
                }
            }
        }
        return false;
    }
}
