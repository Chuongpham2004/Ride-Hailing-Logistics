package com.rhl.realtime.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Who takes part in a trip, as last announced on {@code trip.events.v1}: the customer, the driver
 * once one accepted, the status and when the trip ended. Decides who may follow the trip and
 * see its driver's position (BR-013).
 *
 * @param driverId {@code null} until a driver accepted
 * @param endedAt  {@code null} while the trip is running
 */
public record TripParticipants(UUID tripId, UUID customerId, UUID driverId, String status, Instant endedAt,
                               long version) {

    /** Statuses after which a trip never runs again. */
    public static final Set<String> ENDED = Set.of("COMPLETED", "CANCELLED", "NO_DRIVER");

    public enum Participant {
        CUSTOMER,
        DRIVER
    }

    public boolean isEnded() {
        return ENDED.contains(status);
    }

    /** Running, or ended less than {@code grace} ago. */
    public boolean isFollowable(Instant now, Duration grace) {
        return !isEnded() || (endedAt != null && now.isBefore(endedAt.plus(grace)));
    }

    public Optional<Participant> participant(UUID userId) {
        if (userId.equals(customerId)) {
            return Optional.of(Participant.CUSTOMER);
        }
        if (userId.equals(driverId)) {
            return Optional.of(Participant.DRIVER);
        }
        return Optional.empty();
    }
}
