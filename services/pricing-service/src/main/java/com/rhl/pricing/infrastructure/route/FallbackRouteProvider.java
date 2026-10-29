package com.rhl.pricing.infrastructure.route;

import com.rhl.pricing.PricingServiceProperties;
import com.rhl.pricing.application.RouteProvider;
import com.rhl.pricing.domain.GeoDistance;
import com.rhl.pricing.domain.RouteEstimate;
import com.rhl.pricing.domain.ServiceType;
import com.rhl.pricing.domain.Stop;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;

/**
 * The route every quote uses: OSRM when enabled, otherwise or on any OSRM failure the
 * straight-line estimate, so a quote is never refused because the map server is down. No retry,
 * since a customer is waiting on the quote; a circuit breaker skips OSRM for a while once it keeps
 * failing. A road route much longer than the straight line is distrusted (a snapped point on the
 * wrong side of a river, say) and the estimate is used instead.
 */
@Slf4j
@Primary
@Component
public class FallbackRouteProvider implements RouteProvider {

    private final EstimatedRouteProvider estimate;
    private final RouteProvider osrm;
    private final CircuitBreaker breaker;
    private final double maxDetourFactor;

    @Autowired
    public FallbackRouteProvider(EstimatedRouteProvider estimate, PricingServiceProperties properties) {
        this(estimate, properties.route().osrm(), osrmProvider(properties.route().osrm()));
    }

    FallbackRouteProvider(EstimatedRouteProvider estimate, PricingServiceProperties.Osrm config, RouteProvider osrm) {
        this.estimate = estimate;
        this.osrm = osrm;
        this.maxDetourFactor = config.maxDetourFactor();
        this.breaker = CircuitBreaker.of("osrm", CircuitBreakerConfig.custom()
                .failureRateThreshold(config.failureRateThreshold())
                .slidingWindowSize(config.slidingWindowSize())
                .minimumNumberOfCalls(config.minimumCalls())
                .waitDurationInOpenState(config.openFor())
                .recordException(FallbackRouteProvider::isTransient)
                .build());
        breaker.getEventPublisher().onStateTransition(event ->
                log.warn("Circuit to OSRM is now {}", event.getStateTransition().getToState()));
    }

    @Override
    public RouteEstimate route(ServiceType serviceType, Stop pickup, Stop dropoff) {
        if (osrm == null) {
            return estimate.route(serviceType, pickup, dropoff);
        }
        try {
            RouteEstimate road = breaker.executeSupplier(() -> osrm.route(serviceType, pickup, dropoff));
            double straight = GeoDistance.meters(pickup, dropoff);
            if (road.distanceMeters() > Math.max(straight, 1) * maxDetourFactor) {
                log.warn("OSRM route of {} m is over {}x the straight line of {} m; using the estimate",
                        road.distanceMeters(), maxDetourFactor, Math.round(straight));
                return estimate.route(serviceType, pickup, dropoff);
            }
            return road;
        } catch (CallNotPermittedException e) {
            return estimate.route(serviceType, pickup, dropoff);
        } catch (RestClientException | OsrmRouteProvider.NoRouteException e) {
            log.warn("OSRM failed, using the estimate: {}", describe(e));
            return estimate.route(serviceType, pickup, dropoff);
        }
    }

    /** Never the message of an I/O error: it holds the request URL, i.e. exact coordinates (NFR-SEC-009). */
    private static String describe(RuntimeException error) {
        if (error instanceof RestClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        if (error instanceof OsrmRouteProvider.NoRouteException) {
            return error.getMessage();
        }
        return error.getClass().getSimpleName();
    }

    CircuitBreaker.State state() {
        return breaker.getState();
    }

    /** Only an unreachable or failing server counts against the circuit; a 4xx is an answer. */
    static boolean isTransient(Throwable error) {
        return error instanceof ResourceAccessException || error instanceof HttpServerErrorException;
    }

    private static RouteProvider osrmProvider(PricingServiceProperties.Osrm config) {
        if (!config.enabled()) {
            return null;
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(config.readTimeout());
        // The public demo server asks clients to identify themselves.
        RestClient rest = RestClient.builder().baseUrl(config.baseUrl()).requestFactory(factory)
                .defaultHeader("User-Agent", "rhl-pricing-service (learning project)")
                .build();
        log.info("Routes from OSRM at {}", config.baseUrl());
        return new OsrmRouteProvider(rest, config.profile());
    }
}
