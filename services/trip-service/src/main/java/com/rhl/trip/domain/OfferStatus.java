package com.rhl.trip.domain;

public enum OfferStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    EXPIRED,
    /** Withdrawn because the trip was cancelled or ended while the offer was open. */
    CANCELLED
}
