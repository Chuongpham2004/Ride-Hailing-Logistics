package com.rhl.pricing.domain;

/**
 * Distance and travel time between pickup and drop-off.
 *
 * @param source   where the numbers come from: {@code OSRM} for a road route, {@code ESTIMATE}
 *                 for the straight-line fallback
 * @param polyline the road route as an encoded polyline with precision 6, or {@code null} when
 *                 there is no road route (the estimate)
 */
public record RouteEstimate(int distanceMeters, int durationSeconds, String source, String polyline) {

    public RouteEstimate {
        if (distanceMeters <= 0 || durationSeconds <= 0) {
            throw new IllegalArgumentException("A route has a positive distance and duration");
        }
    }

    /** A route without geometry. */
    public RouteEstimate(int distanceMeters, int durationSeconds, String source) {
        this(distanceMeters, durationSeconds, source, null);
    }
}
