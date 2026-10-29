package com.rhl.pricing.infrastructure.route;

import com.rhl.pricing.PricingServiceProperties;
import com.rhl.pricing.domain.RouteEstimate;
import com.rhl.pricing.domain.ServiceType;
import com.rhl.pricing.domain.Stop;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** TBD-02: road routes from OSRM, and the estimate whenever OSRM cannot give a usable one. */
class FallbackRouteProviderTest {

    // Ben Thanh market -> University of Science, District 5: about 2.0 km as the crow flies.
    private static final Stop PICKUP = new Stop(10.7725, 106.6980, "Ben Thanh");
    private static final Stop DROPOFF = new Stop(10.7626, 106.6822, "DH KHTN");
    private static final String OK = """
            {"code":"Ok","routes":[{"distance":2841.7,"duration":412.3,"geometry":"abc_polyline"}]}""";

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastAcceptEncoding = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = OK;
    private volatile long delayMillis;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            lastPath.set(exchange.getRequestURI().getRawPath());
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            // Like router.project-osrm.org: compresses whenever asked, and labels a gzip body
            // "deflate" when the client accepts deflate.
            String accepts = exchange.getRequestHeaders().getFirst("Accept-Encoding");
            lastAcceptEncoding.set(accepts);
            if (accepts != null && (accepts.contains("gzip") || accepts.contains("deflate"))) {
                bytes = gzip(bytes);
                exchange.getResponseHeaders().add("Content-Encoding", accepts.contains("deflate") ? "deflate" : "gzip");
            }
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

    private static byte[] gzip(byte[] plain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(plain);
        }
        return out.toByteArray();
    }

    @Test
    void usesTheRoadRouteFromOsrm() {
        RouteEstimate route = provider(true).route(ServiceType.RIDE, PICKUP, DROPOFF);

        assertThat(route.source()).isEqualTo("OSRM");
        assertThat(route.distanceMeters()).isEqualTo(2842);
        assertThat(route.durationSeconds()).isEqualTo(412);
        assertThat(route.polyline()).isEqualTo("abc_polyline");
        // Longitude first, separators sent literally.
        assertThat(lastPath.get()).isEqualTo("/route/v1/driving/106.698000,10.772500;106.682200,10.762600");
    }

    @Test
    void asksForAnUncompressedBodySoAMislabelledOneCannotBreakIt() {
        RouteEstimate route = provider(true).route(ServiceType.RIDE, PICKUP, DROPOFF);

        assertThat(lastAcceptEncoding.get()).isEqualTo("identity");
        assertThat(route.source()).isEqualTo("OSRM");
    }

    @Test
    void disabledNeverCallsOsrm() {
        RouteEstimate route = provider(false).route(ServiceType.RIDE, PICKUP, DROPOFF);

        assertThat(route.source()).isEqualTo("ESTIMATE");
        assertThat(route.polyline()).isNull();
        assertThat(hits).hasValue(0);
    }

    @Test
    void noRouteFallsBackWithoutCountingAgainstTheCircuit() {
        status = 400;
        body = """
                {"code":"NoRoute","message":"Impossible route between points"}""";
        FallbackRouteProvider provider = provider(true);

        for (int i = 0; i < 6; i++) {
            assertThat(provider.route(ServiceType.RIDE, PICKUP, DROPOFF).source()).isEqualTo("ESTIMATE");
        }
        assertThat(provider.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(hits).hasValue(6);
    }

    @Test
    void okWithoutRoutesFallsBack() {
        body = """
                {"code":"Ok","routes":[]}""";

        assertThat(provider(true).route(ServiceType.RIDE, PICKUP, DROPOFF).source()).isEqualTo("ESTIMATE");
    }

    @Test
    void distrustsARouteFarLongerThanTheStraightLine() {
        body = """
                {"code":"Ok","routes":[{"distance":40000,"duration":3000,"geometry":"x"}]}""";

        assertThat(provider(true).route(ServiceType.RIDE, PICKUP, DROPOFF).source()).isEqualTo("ESTIMATE");
    }

    @Test
    void slowServerFallsBack() {
        delayMillis = 1_000;

        assertThat(provider(true).route(ServiceType.RIDE, PICKUP, DROPOFF).source()).isEqualTo("ESTIMATE");
    }

    @Test
    void serverErrorsOpenTheCircuitAndOsrmIsSkipped() {
        status = 500;
        FallbackRouteProvider provider = provider(true);

        for (int i = 0; i < 3; i++) {
            assertThat(provider.route(ServiceType.RIDE, PICKUP, DROPOFF).source()).isEqualTo("ESTIMATE");
        }
        assertThat(provider.state()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThat(provider.route(ServiceType.RIDE, PICKUP, DROPOFF).source()).isEqualTo("ESTIMATE");
        assertThat(hits).hasValue(3);
    }

    private FallbackRouteProvider provider(boolean enabled) {
        PricingServiceProperties properties = new PricingServiceProperties(
                new PricingServiceProperties.Quote(Duration.ofMinutes(5), 1_000, 200, 100_000),
                new PricingServiceProperties.Route(1.35, 22, 60, new PricingServiceProperties.Osrm(enabled,
                        "http://localhost:" + server.getAddress().getPort(), "driving", Duration.ofMillis(500),
                        Duration.ofMillis(300), 4.0, 50, 3, 3, Duration.ofMinutes(1))),
                new PricingServiceProperties.Surge(8, 1, Duration.ofMinutes(5), Duration.ofSeconds(60)),
                new PricingServiceProperties.Kafka(3, (short) 1, 1), "DEFAULT");
        return new FallbackRouteProvider(new EstimatedRouteProvider(properties), properties);
    }
}
