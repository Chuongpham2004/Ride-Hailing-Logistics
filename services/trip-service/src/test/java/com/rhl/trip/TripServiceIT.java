package com.rhl.trip;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhl.trip.domain.DriverCandidate;
import com.rhl.trip.infrastructure.client.LocationClient;
import com.rhl.trip.infrastructure.messaging.Topics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end against real PostgreSQL, Redis and Kafka (UC-03, UC-06, FR-TRIP, FR-MAT, FR-CAN).
 * location-service is replaced by a mock that returns the candidates each test needs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        // Tokens are injected with spring-security-test, so the JWKS endpoint is never called.
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks.json",
        "rhl.matching.offer-timeout=2s",
        "rhl.matching.matching-timeout=6s",
        "rhl.matching.max-radius-meters=4000",
        "rhl.matching.hold-grace=1s",
        "rhl.matching.tick-interval=200ms",
        "rhl.outbox.poll-interval=50ms"})
class TripServiceIT {

    private static final Map<String, Object> PICKUP =
            Map.of("latitude", 10.7725, "longitude", 106.698, "address", "Cho Ben Thanh, Quan 1");
    private static final Map<String, Object> DROPOFF =
            Map.of("latitude", 10.7626, "longitude", 106.6822, "address", "DH Khoa hoc Tu nhien, Quan 5");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    @MockitoBean
    LocationClient location;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void rideIsCreatedOnceMatchedToTheNearestDriverAndCompletedOnce() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID near = UUID.randomUUID();
        UUID far = UUID.randomUUID();
        candidates(new DriverCandidate(far, 800, 500), new DriverCandidate(near, 300, 900));

        String key = "create-" + UUID.randomUUID();
        JsonNode trip = data(createTrip(customer, key, PICKUP).andExpect(status().isCreated()));
        String tripId = trip.path("id").asText();
        assertThat(trip.path("status").asText()).isEqualTo("MATCHING");

        // Retry with the same key returns the same trip; a different body under it is refused (COM-008).
        assertThat(data(createTrip(customer, key, PICKUP).andExpect(status().isCreated())).path("id").asText())
                .isEqualTo(tripId);
        createTrip(customer, key, DROPOFF)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trips WHERE customer_id = ?", Integer.class, customer))
                .isEqualTo(1);

        // The nearest driver gets the offer, with the pickup but not the drop-off (BR-013).
        JsonNode offer = awaitOffer(near);
        assertThat(offer.path("tripId").asText()).isEqualTo(tripId);
        assertThat(offer.path("estimatedPickupDistanceMeters").asInt()).isEqualTo(300);
        assertThat(offer.has("dropoff")).isFalse();
        assertThat(pendingOffers(far)).isEmpty();
        String offerId = offer.path("id").asText();
        assertThat(redisTemplate.opsForValue().get("dispatch:driver-hold:" + near)).isEqualTo(offerId);

        // Nobody else can see the trip or take the offer.
        perform(get("/api/v1/trips/" + tripId), customerToken(UUID.randomUUID())).andExpect(status().isNotFound());
        perform(post("/api/v1/offers/" + offerId + "/accept"), driverToken(far)).andExpect(status().isNotFound());

        // Ten simultaneous accepts: one assignment, the same answer for every retry (UC-06).
        List<Integer> statuses = concurrently(10, () -> perform(post("/api/v1/offers/" + offerId + "/accept"),
                driverToken(near)).andReturn().getResponse().getStatus());
        assertThat(statuses).containsOnly(200);
        perform(get("/api/v1/trips/" + tripId), customerToken(customer))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.driverId").value(near.toString()));
        assertThat(count("SELECT COUNT(*) FROM trip_status_history WHERE trip_id = ? AND to_status = 'ACCEPTED'",
                tripId)).isEqualTo(1);
        assertThat(redisTemplate.hasKey("dispatch:driver-hold:" + near)).isFalse();

        // The driver cannot skip steps; the customer cannot drive the trip.
        perform(post("/api/v1/trips/" + tripId + "/complete"), driverToken(near))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRIP_STATE"));
        perform(post("/api/v1/trips/" + tripId + "/start-pickup"), customerToken(customer))
                .andExpect(status().isForbidden());

        for (String step : List.of("start-pickup", "arrive", "start", "complete")) {
            perform(post("/api/v1/trips/" + tripId + "/" + step), driverToken(near)).andExpect(status().isOk());
        }
        // A retried completion changes nothing and publishes nothing (BR-009).
        perform(post("/api/v1/trips/" + tripId + "/complete"), driverToken(near))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        perform(get("/api/v1/trips/" + tripId + "/history"), customerToken(customer))
                .andExpect(jsonPath("$.data[*].toStatus").value(org.hamcrest.Matchers.contains("CREATED", "MATCHING",
                        "ACCEPTED", "PICKING_UP", "ARRIVED", "IN_TRIP", "COMPLETED")));
        perform(get("/api/v1/trips"), driverToken(near))
                .andExpect(jsonPath("$.data.items[0].id").value(tripId));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(count(
                "SELECT COUNT(*) FROM outbox_events WHERE message_key = ? AND status = 'PENDING'", tripId))
                .isZero());
        List<JsonNode> events = consume(Topics.TRIP_EVENTS, tripId, 6);
        assertThat(events).extracting(e -> e.path("eventType").asText()).containsExactly("TripRequested",
                "TripAccepted", "TripStatusChanged", "TripStatusChanged", "TripStatusChanged", "TripCompleted");
        assertThat(events).extracting(e -> e.path("aggregateVersion").asLong()).isSorted().doesNotHaveDuplicates();
        assertThat(events.getLast().path("payload").path("driverId").asText()).isEqualTo(near.toString());

        List<JsonNode> offers = consume(Topics.DISPATCH_OFFERS, near.toString(), 1);
        assertThat(offers.getFirst().path("eventType").asText()).isEqualTo("DriverOfferCreated");
    }

    @Test
    void declinedAndUnansweredOffersMoveOnUntilMatchingGivesUp() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        candidates(new DriverCandidate(first, 200, 100), new DriverCandidate(second, 900, 100));

        String tripId = data(createTrip(customer, "decline-" + UUID.randomUUID(), PICKUP)).path("id").asText();

        String firstOffer = awaitOffer(first).path("id").asText();
        perform(post("/api/v1/offers/" + firstOffer + "/decline"), driverToken(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DECLINED"));
        perform(post("/api/v1/offers/" + firstOffer + "/accept"), driverToken(first))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFFER_EXPIRED"));

        // The second driver is offered next and lets it expire.
        String secondOffer = awaitOffer(second).path("id").asText();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT status FROM driver_offers WHERE id = ?::uuid", String.class, secondOffer))
                .isEqualTo("EXPIRED"));

        // Nobody left within the maximum radius: NO_DRIVER at the deadline (README §5.2).
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                perform(get("/api/v1/trips/" + tripId), customerToken(customer))
                        .andExpect(jsonPath("$.data.status").value("NO_DRIVER")));
        assertThat(jdbc.queryForObject("SELECT matching_radius_meters FROM trips WHERE id = ?::uuid", Integer.class,
                tripId)).isEqualTo(4000);
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_events WHERE message_key IN (?, ?) ORDER BY id",
                String.class, first.toString(), second.toString()))
                .containsExactly("DriverOfferCreated", "DriverOfferDeclined", "DriverOfferCreated",
                        "DriverOfferExpired");
        assertThat(jdbc.queryForObject("""
                        SELECT envelope->'payload'->>'newStatus' FROM outbox_events
                        WHERE message_key = ? AND event_type = 'TripStatusChanged'
                        """, String.class, tripId)).isEqualTo("NO_DRIVER");
    }

    @Test
    void cancellingWhileMatchingWithdrawsTheOfferAndCustomersHaveOneActiveTrip() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID driver = UUID.randomUUID();
        candidates(new DriverCandidate(driver, 400, 100));

        String tripId = data(createTrip(customer, "cancel-" + UUID.randomUUID(), PICKUP)).path("id").asText();
        createTrip(customer, "second-" + UUID.randomUUID(), PICKUP)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

        String offerId = awaitOffer(driver).path("id").asText();
        Map<String, Object> cancel = Map.of("reason", "CHANGED_MIND", "note", "Plans changed");
        perform(post("/api/v1/trips/" + tripId + "/cancel"), customerToken(customer), cancel)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelledBy").value("CUSTOMER"));
        // Cancelling twice is idempotent: still one cancellation event.
        perform(post("/api/v1/trips/" + tripId + "/cancel"), customerToken(customer), cancel)
                .andExpect(status().isOk());

        perform(post("/api/v1/offers/" + offerId + "/accept"), driverToken(driver))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFFER_EXPIRED"));
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE message_key = ? AND event_type = 'TripCancelled'",
                tripId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT envelope->'payload'->>'reason' FROM outbox_events "
                + "WHERE message_key = ? AND event_type = 'DriverOfferCancelled'", String.class, driver.toString()))
                .isEqualTo("TRIP_CANCELLED");
        assertThat(redisTemplate.hasKey("dispatch:driver-hold:" + driver)).isFalse();

        // The customer is free to book again.
        String next = data(createTrip(customer, "again-" + UUID.randomUUID(), PICKUP)).path("id").asText();
        awaitOffer(driver);
        perform(post("/api/v1/trips/" + next + "/cancel"), customerToken(customer), cancel).andExpect(status().isOk());
    }

    @Test
    void aDriverOnATripIsNeverOfferedAnother() throws Exception {
        UUID busy = UUID.randomUUID();
        UUID free = UUID.randomUUID();
        candidates(new DriverCandidate(busy, 100, 100));
        String first = data(createTrip(UUID.randomUUID(), "busy-" + UUID.randomUUID(), PICKUP)).path("id").asText();
        String offerId = awaitOffer(busy).path("id").asText();
        perform(post("/api/v1/offers/" + offerId + "/accept"), driverToken(busy)).andExpect(status().isOk());

        // location-service may still list the busy driver (its trip.events consumer lags); trip-service filters.
        candidates(new DriverCandidate(busy, 50, 100), new DriverCandidate(free, 700, 100));
        UUID otherCustomer = UUID.randomUUID();
        String second = data(createTrip(otherCustomer, "free-" + UUID.randomUUID(), PICKUP)).path("id").asText();
        assertThat(awaitOffer(free).path("tripId").asText()).isEqualTo(second);
        assertThat(pendingOffers(busy)).isEmpty();

        Map<String, Object> cancel = Map.of("reason", "OTHER");
        perform(post("/api/v1/trips/" + second + "/cancel"), customerToken(otherCustomer), cancel)
                .andExpect(status().isOk());
        perform(post("/api/v1/trips/" + first + "/cancel"), driverToken(busy), Map.of("reason", "VEHICLE_ISSUE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancelledBy").value("DRIVER"));
    }

    @Test
    void endpointsEnforceAuthenticationRolesAndInput() throws Exception {
        mvc.perform(get("/api/v1/trips")).andExpect(status().isUnauthorized());
        createTrip(UUID.randomUUID(), "x", PICKUP).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/trips").with(driverToken(UUID.randomUUID()))
                        .header("Idempotency-Key", "driver-key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body(PICKUP))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/trips").with(customerToken(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body(PICKUP))))
                .andExpect(status().isBadRequest());
        createTrip(UUID.randomUUID(), "bad-coordinates", Map.of("latitude", 95, "longitude", 106.7, "address", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        perform(get("/api/v1/offers"), customerToken(UUID.randomUUID())).andExpect(status().isForbidden());
    }

    // ---- helpers --------------------------------------------------------------------------------

    private void candidates(DriverCandidate... drivers) {
        when(location.nearby(any(), any(), anyInt(), anyInt())).thenReturn(List.of(drivers));
    }

    private ResultActions createTrip(UUID customer, String key, Map<String, Object> pickup) throws Exception {
        return mvc.perform(post("/api/v1/trips").with(customerToken(customer))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body(pickup))));
    }

    private static Map<String, Object> body(Map<String, Object> pickup) {
        return Map.of("serviceType", "RIDE", "pickup", pickup, "dropoff", DROPOFF);
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, RequestPostProcessor token)
            throws Exception {
        return mvc.perform(request.with(token));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, RequestPostProcessor token,
                                  Object body) throws Exception {
        return mvc.perform(request.with(token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private JsonNode awaitOffer(UUID driver) {
        List<JsonNode> found = new ArrayList<>();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            found.clear();
            pendingOffers(driver).forEach(found::add);
            assertThat(found).hasSize(1);
        });
        return found.getFirst();
    }

    private JsonNode pendingOffers(UUID driver) throws Exception {
        return data(perform(get("/api/v1/offers"), driverToken(driver)));
    }

    private int count(String sql, String key) {
        Integer value = jdbc.queryForObject(sql.replace("trip_id = ?", "trip_id = ?::uuid"), Integer.class, key);
        return value == null ? 0 : value;
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private static <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private static RequestPostProcessor customerToken(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString())).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private static RequestPostProcessor driverToken(UUID id) {
        return jwt().jwt(j -> j.subject(id.toString())).authorities(new SimpleGrantedAuthority("ROLE_DRIVER"));
    }

    private List<JsonNode> consume(String topic, String key, int expected) throws Exception {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<JsonNode> events = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 15_000;
            while (events.size() < expected && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        events.add(json.readTree(record.value()));
                    }
                }
            }
        }
        return events;
    }
}
