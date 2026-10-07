package com.rhl.user.application.driver;

import com.rhl.common.messaging.EventEnvelope;
import com.rhl.common.messaging.ProcessedEvents;
import com.rhl.user.domain.driver.AvailabilityChange;
import com.rhl.user.domain.driver.DriverProfile;
import com.rhl.user.infrastructure.persistence.DriverProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Projects trip-service's dispatch and trip events onto driver availability: AVAILABLE →
 * OFFERED → BUSY → AVAILABLE (README §4.8, §5.3). Each change is republished as
 * {@code DriverAvailabilityChanged}, which is how location-service learns to stop matching a
 * driver, so availability keeps a single source and a single version sequence.
 *
 * <p>Idempotent through {@code processed_events}. A concurrent change by the driver (going
 * offline) fails the optimistic lock; the whole transaction rolls back and the event is retried.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripAvailabilityProjection {

    static final String CONSUMER = "user-service.trip-availability";

    private final ProcessedEvents processedEvents;
    private final DriverProfileRepository profiles;
    private final DriverService drivers;
    private final Clock clock;

    @Transactional
    public void apply(EventEnvelope event) {
        if (!processedEvents.markProcessed(CONSUMER, event.eventId())) {
            return;
        }
        JsonNode payload = event.payload();
        if (!payload.hasNonNull("driverId")) {
            return; // e.g. a trip cancelled before any driver was assigned
        }
        UUID driverId = UUID.fromString(payload.path("driverId").asString());
        Optional<DriverProfile> found = profiles.findById(driverId);
        if (found.isEmpty()) {
            log.warn("{} {} names a driver without a profile", event.eventType(), event.eventId());
            return;
        }
        DriverProfile profile = found.get();
        Instant now = clock.instant();
        Optional<AvailabilityChange> change = switch (event.eventType()) {
            case "DriverOfferCreated" -> profile.offered(id(payload, "offerId"),
                    Instant.parse(payload.path("expiresAt").asString()), now);
            case "DriverOfferExpired" -> profile.offerClosed(id(payload, "offerId"), "OFFER_EXPIRED", now);
            case "DriverOfferDeclined" -> profile.offerClosed(id(payload, "offerId"), "OFFER_DECLINED", now);
            case "DriverOfferCancelled" -> profile.offerClosed(id(payload, "offerId"), "OFFER_CANCELLED", now);
            case "TripAccepted" -> profile.tripAssigned(id(payload, "tripId"), id(payload, "offerId"), now);
            case "TripCompleted" -> profile.tripEnded(id(payload, "tripId"), "TRIP_COMPLETED", now);
            case "TripCancelled" -> profile.tripEnded(id(payload, "tripId"), "TRIP_CANCELLED", now);
            default -> Optional.empty();
        };
        change.ifPresentOrElse(c -> drivers.publish(profile, c),
                () -> log.debug("{} {} does not change availability ({})", event.eventType(), event.eventId(),
                        profile.getAvailability()));
    }

    /** Frees one driver stuck in OFFERED past the grace period (see {@link StaleOfferSweeper}). */
    @Transactional
    public void releaseStaleOffer(UUID driverId, Duration grace) {
        profiles.findById(driverId).ifPresent(profile ->
                profile.releaseStaleOffer(grace, clock.instant()).ifPresent(change -> {
                    log.warn("Driver offer went stale without a closing event; driver set {}", change.newStatus());
                    drivers.publish(profile, change);
                }));
    }

    private static UUID id(JsonNode payload, String field) {
        return UUID.fromString(payload.path(field).asString());
    }
}
