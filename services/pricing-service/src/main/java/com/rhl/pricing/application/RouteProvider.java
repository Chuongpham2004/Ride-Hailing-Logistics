package com.rhl.pricing.application;

import com.rhl.pricing.domain.RouteEstimate;
import com.rhl.pricing.domain.ServiceType;
import com.rhl.pricing.domain.Stop;

/**
 * Distance and travel time for a quote (FR-PRI, Map Provider in README §8.5). A provider that
 * cannot answer throws, and no quote is issued: a price is never made up from a missing route.
 */
public interface RouteProvider {

    RouteEstimate route(ServiceType serviceType, Stop pickup, Stop dropoff);
}
