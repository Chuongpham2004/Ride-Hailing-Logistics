package com.rhl.trip.domain;

/** The two handover codes of a trip (README §6). */
public enum HandoverCode {
    /** The customer reads it to the driver to start the trip. */
    PICKUP,
    /** The recipient reads it to the driver to complete a delivery. */
    DELIVERY
}
