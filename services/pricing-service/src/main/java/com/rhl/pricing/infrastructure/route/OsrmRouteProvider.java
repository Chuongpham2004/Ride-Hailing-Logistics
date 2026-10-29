package com.rhl.pricing.infrastructure.route;

import com.rhl.pricing.application.RouteProvider;
import com.rhl.pricing.domain.RouteEstimate;
import com.rhl.pricing.domain.ServiceType;
import com.rhl.pricing.domain.Stop;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * Road route from an OSRM server ({@code GET /route/v1/{profile}/{lng},{lat};{lng},{lat}}).
 * Both service types use the same profile. Throws {@link NoRouteException} when OSRM answers
 * without a usable route, and lets {@link org.springframework.web.client.RestClientException}
 * through when it cannot be reached.
 */
public class OsrmRouteProvider implements RouteProvider {

    public static final String SOURCE = "OSRM";

    private final RestClient rest;
    private final String profile;

    public OsrmRouteProvider(RestClient rest, String profile) {
        this.rest = rest;
        this.profile = profile;
    }

    @Override
    public RouteEstimate route(ServiceType serviceType, Stop pickup, Stop dropoff) {
        // Coordinates go into the template itself: as a URI variable, ';' and ',' would be
        // percent-encoded, and OSRM only splits on the literal characters.
        String coordinates = point(pickup) + ";" + point(dropoff);
        JsonNode response = rest.get()
                .uri("/route/v1/{profile}/" + coordinates + "?overview=simplified&geometries=polyline6&steps=false",
                        profile)
                .retrieve()
                .body(JsonNode.class);
        if (response == null || !"Ok".equals(response.path("code").asString())) {
            throw new NoRouteException(response == null ? "empty body" : response.path("code").asString());
        }
        JsonNode route = response.path("routes").path(0);
        long distance = Math.round(route.path("distance").asDouble());
        long duration = Math.round(route.path("duration").asDouble());
        String geometry = route.path("geometry").asString();
        if (distance <= 0 || duration <= 0 || geometry.isEmpty()) {
            throw new NoRouteException("route without distance, duration or geometry");
        }
        return new RouteEstimate((int) distance, (int) duration, SOURCE, geometry);
    }

    /** OSRM takes longitude first. */
    private static String point(Stop stop) {
        return String.format(Locale.ROOT, "%.6f,%.6f", stop.longitude(), stop.latitude());
    }

    public static class NoRouteException extends RuntimeException {

        NoRouteException(String reason) {
            super("OSRM returned no usable route: " + reason);
        }
    }
}
