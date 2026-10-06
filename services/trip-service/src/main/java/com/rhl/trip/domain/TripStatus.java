package com.rhl.trip.domain;

/** Trip lifecycle (README §6); allowed moves live in {@link TripStateMachine}. */
public enum TripStatus {
    CREATED,
    MATCHING,
    ACCEPTED,
    PICKING_UP,
    ARRIVED,
    IN_TRIP,
    COMPLETED,
    CANCELLED,
    NO_DRIVER;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == NO_DRIVER;
    }

    /** A driver is assigned and the trip is not over (BR-002, the unique index on trips.driver_id). */
    public boolean hasAssignedDriver() {
        return this == ACCEPTED || this == PICKING_UP || this == ARRIVED || this == IN_TRIP;
    }
}
