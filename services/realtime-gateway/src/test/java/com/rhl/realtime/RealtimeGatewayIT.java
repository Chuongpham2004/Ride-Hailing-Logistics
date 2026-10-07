package com.rhl.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.rhl.common.id.UuidV7;
import com.rhl.realtime.infrastructure.messaging.DomainEventListener;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end over a real WebSocket, with Kafka and Redis in containers and a local JWKS endpoint
 * standing in for user-service (CON-03, COM-005…007, FR-RT, README §4.7).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
        "rhl.realtime.instance-id=it-instance",
        "rhl.realtime.heartbeat-timeout=8s",
        "rhl.realtime.sweep-interval=200ms",
        "rhl.realtime.telemetry-min-interval=1s"})
class RealtimeGatewayIT {

    private static final RSAKey KEY;
    private static final HttpServer JWKS;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("it").generate();
            JWKS = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            JWKS.createContext("/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            JWKS.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://localhost:" + JWKS.getAddress().getPort() + "/jwks.json");
    }

    @AfterAll
    static void stopJwks() {
        JWKS.stop(0);
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    /** Events published before the listener owns its partitions would be skipped (it starts at the latest offset). */
    @BeforeEach
    void listenerAssigned() {
        MessageListenerContainer container = listeners.getListenerContainer(DomainEventListener.LISTENER_ID);
        await().atMost(Duration.ofSeconds(60)).until(() -> container != null
                && container.getAssignedPartitions() != null && container.getAssignedPartitions().size() == 12);
    }

    @Test
    void eachUserReceivesOnlyTheEventsThatConcernThem() throws Exception {
        UUID driver = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        Client driverApp = connect(token(driver, "DRIVER", Duration.ofMinutes(10)));
        Client customerApp = connect(token(customer, "CUSTOMER", Duration.ofMinutes(10)));
        Client otherCustomer = connect(token(UUID.randomUUID(), "CUSTOMER", Duration.ofMinutes(10)));

        JsonNode ready = driverApp.next();
        assertThat(ready.path("type").asText()).isEqualTo("SESSION_READY");
        assertThat(ready.path("sequence").asLong()).isEqualTo(1);
        assertThat(ready.path("data").path("userId").asText()).isEqualTo(driver.toString());
        assertThat(ready.path("data").path("roles").get(0).asText()).isEqualTo("DRIVER");
        customerApp.next();
        otherCustomer.next();
        assertThat(redisTemplate.opsForSet().members("ws:session:" + driver)).containsExactly("it-instance");

        UUID trip = UuidV7.random();
        String offerEvent = publish("dispatch.offers.v1", driver, "DriverOfferCreated",
                Map.of("driverId", driver.toString(), "tripId", trip.toString()));
        publish("trip.events.v1", trip, "TripAccepted",
                Map.of("tripId", trip.toString(), "customerId", customer.toString(), "driverId", driver.toString()));
        publish("payment.events.v1", trip, "PaymentSucceeded",
                Map.of("tripId", trip.toString(), "customerId", customer.toString(), "driverId", driver.toString()));
        publish("wallet.events.v1", driver, "DriverEarningPosted",
                Map.of("tripId", trip.toString(), "driverId", driver.toString()));

        JsonNode offer = driverApp.next();
        assertThat(offer.path("type").asText()).isEqualTo("DRIVER_OFFER_CREATED");
        assertThat(offer.path("messageId").asText()).isEqualTo(offerEvent);
        assertThat(offer.path("sequence").asLong()).isEqualTo(2);
        assertThat(offer.path("data").path("tripId").asText()).isEqualTo(trip.toString());
        assertThat(offer.has("aggregateVersion")).isTrue();
        assertThat(driverApp.next().path("type").asText()).isEqualTo("TRIP_ACCEPTED");
        JsonNode earning = driverApp.next();
        assertThat(earning.path("type").asText()).isEqualTo("DRIVER_EARNING_POSTED");
        assertThat(earning.path("sequence").asLong()).isEqualTo(4);

        assertThat(customerApp.next().path("type").asText()).isEqualTo("TRIP_ACCEPTED");
        assertThat(customerApp.next().path("type").asText()).isEqualTo("PAYMENT_SUCCEEDED");
        // Nobody else hears about it; the driver does not get the customer's payment.
        assertThat(otherCustomer.poll(Duration.ofSeconds(1))).isNull();
        assertThat(customerApp.poll(Duration.ofMillis(300))).isNull();
        assertThat(driverApp.poll(Duration.ofMillis(300))).isNull();

        driverApp.close();
        await().atMost(Duration.ofSeconds(5)).until(() ->
                !Boolean.TRUE.equals(redisTemplate.hasKey("ws:session:" + driver)));
    }

    @Test
    void driversForwardTheirLocationUnderTheirOwnIdentity() throws Exception {
        UUID driver = UUID.randomUUID();
        Client driverApp = connect(token(driver, "DRIVER", Duration.ofMinutes(10)));
        Client customerApp = connect(token(UUID.randomUUID(), "CUSTOMER", Duration.ofMinutes(10)));
        driverApp.next();
        customerApp.next();

        String ping = driverApp.send("PING", null, Map.of());
        JsonNode pong = driverApp.next();
        assertThat(pong.path("type").asText()).isEqualTo("PONG");
        assertThat(pong.path("data").path("inReplyTo").asText()).isEqualTo(ping);

        driverApp.send("DRIVER_LOCATION_UPDATED", 42L, location());
        JsonNode reported = consume("location.telemetry.raw.v1", driver.toString());
        assertThat(reported.path("eventType").asText()).isEqualTo("DriverLocationReported");
        assertThat(reported.path("producer").asText()).isEqualTo("realtime-gateway");
        assertThat(reported.path("payload").path("driverId").asText()).isEqualTo(driver.toString());
        assertThat(reported.path("payload").path("sequence").asLong()).isEqualTo(42);
        assertThat(reported.path("payload").path("latitude").asDouble()).isEqualTo(10.7769);

        // NFR-SEC-007: one report per second.
        String tooSoon = driverApp.send("DRIVER_LOCATION_UPDATED", 43L, location());
        assertError(driverApp.next(), "RATE_LIMIT_EXCEEDED", tooSoon);
        // The driver is the session's user: a driverId in the message is refused, not used.
        Map<String, Object> spoofed = new LinkedHashMap<>(location());
        spoofed.put("driverId", UUID.randomUUID().toString());
        String spoof = driverApp.send("DRIVER_LOCATION_UPDATED", 44L, spoofed);
        assertError(driverApp.next(), "VALIDATION_ERROR", spoof);
        String customerReport = customerApp.send("DRIVER_LOCATION_UPDATED", 1L, location());
        assertError(customerApp.next(), "ACCESS_DENIED", customerReport);
        String unknown = driverApp.send("SUBSCRIBE_EVERYTHING", null, Map.of());
        assertError(driverApp.next(), "VALIDATION_ERROR", unknown);
        driverApp.sendRaw("{not json");
        JsonNode garbage = driverApp.next();
        assertThat(garbage.path("data").path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(garbage.path("data").has("inReplyTo")).isFalse();
        // Refused messages do not close the connection.
        assertThat(driverApp.isOpen()).isTrue();
    }

    @Test
    void aConnectionNeedsAValidTokenAndLastsOnlyAsLongAsIt() throws Exception {
        assertThatThrownBy(() -> connect(null)).hasMessageContaining("401");
        assertThatThrownBy(() -> connect("not-a-token")).hasMessageContaining("401");
        assertThatThrownBy(() -> connect(token(UUID.randomUUID(), "CUSTOMER", Duration.ofMinutes(-2))))
                .hasMessageContaining("401");
        UUID revokedUser = UUID.randomUUID();
        String revokedToken = token(revokedUser, "CUSTOMER", Duration.ofMinutes(10));
        redisTemplate.opsForValue().set("auth:revoked:" + jti(revokedToken), "1");
        assertThatThrownBy(() -> connect(revokedToken)).hasMessageContaining("401");

        // A bearer header works as well as the query parameter.
        UUID user = UUID.randomUUID();
        Client app = connect(token(user, "CUSTOMER", Duration.ofSeconds(3)), true);
        assertThat(app.next().path("type").asText()).isEqualTo("SESSION_READY");
        // COM-005: refresh before expiry, with a token of the same user only.
        String foreign = app.send("AUTH", null, Map.of("accessToken",
                token(UUID.randomUUID(), "CUSTOMER", Duration.ofMinutes(10))));
        assertError(app.next(), "ACCESS_DENIED", foreign);
        String fresh = token(user, "CUSTOMER", Duration.ofMinutes(10));
        String refresh = app.send("AUTH", null, Map.of("accessToken", fresh));
        JsonNode refreshed = app.next();
        assertThat(refreshed.path("type").asText()).isEqualTo("AUTH_REFRESHED");
        assertThat(refreshed.path("data").path("inReplyTo").asText()).isEqualTo(refresh);
        Thread.sleep(3_500);
        assertThat(app.isOpen()).isTrue();
        // Logout revokes the token: the session ends within a sweep.
        redisTemplate.opsForValue().set("auth:revoked:" + jti(fresh), "1");
        assertThat(app.closed().get(5, TimeUnit.SECONDS).getCode()).isEqualTo(4401);

        // Not refreshed: closed when the token expires.
        Client expiring = connect(token(UUID.randomUUID(), "DRIVER", Duration.ofSeconds(2)));
        CloseStatus expired = expiring.closed().get(6, TimeUnit.SECONDS);
        assertThat(expired.getCode()).isEqualTo(4401);
        assertThat(expired.getReason()).isEqualTo("TOKEN_EXPIRED");
    }

    @Test
    void aSilentConnectionIsClosed() throws Exception {
        Client app = connect(token(UUID.randomUUID(), "CUSTOMER", Duration.ofMinutes(10)));
        assertThat(app.next().path("data").path("heartbeatIntervalSeconds").asInt()).isEqualTo(2);
        CloseStatus status = app.closed().get(15, TimeUnit.SECONDS);
        assertThat(status.getCode()).isEqualTo(4408);
        assertThat(status.getReason()).isEqualTo("HEARTBEAT_TIMEOUT");
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static Map<String, Object> location() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("latitude", 10.7769);
        data.put("longitude", 106.7009);
        data.put("accuracyMeters", 8.5);
        data.put("headingDegrees", 125);
        data.put("deviceTimestamp", Instant.now().toString());
        return data;
    }

    private static void assertError(JsonNode message, String code, String inReplyTo) {
        assertThat(message.path("type").asText()).isEqualTo("ERROR");
        assertThat(message.path("data").path("code").asText()).isEqualTo(code);
        assertThat(message.path("data").path("inReplyTo").asText()).isEqualTo(inReplyTo);
    }

    /** Publishes the contract example of {@code type} with fresh IDs; returns the event ID. */
    private String publish(String topic, UUID key, String type, Map<String, String> payloadOverrides)
            throws Exception {
        ObjectNode event;
        try (InputStream in = getClass().getResourceAsStream("/contracts/events/examples/" + type
                + ".v1.example.json")) {
            assertThat(in).as("example of " + type).isNotNull();
            event = (ObjectNode) json.readTree(in);
        }
        String eventId = UuidV7.randomString();
        event.put("eventId", eventId);
        event.put("aggregateId", key.toString());
        ObjectNode payload = (ObjectNode) event.path("payload");
        payloadOverrides.forEach(payload::put);
        kafkaTemplate.send(topic, key.toString(), json.writeValueAsString(event)).get();
        return eventId;
    }

    private JsonNode consume(String topic, String key) throws Exception {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return json.readTree(record.value());
                    }
                }
            }
        }
        throw new AssertionError("No record with key " + key + " on " + topic);
    }

    private static String token(UUID subject, String role, Duration validFor) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("rhl-user-service")
                .subject(subject.toString())
                .jwtID(UUID.randomUUID().toString())
                .claim("roles", List.of(role))
                .claim("typ", "access")
                .issueTime(Date.from(now.minusSeconds(60)))
                .expirationTime(Date.from(now.plus(validFor)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    private static String jti(String token) throws Exception {
        return SignedJWT.parse(token).getJWTClaimsSet().getJWTID();
    }

    private Client connect(String token) throws Exception {
        return connect(token, false);
    }

    private Client connect(String token, boolean header) throws Exception {
        Client client = new Client();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        String uri = "ws://localhost:" + port + "/ws";
        if (token != null && header) {
            headers.setBearerAuth(token);
        } else if (token != null) {
            uri += "?access_token=" + token;
        }
        client.session = new StandardWebSocketClient().execute(client, headers, URI.create(uri))
                .get(10, TimeUnit.SECONDS);
        return client;
    }

    private final class Client extends TextWebSocketHandler {

        private final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        private WebSocketSession session;

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            received.add(json.readTree(message.getPayload()));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(status);
        }

        JsonNode next() throws InterruptedException {
            JsonNode message = received.poll(20, TimeUnit.SECONDS);
            assertThat(message).as("a message within 20 s").isNotNull();
            return message;
        }

        JsonNode poll(Duration wait) throws InterruptedException {
            return received.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
        }

        String send(String type, Long sequence, Map<String, ?> data) throws Exception {
            String messageId = UuidV7.randomString();
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("messageId", messageId);
            message.put("type", type);
            message.put("version", 1);
            message.put("sentAt", Instant.now().toString());
            if (sequence != null) {
                message.put("sequence", sequence);
            }
            message.put("data", data);
            sendRaw(json.writeValueAsString(message));
            return messageId;
        }

        void sendRaw(String text) throws Exception {
            session.sendMessage(new TextMessage(text));
        }

        boolean isOpen() {
            return session.isOpen();
        }

        CompletableFuture<CloseStatus> closed() {
            return closed;
        }

        void close() throws Exception {
            session.close();
        }
    }
}
