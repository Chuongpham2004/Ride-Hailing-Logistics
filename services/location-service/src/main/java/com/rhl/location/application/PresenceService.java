package com.rhl.location.application;

import com.rhl.common.messaging.EventEnvelope;
import com.rhl.common.messaging.ProcessedEvents;
import com.rhl.location.domain.Availability;
import com.rhl.location.domain.DriverPresence;
import com.rhl.location.domain.ServiceType;
import com.rhl.location.infrastructure.cache.LocationStore;
import com.rhl.location.infrastructure.persistence.DriverPresenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps driver_presence and the GEO index in step with {@code DriverAvailabilityChanged}
 * (README §4.6: only AVAILABLE drivers are indexed).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {

    static final String CONSUMER = "location-service.presence";

    private final ProcessedEvents processedEvents;
    private final DriverPresenceRepository presences;
    private final LocationStore store;
    private final Clock clock;

    /**
     * Redis is updated inside the database transaction: if Redis fails, the transaction and the
     * processed_events mark roll back and the event is retried. Both writes are idempotent, so a
     * retry after a failed commit is harmless.
     */
    @Transactional
    public void onAvailabilityChanged(EventEnvelope event) {
        if (!processedEvents.markProcessed(CONSUMER, event.eventId())) {
            return;
        }
        JsonNode payload = event.payload();
        Availability oldStatus = Availability.valueOf(payload.path("oldStatus").asString());
        DriverPresence presence = new DriverPresence(
                UUID.fromString(payload.path("driverId").asString()),
                Availability.valueOf(payload.path("newStatus").asString()),
                serviceTypes(payload.path("serviceTypes")),
                payload.hasNonNull("vehicleId") ? UUID.fromString(payload.path("vehicleId").asString()) : null,
                event.aggregateVersion(),
                Instant.parse(payload.path("occurredAt").asString()));

        if (!presences.saveIfNewer(presence, clock.instant())) {
            log.debug("Ignored stale availability version {} for a driver", event.aggregateVersion());
            return;
        }
        boolean newSession = oldStatus == Availability.OFFLINE && !presence.isOffline();
        store.setAvailability(presence.driverId(), presence.matchableServiceTypes(), presence.vehicleId(),
                newSession);
    }

    /** Restores availability markers after Redis lost its data (DR-GEO-005); positions follow with the next reports. */
    @EventListener(ApplicationReadyEvent.class)
    public void restoreIndex() {
        try {
            var available = presences.findByAvailability(Availability.AVAILABLE);
            available.forEach(p ->
                    store.setAvailability(p.driverId(), p.matchableServiceTypes(), p.vehicleId(), false));
            log.info("Restored availability for {} driver(s) into Redis", available.size());
        } catch (RuntimeException e) {
            // Not fatal: the next availability event per driver writes the marker again.
            log.warn("Could not restore availability into Redis: {}", e.getMessage());
        }
    }

    private static Set<ServiceType> serviceTypes(JsonNode node) {
        Set<ServiceType> types = EnumSet.noneOf(ServiceType.class);
        node.forEach(type -> types.add(ServiceType.valueOf(type.asString())));
        return types;
    }
}
