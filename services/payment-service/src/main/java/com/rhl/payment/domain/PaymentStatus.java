package com.rhl.payment.domain;

/**
 * Lifecycle of a payment (README FR-PAY). A failed payment can be charged again; a captured one
 * (succeeded, possibly refunded since) can be refunded up to its amount.
 */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    /** Captured, with a refund sent to the provider and not settled yet. */
    REFUND_PENDING,
    PARTIALLY_REFUNDED,
    REFUNDED;

    /** The customer was charged: true whatever happened to refunds afterwards. */
    public boolean isCaptured() {
        return this == SUCCEEDED || this == REFUND_PENDING || this == PARTIALLY_REFUNDED || this == REFUNDED;
    }
}
