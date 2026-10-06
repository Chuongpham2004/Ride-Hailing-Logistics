package com.rhl.trip.domain;

import java.time.Instant;
import java.util.UUID;

/** One status change, as written to trip_status_history and announced in trip events. */
public record Transition(UUID tripId, TripStatus from, TripStatus to, Actor actor, String reason, Instant at) {
}
