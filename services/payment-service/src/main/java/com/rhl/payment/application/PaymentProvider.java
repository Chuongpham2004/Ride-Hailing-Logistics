package com.rhl.payment.application;

import java.util.UUID;

/**
 * The external payment provider (README §8.5; provider choice is TBD-09). Implementations must
 * treat {@code idempotencyKey} as the provider's own idempotency key: sending the same key twice
 * returns the first outcome and never charges again.
 */
public interface PaymentProvider {

    String name();

    /**
     * @return the provider's decision, or {@code PENDING} when the outcome will arrive later as a
     *         signed callback; a decline is a normal outcome, not an exception
     * @throws ProviderUnavailableException when the outcome is unknown (timeout, network, 5xx);
     *                                      the same attempt is sent again later with the same key
     */
    ChargeResult charge(ChargeRequest request);

    /**
     * Gives (part of) a captured charge back, with the same idempotency and outcome rules as
     * {@link #charge}; a refusal is {@code DECLINED} with the provider's reason.
     *
     * @throws ProviderUnavailableException when the outcome is unknown; sent again with the same key
     */
    ChargeResult refund(RefundRequest request);

    /** No card data: the provider resolves the customer's stored method from its own token (CON-08). */
    record ChargeRequest(String idempotencyKey, UUID customerId, long amount, String currency, String description) {
    }

    /** @param chargeReference the provider's ID of the charge being refunded */
    record RefundRequest(String idempotencyKey, String chargeReference, long amount, String currency, String reason) {
    }

    enum Outcome {
        SUCCEEDED,
        DECLINED,
        /** Accepted by the provider; the final outcome comes later through a signed callback. */
        PENDING
    }

    /**
     * The outcome of a charge or a refund.
     *
     * @param reference   the provider's ID for the charge or refund; set when succeeded or pending
     * @param failureCode the provider's reason; set when declined
     */
    record ChargeResult(Outcome outcome, String reference, String failureCode) {

        public static ChargeResult success(String reference) {
            return new ChargeResult(Outcome.SUCCEEDED, reference, null);
        }

        public static ChargeResult declined(String failureCode) {
            return new ChargeResult(Outcome.DECLINED, null, failureCode);
        }

        public static ChargeResult pending(String reference) {
            return new ChargeResult(Outcome.PENDING, reference, null);
        }
    }

    class ProviderUnavailableException extends RuntimeException {

        public ProviderUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
