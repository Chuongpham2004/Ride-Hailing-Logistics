package com.rhl.pricing.domain;

/**
 * Distance and travel time between pickup and drop-off.
 *
 * @param source where the numbers come from: {@code ESTIMATE} until a Map Provider is chosen
 *               (TBD-02), later the provider's name
 */
public record RouteEstimate(int distanceMeters, int durationSeconds, String source) {

    public RouteEstimate {
        if (distanceMeters <= 0 || durationSeconds <= 0) {
            throw new IllegalArgumentException("A route has a positive distance and duration");
        }
    }
}
