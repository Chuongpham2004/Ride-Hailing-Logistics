package com.rhl.trip.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.rhl.common.web.ApiException;
import com.rhl.common.web.CorrelationId;
import com.rhl.common.web.ErrorCode;
import com.rhl.trip.TripServiceProperties;
import com.rhl.trip.domain.FareSnapshot;
import com.rhl.trip.domain.ServiceType;
import com.rhl.trip.domain.Stop;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.UUID;

/**
 * Quote validation against pricing-service ({@code GET /internal/v1/quotes/{id}}, README §5.1).
 * Its answers are passed on to the customer: unknown quote 404, expired 422
 * {@code QUOTE_EXPIRED}; an unreachable pricing-service is a retryable 503. Both calls are
 * idempotent reads, retried once and guarded by a circuit breaker.
 */
@Component
public class PricingClient {

    private final RestClient rest;
    private final RemoteCalls calls;

    public PricingClient(RestClient.Builder builder, TripServiceProperties properties) {
        TripServiceProperties.Remote config = properties.pricing();
        this.calls = new RemoteCalls("pricing-service", properties.resilience());
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

    /** What trip-service copies from a quote when booking (BR-007). */
    public record Quote(UUID id, ServiceType serviceType, Stop pickup, Stop dropoff, FareSnapshot fare) {
    }

    /** The customer's quote, still valid; otherwise an {@link ApiException} to answer with. */
    public Quote validQuote(UUID quoteId, UUID customerId) {
        JsonNode body;
        try {
            body = calls.read(() -> rest.get()
                    .uri(uri -> uri.path("/internal/v1/quotes/{id}").queryParam("customerId", customerId)
                            .build(quoteId))
                    .retrieve()
                    .body(JsonNode.class));
        } catch (HttpClientErrorException e) {
            throw translate(e);
        } catch (RestClientException | CallNotPermittedException e) {
            throw unavailable();
        }
        JsonNode q = body == null ? null : body.path("data");
        if (q == null || q.isMissingNode()) {
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Pricing returned no quote");
        }
        JsonNode b = q.path("breakdown");
        return new Quote(UUID.fromString(q.path("id").asText()), ServiceType.valueOf(q.path("serviceType").asText()),
                stop(q.path("pickup")), stop(q.path("dropoff")),
                new FareSnapshot(UUID.fromString(q.path("id").asText()), q.path("total").asLong(),
                        q.path("currency").asText(), q.path("surgeMultiplier").decimalValue(),
                        q.path("ruleVersion").asInt(), q.path("distanceMeters").asInt(),
                        q.path("durationSeconds").asInt()));
    }

    /**
     * What cancelling now would cost (FR-CAN), decided by pricing-service with the rule in force.
     *
     * @param freeUntil until when the customer cancels for free, when that applies
     */
    public record CancellationFee(String decision, long fee, String currency, int ruleVersion, Instant freeUntil) {
    }

    public CancellationFee cancellationFee(ServiceType serviceType, String actorType, String status, String reason,
                                           Instant acceptedAt, Long bookedFare) {
        JsonNode body;
        try {
            body = calls.read(() -> rest.get()
                    .uri(uri -> {
                        uri.path("/internal/v1/cancellation-fees/preview")
                                .queryParam("serviceType", serviceType.name())
                                .queryParam("actorType", actorType)
                                .queryParam("status", status)
                                .queryParam("reason", reason);
                        if (acceptedAt != null) {
                            uri.queryParam("acceptedAt", acceptedAt.toString());
                        }
                        if (bookedFare != null) {
                            uri.queryParam("bookedFare", bookedFare);
                        }
                        return uri.build();
                    })
                    .retrieve()
                    .body(JsonNode.class));
        } catch (RestClientException | CallNotPermittedException e) {
            throw unavailable();
        }
        JsonNode f = body == null ? null : body.path("data");
        if (f == null || f.isMissingNode()) {
            throw unavailable();
        }
        return new CancellationFee(f.path("decision").asText(), f.path("fee").asLong(), f.path("currency").asText(),
                f.path("ruleVersion").asInt(), f.hasNonNull("freeUntil") ? Instant.parse(f.path("freeUntil").asText())
                : null);
    }

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Pricing is temporarily unavailable, try again");
    }

    private static ApiException translate(HttpClientErrorException e) {
        if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return ApiException.notFound("Quote");
        }
        JsonNode error = e.getResponseBodyAs(JsonNode.class);
        String code = error == null ? "" : error.path("code").asText();
        String message = error == null ? "The quote cannot be used" : error.path("message").asText();
        return ErrorCode.QUOTE_EXPIRED.name().equals(code)
                ? new ApiException(ErrorCode.QUOTE_EXPIRED, message)
                : ApiException.rule(message);
    }

    private static Stop stop(JsonNode node) {
        return new Stop(node.path("latitude").asDouble(), node.path("longitude").asDouble(),
                node.path("address").asText());
    }
}
