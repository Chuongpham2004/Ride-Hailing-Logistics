package com.rhl.pricing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
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
}
