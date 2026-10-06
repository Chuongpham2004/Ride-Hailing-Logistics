package com.rhl.payment.domain;

/** Lifecycle of a payment and of each provider attempt. A failed payment can be charged again. */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
