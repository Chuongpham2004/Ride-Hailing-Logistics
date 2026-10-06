package com.rhl.user.domain.driver;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Result of an availability transition; published as {@code DriverAvailabilityChanged}. */
public record AvailabilityChange(UUID driverId, Availability oldStatus, Availability newStatus,
                                 Set<ServiceType> serviceTypes, UUID vehicleId, String reason, Instant occurredAt) {
}
