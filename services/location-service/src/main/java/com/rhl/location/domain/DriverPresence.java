package com.rhl.location.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** What location-service knows about a driver's availability, projected from driver.events.v1. */
public record DriverPresence(
        UUID driverId,
        Availability availability,
        Set<ServiceType> serviceTypes,
        UUID vehicleId,
        long aggregateVersion,
        Instant changedAt) {

    public boolean isOffline() {
        return availability == Availability.OFFLINE;
    }

    /** Service types the driver is matchable for right now; empty unless {@link Availability#AVAILABLE}. */
    public Set<ServiceType> matchableServiceTypes() {
        return availability == Availability.AVAILABLE ? serviceTypes : Set.of();
    }
}
