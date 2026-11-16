package com.rhl.trip.infrastructure.client;

import com.rhl.trip.TripServiceProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Client credentials grant against user-service's token endpoint (README §8.5). */
class ClientCredentialsTokenSourceTest {

    private HttpServer server;
    private final AtomicInteger issued = new AtomicInteger();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile long expiresIn = 600;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T08:00:00Z"));

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/oauth2/token", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestHeaders().getFirst("Authorization") + " | " + form);
            String answer = status == 200
                    ? "{\"access_token\":\"t" + issued.incrementAndGet() + "\",\"token_type\":\"Bearer\","
                    + "\"expires_in\":" + expiresIn + "}"
                    : "{\"error\":\"invalid_client\"}";
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
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
    void asksWithBasicCredentialsAndReusesTheTokenUntilShortlyBeforeItExpires() throws IOException {
        ClientCredentialsTokenSource source = source();

        assertThat(source.token()).isEqualTo("t1");
        clock.advance(Duration.ofSeconds(539));
        assertThat(source.token()).isEqualTo("t1");
        // 60 s (refresh-before) ahead of the 600 s lifetime: renewed.
        clock.advance(Duration.ofSeconds(1));
        assertThat(source.token()).isEqualTo("t2");

        String basic = Base64.getEncoder().encodeToString("trip-service:dev-trip-service-secret"
                .getBytes(StandardCharsets.UTF_8));
        assertThat(requests.getFirst()).isEqualTo("Basic " + basic
                + " | grant_type=client_credentials&scope=internal%3Alocation+internal%3Apricing");
    }

    @Test
    void aShortLivedTokenIsRenewedHalfWay() throws IOException {
        expiresIn = 60;
        ClientCredentialsTokenSource source = source();

        assertThat(source.token()).isEqualTo("t1");
        clock.advance(Duration.ofSeconds(29));
        assertThat(source.token()).isEqualTo("t1");
        clock.advance(Duration.ofSeconds(1));
        assertThat(source.token()).isEqualTo("t2");
    }

    @Test
    void anInvalidatedTokenIsReplacedOnTheNextCall() throws IOException {
        ClientCredentialsTokenSource source = source();
        String first = source.token();

        source.invalidate("someone-else");
        assertThat(source.token()).isEqualTo(first);
        source.invalidate(first);
        assertThat(source.token()).isEqualTo("t2");
    }

    @Test
    void whileUserServiceFailsAValidTokenKeepsBeingUsedAndAttemptsAreSpacedOut() throws IOException {
        ClientCredentialsTokenSource source = source();
        source.token();
        status = 500;

        clock.advance(Duration.ofSeconds(550));
        assertThat(source.token()).isEqualTo("t1");
        assertThat(source.token()).isEqualTo("t1");
        assertThat(requests).hasSize(2);

        // Expired and still failing: the call fails like one to a service that is down.
        clock.advance(Duration.ofSeconds(60));
        assertThatThrownBy(source::token).isInstanceOf(IOException.class);
        assertThatThrownBy(source::token).isInstanceOf(IOException.class)
                .hasMessageContaining("failed moments ago");
        assertThat(requests).hasSize(3);

        status = 200;
        clock.advance(ClientCredentialsTokenSource.FAILURE_BACKOFF);
        assertThat(source.token()).isEqualTo("t2");
    }

    @Test
    void wrongCredentialsAreAnError() {
        status = 401;
        assertThatThrownBy(source()::token).isInstanceOf(IOException.class)
                .hasMessageContaining("No service token from user-service");
    }

    private ClientCredentialsTokenSource source() {
        TripServiceProperties.ServiceAuth auth = RemoteCallsTest.serviceAuth(
                "http://localhost:" + server.getAddress().getPort() + "/oauth2/token");
        TripServiceProperties properties = new TripServiceProperties(null, null, null, null, null, null, null, null,
                auth);
        return new ClientCredentialsTokenSource(RestClient.builder(), properties, clock);
    }

    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
