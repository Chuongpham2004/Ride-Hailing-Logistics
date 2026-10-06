package com.rhl.trip.domain;

/** A business rule rejected the operation. The API layer maps each kind to an error code (README §8.3). */
public class DomainException extends RuntimeException {

    public enum Kind {
        /** The move is not in the state machine, or not for this actor (409). */
        INVALID_TRIP_STATE,
        /** The offer is no longer open: expired, declined or lost to another outcome (409). */
        OFFER_EXPIRED,
        /** The driver already has an active trip (409). */
        DRIVER_ALREADY_ASSIGNED,
        /** Allowed in this state but a precondition is not met (422). */
        RULE_VIOLATION
    }

    private final Kind kind;

    private DomainException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public static DomainException invalidTripState(String message) {
        return new DomainException(Kind.INVALID_TRIP_STATE, message);
    }

    public static DomainException offerExpired(String message) {
        return new DomainException(Kind.OFFER_EXPIRED, message);
    }

    public static DomainException driverAlreadyAssigned(String message) {
        return new DomainException(Kind.DRIVER_ALREADY_ASSIGNED, message);
    }

    public static DomainException rule(String message) {
        return new DomainException(Kind.RULE_VIOLATION, message);
    }

    public Kind kind() {
        return kind;
    }
}
