package com.rhl.trip.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.rhl.common.web.CorrelationId;
import com.rhl.trip.TripServiceProperties;
import com.rhl.trip.domain.DriverCandidate;
import com.rhl.trip.domain.ServiceType;
import com.rhl.trip.domain.Stop;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Nearby AVAILABLE drivers from location-service ({@code GET /internal/v1/drivers/nearby},
 * README §8.5). Short timeouts, one bounded retry and a circuit breaker: while location-service
 * is failing, dispatch rounds fail at once and are tried again on a later dispatcher tick.
 */
@Component
public class LocationClient {

    private final RestClient rest;
    private final RemoteCalls calls;

    public LocationClient(RestClient.Builder builder, TripServiceProperties properties) {
        TripServiceProperties.Remote config = properties.location();
        this.calls = new RemoteCalls("location-service", properties.resilience());
        HttpClient http = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(config.readTimeout());
        this.rest = builder.baseUrl(config.baseUrl())
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = CorrelationId.current();
                    if (correlationId != null) {
                        request.getHeaders().set(CorrelationId.HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    /** @throws LocationUnavailableException when location-service cannot be reached or answers with an error */
    public List<DriverCandidate> nearby(ServiceType serviceType, Stop pickup, int radiusMeters, int limit) {
        JsonNode response;
        try {
            response = calls.read(() -> rest.get()
                    .uri(uri -> uri.path("/internal/v1/drivers/nearby")
                            .queryParam("latitude", pickup.latitude())
                            .queryParam("longitude", pickup.longitude())
                            .queryParam("serviceType", serviceType.name())
                            .queryParam("radiusMeters", radiusMeters)
                            .queryParam("limit", limit)
                            .build())
                    .retrieve()
                    .body(JsonNode.class));
        } catch (RestClientException | CallNotPermittedException e) {
            throw new LocationUnavailableException(e);
        }
        List<DriverCandidate> candidates = new ArrayList<>();
        if (response != null) {
            for (JsonNode driver : response.path("data")) {
                candidates.add(new DriverCandidate(UUID.fromString(driver.path("driverId").asText()),
                        driver.path("distanceMeters").asLong(), driver.path("locationAgeMillis").asLong()));
            }
        }
        return candidates;
    }

    public static class LocationUnavailableException extends RuntimeException {

        LocationUnavailableException(Throwable cause) {
            super("location-service unavailable: " + cause.getMessage(), cause);
        }
    }
}
