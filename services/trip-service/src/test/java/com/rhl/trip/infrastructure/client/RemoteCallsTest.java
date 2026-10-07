package com.rhl.trip.infrastructure.client;

import com.rhl.common.web.ApiException;
import com.rhl.common.web.ErrorCode;
import com.rhl.trip.TripServiceProperties;
import com.rhl.trip.domain.ServiceType;
import com.rhl.trip.domain.Stop;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** README §8.5: bounded retry on transient failures, fail fast while the remote is down. */
class RemoteCallsTest {

    private static final Stop PICKUP = new Stop(10.77, 106.69, "Q1");

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 500;
    private volatile String body = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void transientFailuresAreRetriedOnceThenTheCircuitOpens() {
        LocationClient location = new LocationClient(RestClient.builder(), properties());

        // Each call: 2 attempts (first + one retry).
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> location.nearby(ServiceType.RIDE, PICKUP, 2000, 10))
                    .isInstanceOf(LocationClient.LocationUnavailableException.class);
        }
        assertThat(hits.get()).isEqualTo(4);

        // 4 failures out of 4 calls: open. Calls now fail without reaching the server.
        assertThatThrownBy(() -> location.nearby(ServiceType.RIDE, PICKUP, 2000, 10))
                .isInstanceOf(LocationClient.LocationUnavailableException.class)
                .hasMessageContaining("CircuitBreaker");
        assertThat(hits.get()).isEqualTo(4);
    }

    @Test
    void aClientErrorIsTheAnswerNotAFailure() {
        status = 404;
        body = "{\"code\":\"RESOURCE_NOT_FOUND\"}";
        PricingClient pricing = new PricingClient(RestClient.builder(), properties());

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> pricing.validQuote(UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        }
        // Not retried, and never opens the circuit.
        assertThat(hits.get()).isEqualTo(6);
    }

    @Test
    void pricingDownIsARetryable503() {
        PricingClient pricing = new PricingClient(RestClient.builder(), properties());
        assertThatThrownBy(() -> pricing.cancellationFee(ServiceType.RIDE, "CUSTOMER", "ACCEPTED", "CHANGED_MIND",
                null, 27_000L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void theBreakerOnlyCountsNetworkErrorsAnd5xx() {
        RemoteCalls calls = new RemoteCalls("test", properties().resilience());
        assertThat(calls.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(RemoteCalls.isTransient(new org.springframework.web.client.ResourceAccessException("down")))
                .isTrue();
        assertThat(RemoteCalls.isTransient(new IllegalStateException())).isFalse();
    }

    private TripServiceProperties properties() {
        String url = "http://localhost:" + server.getAddress().getPort();
        TripServiceProperties.Remote remote = new TripServiceProperties.Remote(url, Duration.ofMillis(500),
                Duration.ofSeconds(1));
        return new TripServiceProperties(
                new TripServiceProperties.Matching(2000, 1000, 8000, Duration.ofSeconds(15), Duration.ofSeconds(30),
                        10, Duration.ofSeconds(5), Duration.ofSeconds(1), 50),
                new TripServiceProperties.Codes(Set.of(ServiceType.RIDE), 4, 5),
                new TripServiceProperties.Delivery(20_000),
                new TripServiceProperties.Resilience(50, 4, 4, Duration.ofMinutes(1), 2, Duration.ofMillis(10)),
                remote, remote, new TripServiceProperties.Kafka(3, (short) 1));
    }
}
