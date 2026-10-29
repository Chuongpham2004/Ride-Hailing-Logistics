package com.rhl.pricing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Business parameters (NFR-MNT-005); placeholders until TBD-03 and TBD-05 are settled. */
@Validated
@ConfigurationProperties(prefix = "rhl")
public record PricingServiceProperties(@Valid @NotNull Quote quote, @Valid @NotNull Route route,
                                       @Valid @NotNull Surge surge, @Valid @NotNull Kafka kafka,
                                       @NotBlank String regionCode) {

    /**
     * @param ttl                how long a quote can be used to create a trip (TBD-05)
     * @param roundingStep       totals are rounded up to a multiple of this, in VND (TBD-03)
     * @param minDistanceMeters  shorter trips are refused
     * @param maxDistanceMeters  longer trips are out of v1.0 scope (no long-haul transport)
     */
    public record Quote(@NotNull Duration ttl, @Min(1) long roundingStep, @Positive int minDistanceMeters,
                        @Positive int maxDistanceMeters) {
    }

    /**
     * Routes come from OSRM when it is enabled (TBD-02), with a straight-line estimate as the
     * fallback.
     *
     * @param roadFactor       estimate: road distance ÷ straight-line distance in town
     * @param averageSpeedKmh  estimate: door-to-door average including stops
     */
    public record Route(@DecimalMin("1.0") double roadFactor, @Positive double averageSpeedKmh,
                        @Positive int minDurationSeconds, @Valid @NotNull Osrm osrm) {
    }

    /**
     * Self-hosted OSRM (docker compose profile {@code map}). Any failure falls back to the
     * estimate, so a quote is never refused because OSRM is down.
     *
     * @param profile              OSRM profile in the URL; the dev image is built with the car profile
     * @param maxDetourFactor      a road route longer than this × the straight line is distrusted
     * @param failureRateThreshold percentage of failed calls that opens the circuit
     * @param openFor              how long OSRM is skipped once the circuit opens
     */
    public record Osrm(boolean enabled, @NotBlank String baseUrl, @NotBlank String profile,
                       @NotNull Duration connectTimeout, @NotNull Duration readTimeout,
                       @DecimalMin("1.0") double maxDetourFactor, @Min(1) @Max(100) int failureRateThreshold,
                       @Min(1) int slidingWindowSize, @Min(1) int minimumCalls, @NotNull Duration openFor) {
    }

    /**
     * Where and over what time supply and demand are counted (README §4.6; TBD-04). The formula
     * itself is versioned in {@code surge_rules}.
     *
     * @param h3Resolution    cell size; 8 is roughly 0.7 km² per cell
     * @param ringSize        neighbouring rings counted with the pickup cell, to smooth borders
     * @param demandWindow    trip requests counted over this sliding window
     * @param supplyFreshness drivers count as supply only with a position this recent
     */
    public record Surge(@Min(0) @Max(15) int h3Resolution, @Min(0) @Max(3) int ringSize,
                        @NotNull Duration demandWindow, @NotNull Duration supplyFreshness) {
    }

    public record Kafka(@Min(1) int partitions, @Min(1) short replicas, @Min(1) int locationConcurrency) {
    }
}
