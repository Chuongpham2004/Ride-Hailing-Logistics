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
     * @return the provider's decision; a decline is a normal outcome, not an exception
     * @throws ProviderUnavailableException when the outcome is unknown (timeout, network, 5xx);
     *                                      the same attempt is sent again later with the same key
     */
    ChargeResult charge(ChargeRequest request);

    /** No card data: the provider resolves the customer's stored method from its own token (CON-08). */
    record ChargeRequest(String idempotencyKey, UUID customerId, long amount, String currency, String description) {
    }

    /**
     * @param reference   the provider's ID for the charge; set when {@code succeeded}
     * @param failureCode the provider's reason; set when declined
     */
    record ChargeResult(boolean succeeded, String reference, String failureCode) {

        public static ChargeResult success(String reference) {
            return new ChargeResult(true, reference, null);
        }

        public static ChargeResult declined(String failureCode) {
            return new ChargeResult(false, null, failureCode);
        }
    }

    class ProviderUnavailableException extends RuntimeException {

        public ProviderUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
