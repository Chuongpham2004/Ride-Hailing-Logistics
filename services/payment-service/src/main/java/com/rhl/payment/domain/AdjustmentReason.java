package com.rhl.payment.domain;

/** Why a driver's wallet is corrected (BR-011, BR-012). */
public enum AdjustmentReason {
    /** The driver's share of a refunded trip is taken back. */
    REFUND_CLAWBACK,
    /** An earning or commission was posted wrongly. */
    EARNING_CORRECTION,
    INCENTIVE,
    /** Requires a note. */
    OTHER
}
