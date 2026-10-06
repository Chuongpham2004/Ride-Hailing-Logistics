package com.rhl.trip.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Dispatch parameters (README §4.11, §5.2). Placeholders until TBD-06 settles radius, rounds,
 * offer timeout and ranking.
 */
public record MatchingPolicy(int initialRadiusMeters, int radiusStepMeters, int maxRadiusMeters,
                             Duration offerTimeout, Duration matchingTimeout) {

    public MatchingPolicy {
        Objects.requireNonNull(offerTimeout, "offerTimeout");
        Objects.requireNonNull(matchingTimeout, "matchingTimeout");
        if (initialRadiusMeters <= 0 || radiusStepMeters <= 0 || maxRadiusMeters < initialRadiusMeters) {
            throw new IllegalArgumentException("Radii must be positive and max >= initial");
        }
        if (offerTimeout.isNegative() || offerTimeout.isZero() || matchingTimeout.compareTo(offerTimeout) < 0) {
            throw new IllegalArgumentException("Offer timeout must be positive and not longer than matching");
        }
    }

    public int nextRadius(int current) {
        return Math.min(current + radiusStepMeters, maxRadiusMeters);
    }
}
