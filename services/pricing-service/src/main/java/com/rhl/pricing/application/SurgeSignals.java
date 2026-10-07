package com.rhl.pricing.application;

import com.rhl.common.messaging.EventEnvelope;
import com.rhl.pricing.domain.ServiceType;
import com.rhl.pricing.infrastructure.cache.SurgeAreas;
import com.rhl.pricing.infrastructure.cache.SurgeCounters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Feeds the surge counters from events (README §4.7): demand from {@code TripRequested}, supply
 * from {@code DriverAvailabilityChanged} and {@code DriverLocationUpdated}. Every write is
 * idempotent in Redis (trip/driver IDs as set members, versions and timestamps compared in Lua),
 * so redelivered events need no processed_events bookkeeping.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SurgeSignals {

    private final SurgeAreas areas;
    private final SurgeCounters counters;
    private final Clock clock;

    public void onTripRequested(EventEnvelope event) {
        JsonNode payload = event.payload();
        Instant requestedAt = Instant.parse(payload.path("occurredAt").asString());
        if (requestedAt.isBefore(clock.instant().minus(counters.demandWindow()))) {
            return; // already outside every window that will be read
        }
        JsonNode pickup = payload.path("pickup");
        counters.recordDemand(ServiceType.valueOf(payload.path("serviceType").asString()),
                areas.cellOf(pickup.path("latitude").asDouble(), pickup.path("longitude").asDouble()),
                UUID.fromString(payload.path("tripId").asString()), requestedAt);
    }

    public void onAvailabilityChanged(EventEnvelope event) {
        JsonNode payload = event.payload();
        Set<ServiceType> types = EnumSet.noneOf(ServiceType.class);
        payload.path("serviceTypes").forEach(t -> types.add(ServiceType.valueOf(t.asString())));
        if (!counters.recordAvailability(UUID.fromString(payload.path("driverId").asString()), event.aggregateVersion(),
                payload.path("newStatus").asString(), types)) {
            log.debug("Ignored stale availability version {}", event.aggregateVersion());
        }
    }

    public void onLocationUpdated(EventEnvelope event) {
        JsonNode payload = event.payload();
        counters.recordPosition(UUID.fromString(payload.path("driverId").asString()),
                areas.cellOf(payload.path("latitude").asDouble(), payload.path("longitude").asDouble()),
                Instant.parse(payload.path("serverTimestamp").asString()));
    }
}
