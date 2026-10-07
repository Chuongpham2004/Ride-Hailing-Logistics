package com.rhl.gateway;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Gateway against a stub user-service (JWKS + echo) and a real Redis. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Testcontainers
class ApiGatewayIT {

    private static final RSAKey KEY = generateKey();
    private static final HttpServer STUB = startStub();

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        String stub = "http://localhost:" + STUB.getAddress().getPort();
        registry.add("USER_SERVICE_URL", () -> stub);
        registry.add("TRIP_SERVICE_URL", () -> stub);
        registry.add("PAYMENT_SERVICE_URL", () -> stub);
        // Nothing listens here: simulates a service that is not running.
        registry.add("LOCATION_SERVICE_URL", () -> "http://localhost:" + freePort());
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @Autowired
    WebTestClient client;

    @Autowired
    ReactiveStringRedisTemplate redisTemplate;

    @Test
    void rejectsMissingTokenWithEnvelopeAndCorrelationId() {
        client.get().uri("/api/v1/users/me")
                .header("X-Correlation-Id", "abc-123")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("X-Correlation-Id", "abc-123")
                .expectBody()
                .jsonPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED")
                .jsonPath("$.correlationId").isEqualTo("abc-123");
    }

    @Test
    void routesAuthenticatedRequestsAndForwardsCorrelationId() {
        client.get().uri("/api/v1/users/me")
                .header("Authorization", "Bearer " + token(List.of("CUSTOMER"), UUID.randomUUID().toString()))
                .header("X-Correlation-Id", "bad\tvalue")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.path").isEqualTo("/api/v1/users/me")
                .jsonPath("$.correlationId").value(id -> {
                    // An unsafe caller value is replaced, never forwarded.
                    org.assertj.core.api.Assertions.assertThat((String) id).hasSize(36);
                });
    }

    @Test
    void publicAuthEndpointsNeedNoToken() {
        for (String path : List.of("/api/v1/auth/login", "/api/v1/auth/password-reset",
                "/api/v1/auth/password-reset/confirm")) {
            client.post().uri(path)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.path").isEqualTo(path)
                    // user-service rate-limits sign-ups and resets by this client address.
                    .jsonPath("$.forwardedFor").value(ip ->
                            org.assertj.core.api.Assertions.assertThat((String) ip).isNotIn("null", ""));
        }
        client.get().uri("/api/v1/auth/password-reset")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Provider webhooks carry no user token; payment-service checks their signature instead. */
    @Test
    void paymentCallbacksPassWithoutATokenButOtherPaymentRoutesDoNot() {
        client.post().uri("/api/v1/payments/callbacks/sandbox")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.path").isEqualTo("/api/v1/payments/callbacks/sandbox");
        client.get().uri("/api/v1/payments/callbacks/sandbox")
                .exchange()
                .expectStatus().isUnauthorized();
        client.post().uri("/api/v1/payments/00000000-0000-0000-0000-000000000000/retry")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void revokedTokensAreRejected() {
        String jti = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set("auth:revoked:" + jti, "1", Duration.ofMinutes(5)).block();

        client.get().uri("/api/v1/users/me")
                .header("Authorization", "Bearer " + token(List.of("CUSTOMER"), jti))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void adminPathsRequireAStaffRole() {
        client.get().uri("/api/v1/admin/drivers")
                .header("Authorization", "Bearer " + token(List.of("CUSTOMER", "DRIVER"), UUID.randomUUID().toString()))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("ACCESS_DENIED");

        client.get().uri("/api/v1/admin/drivers")
                .header("Authorization", "Bearer " + token(List.of("REVIEWER"), UUID.randomUUID().toString()))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void rejectsTokensFromAnotherIssuerOrOfTheWrongType() {
        client.get().uri("/api/v1/trips/1")
                .header("Authorization", "Bearer " + sign("someone-else", "access", List.of("CUSTOMER")))
                .exchange()
                .expectStatus().isUnauthorized();
        client.get().uri("/api/v1/trips/1")
                .header("Authorization", "Bearer " + sign("rhl-user-service", "refresh", List.of("CUSTOMER")))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void downstreamServiceDownIs503WithEnvelope() {
        client.get().uri("/api/v1/locations/drivers/nearby")
                .header("Authorization", "Bearer " + token(List.of("CUSTOMER"), UUID.randomUUID().toString()))
                .header("X-Correlation-Id", "down-1")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("DEPENDENCY_UNAVAILABLE")
                .jsonPath("$.correlationId").isEqualTo("down-1");
    }

    @Test
    void unknownRouteIs404WithEnvelope() {
        client.get().uri("/api/v1/nothing-here")
                .header("Authorization", "Bearer " + token(List.of("CUSTOMER"), UUID.randomUUID().toString()))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
    }

    // ---- helpers --------------------------------------------------------------------------

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String token(List<String> roles, String jti) {
        return sign("rhl-user-service", "access", roles, jti);
    }

    private static String sign(String issuer, String type, List<String> roles) {
        return sign(issuer, type, roles, UUID.randomUUID().toString());
    }

    private static String sign(String issuer, String type, List<String> roles, String jti) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(UUID.randomUUID().toString())
                    .jwtID(jti)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(300)))
                    .claim("typ", type)
                    .claim("roles", roles)
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID())
                    .type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Serves the JWKS like user-service and echoes path + correlation ID for every other request. */
    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            String jwks = new JWKSet(KEY.toPublicJWK()).toString();
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String body = path.equals("/.well-known/jwks.json")
                        ? jwks
                        : "{\"path\":\"" + path + "\",\"correlationId\":\""
                        + exchange.getRequestHeaders().getFirst("X-Correlation-Id") + "\",\"forwardedFor\":\""
                        + exchange.getRequestHeaders().getFirst("X-Forwarded-For") + "\"}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
