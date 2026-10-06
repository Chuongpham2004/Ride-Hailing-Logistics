package com.rhl.trip.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.rhl.trip.domain.ActorType.CUSTOMER;
import static com.rhl.trip.domain.ActorType.DRIVER;
import static com.rhl.trip.domain.ActorType.STAFF;
import static com.rhl.trip.domain.ActorType.SYSTEM;
import static com.rhl.trip.domain.TripStatus.ACCEPTED;
import static com.rhl.trip.domain.TripStatus.ARRIVED;
import static com.rhl.trip.domain.TripStatus.CANCELLED;
import static com.rhl.trip.domain.TripStatus.COMPLETED;
import static com.rhl.trip.domain.TripStatus.CREATED;
import static com.rhl.trip.domain.TripStatus.IN_TRIP;
import static com.rhl.trip.domain.TripStatus.MATCHING;
import static com.rhl.trip.domain.TripStatus.NO_DRIVER;
import static com.rhl.trip.domain.TripStatus.PICKING_UP;

/**
 * Explicit transition table of README §6 (CON-05): for each move, the actors allowed to make
 * it. Anything not listed is refused with {@code INVALID_TRIP_STATE} (BR-008).
 */
public final class TripStateMachine {

    private static final Map<TripStatus, Map<TripStatus, Set<ActorType>>> TRANSITIONS = new EnumMap<>(TripStatus.class);

    static {
        allow(CREATED, MATCHING, SYSTEM);
        allow(CREATED, CANCELLED, CUSTOMER, STAFF);
        allow(MATCHING, ACCEPTED, DRIVER);
        allow(MATCHING, NO_DRIVER, SYSTEM);
        allow(MATCHING, CANCELLED, CUSTOMER, STAFF);
        allow(ACCEPTED, PICKING_UP, DRIVER);
        allow(ACCEPTED, CANCELLED, CUSTOMER, DRIVER, STAFF);
        allow(PICKING_UP, ARRIVED, DRIVER);
        allow(PICKING_UP, CANCELLED, CUSTOMER, DRIVER, STAFF);
        allow(ARRIVED, IN_TRIP, DRIVER);
        // Includes the driver reporting a no-show.
        allow(ARRIVED, CANCELLED, CUSTOMER, DRIVER, STAFF);
        allow(IN_TRIP, COMPLETED, DRIVER);
        // Only through the staff exception process (README §6).
        allow(IN_TRIP, CANCELLED, STAFF);
    }

    private TripStateMachine() {
    }

    public static boolean allows(TripStatus from, TripStatus to, ActorType actor) {
        return TRANSITIONS.getOrDefault(from, Map.of()).getOrDefault(to, Set.of()).contains(actor);
    }

    /** @throws DomainException {@code INVALID_TRIP_STATE} when the move or the actor is not allowed */
    public static void check(TripStatus from, TripStatus to, ActorType actor) {
        if (!allows(from, to, actor)) {
            throw DomainException.invalidTripState(
                    "Cannot move a trip from " + from + " to " + to + " as " + actor);
        }
    }

    private static void allow(TripStatus from, TripStatus to, ActorType... actors) {
        TRANSITIONS.computeIfAbsent(from, k -> new EnumMap<>(TripStatus.class))
                .put(to, Set.copyOf(EnumSet.of(actors[0], actors)));
    }
}
