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

/** Business parameters (NFR-MNT-005); placeholders until TBD-02, TBD-03 and TBD-05 are settled. */
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
     * Straight-line estimate used until a Map Provider is chosen (TBD-02).
     *
     * @param roadFactor       road distance ÷ straight-line distance in town
     * @param averageSpeedKmh  door-to-door average including stops
     */
    public record Route(@DecimalMin("1.0") double roadFactor, @Positive double averageSpeedKmh,
                        @Positive int minDurationSeconds) {
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
