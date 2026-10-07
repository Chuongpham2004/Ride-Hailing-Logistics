package com.rhl.trip;

import com.rhl.trip.domain.MatchingPolicy;
import com.rhl.trip.domain.ServiceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Set;

/** Business thresholds (NFR-MNT-005); matching values are placeholders until TBD-06 is settled. */
@Validated
@ConfigurationProperties(prefix = "rhl")
public record TripServiceProperties(@Valid @NotNull Matching matching, @Valid @NotNull Codes codes,
                                    @Valid @NotNull Delivery delivery, @Valid @NotNull Resilience resilience,
                                    @Valid @NotNull Remote location, @Valid @NotNull Remote pricing,
                                    @Valid @NotNull Kafka kafka) {

    /**
     * Circuit breaker and retry applied to each remote service (README §8.5).
     *
     * @param failureRateThreshold percentage of failed calls that opens the circuit
     * @param slidingWindowSize    calls the failure rate is measured over
     * @param minimumCalls         calls needed before the rate counts
     * @param openFor              how long calls fail fast before a few are let through again
     * @param maxAttempts          attempts per call, the first included (idempotent GETs only)
     */
    public record Resilience(@Min(1) @Max(100) int failureRateThreshold, @Min(1) int slidingWindowSize,
                             @Min(1) int minimumCalls, @NotNull Duration openFor, @Min(1) @Max(5) int maxAttempts,
                             @NotNull Duration retryWait) {
    }

    /**
     * Handover codes (README §6: start needs confirmation/OTP if enabled; DELIVERY completion
     * needs proof).
     *
     * @param pickupRequired services whose trips need the pickup code to start
     * @param length         digits per code
     * @param maxAttempts    wrong entries per code before it is locked (support takes over)
     */
    public record Codes(@NotNull Set<ServiceType> pickupRequired, @Min(4) @Max(8) int length,
                        @Min(1) int maxAttempts) {
    }

    /** @param maxWeightGrams heaviest package accepted (TBD-08 placeholder) */
    public record Delivery(@Positive int maxWeightGrams) {
    }

    /**
     * @param candidateLimit drivers asked from location-service per round
     * @param holdGrace      extra lifetime of the Redis hold beyond the offer timeout
     * @param tickInterval   how often expired offers and waiting trips are processed
     * @param batchSize      offers or trips handled per tick
     */
    public record Matching(@Positive int initialRadiusMeters, @Positive int radiusStepMeters,
                           @Positive int maxRadiusMeters, @NotNull Duration offerTimeout,
                           @NotNull Duration matchingTimeout, @Positive int candidateLimit,
                           @NotNull Duration holdGrace, @NotNull Duration tickInterval, @Positive int batchSize) {

        public MatchingPolicy policy() {
            return new MatchingPolicy(initialRadiusMeters, radiusStepMeters, maxRadiusMeters, offerTimeout,
                    matchingTimeout);
        }

        public Duration holdTtl() {
            return offerTimeout.plus(holdGrace);
        }
    }

    /** A service called over REST, with short timeouts (README §8.5). */
    public record Remote(@NotBlank String baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
    }

    public record Kafka(@Min(1) int partitions, @Min(1) short replicas) {
    }
}
