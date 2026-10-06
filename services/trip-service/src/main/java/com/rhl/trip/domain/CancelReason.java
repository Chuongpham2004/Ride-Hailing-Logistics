package com.rhl.trip.domain;

/** Mandatory reason code for a cancellation (FR-CAN). Free text goes in the optional note. */
public enum CancelReason {
    CHANGED_MIND,
    WAIT_TOO_LONG,
    DRIVER_NOT_MOVING,
    CUSTOMER_NO_SHOW,
    WRONG_PICKUP,
    VEHICLE_ISSUE,
    SAFETY_CONCERN,
    OTHER
}
