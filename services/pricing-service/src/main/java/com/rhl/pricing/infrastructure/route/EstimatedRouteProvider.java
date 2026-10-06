package com.rhl.pricing.infrastructure.route;

import com.rhl.pricing.PricingServiceProperties;
import com.rhl.pricing.application.RouteProvider;
import com.rhl.pricing.domain.GeoDistance;
import com.rhl.pricing.domain.RouteEstimate;
import com.rhl.pricing.domain.ServiceType;
import com.rhl.pricing.domain.Stop;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Fallback route used until a Map Provider is chosen (TBD-02): straight-line distance times a
 * road factor, at an average speed. Deterministic, so the same points always price the same.
 * Quotes record {@code ESTIMATE} as their route source, so they can be told apart later.
 */
@Component
@RequiredArgsConstructor
public class EstimatedRouteProvider implements RouteProvider {

    public static final String SOURCE = "ESTIMATE";

    private final PricingServiceProperties properties;

    @Override
    public RouteEstimate route(ServiceType serviceType, Stop pickup, Stop dropoff) {
        PricingServiceProperties.Route config = properties.route();
        double road = GeoDistance.meters(pickup, dropoff) * config.roadFactor();
        int distance = (int) Math.max(1, Math.round(road));
        double metersPerSecond = config.averageSpeedKmh() * 1000 / 3600;
        int duration = (int) Math.max(config.minDurationSeconds(), Math.round(distance / metersPerSecond));
        return new RouteEstimate(distance, duration, SOURCE);
    }
}
