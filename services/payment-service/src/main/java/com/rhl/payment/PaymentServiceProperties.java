package com.rhl.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Business parameters (NFR-MNT-005). */
@Validated
@ConfigurationProperties(prefix = "rhl")
public record PaymentServiceProperties(@Valid @NotNull Charge charge, @Valid @NotNull Provider provider,
                                       @Valid @NotNull Kafka kafka) {

    /**
     * @param resolveAfter    an attempt still unresolved this long after it started is sent again
     *                        (same idempotency key)
     * @param callbackTimeout an attempt the provider accepted is asked again only after this long
     *                        without a callback
     * @param resolveEvery    how often unresolved attempts are looked for
     * @param resolveBatch    attempts handled per run
     * @param maxAttempts     attempts per payment, the first included; customers retry up to it
     */
    public record Charge(@NotNull Duration resolveAfter, @NotNull Duration callbackTimeout,
                         @NotNull Duration resolveEvery, @Min(1) int resolveBatch, @Min(1) int maxAttempts) {
    }

    /**
     * Provider webhooks (FR-PAY): HMAC-SHA256 over {@code timestamp.body} with a shared secret,
     * accepted only within {@code signatureTolerance} of the signing time (replay protection,
     * together with the unique provider event ID).
     */
    public record Provider(@NotBlank @Size(min = 16) String webhookSecret, @NotNull Duration signatureTolerance) {
    }

    public record Kafka(@Min(1) int partitions, @Min(1) short replicas) {
    }
}
