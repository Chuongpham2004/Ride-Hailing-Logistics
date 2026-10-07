package com.rhl.payment.domain;

/** Why a customer is refunded (FR-PAY: a refund always has a reason). */
public enum RefundReason {
    /** The trip or delivery did not happen as booked. */
    SERVICE_NOT_PROVIDED,
    /** The customer paid more than they should have. */
    OVERCHARGE,
    DUPLICATE_CHARGE,
    /** Complaint upheld: quality, safety, lost item handling. */
    SERVICE_COMPLAINT,
    GOODWILL,
    /** Requires a note. */
    OTHER
}
