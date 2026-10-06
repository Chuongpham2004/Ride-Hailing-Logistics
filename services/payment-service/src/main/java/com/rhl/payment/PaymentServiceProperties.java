package com.rhl.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Business parameters (NFR-MNT-005). */
@Validated
@ConfigurationProperties(prefix = "rhl")
public record PaymentServiceProperties(@Valid @NotNull Charge charge, @Valid @NotNull Kafka kafka) {

    /**
     * @param resolveAfter   an attempt still unresolved this long after it started is sent again
     *                       (same idempotency key)
     * @param resolveEvery   how often unresolved attempts are looked for
     * @param resolveBatch   attempts handled per run
     */
    public record Charge(@NotNull Duration resolveAfter, @NotNull Duration resolveEvery, @Min(1) int resolveBatch) {
    }

    public record Kafka(@Min(1) int partitions, @Min(1) short replicas) {
    }
}
