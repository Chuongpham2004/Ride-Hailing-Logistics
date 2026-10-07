package com.rhl.payment.domain;

/** A refund is sent to the provider once; a failed one stays failed and a new refund is requested instead. */
public enum RefundStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
