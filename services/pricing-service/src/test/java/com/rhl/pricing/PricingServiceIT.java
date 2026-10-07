package com.rhl.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rhl.common.id.UuidV7;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end against real PostgreSQL, Redis and Kafka (UC-02, FR-PRI, BR-005…007). */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        // Tokens are injected with spring-security-test, so the JWKS endpoint is never called.
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks.json",
        "rhl.quote.ttl=3s"})
class PricingServiceIT {

    private static final Map<String, Object> BEN_THANH =
            Map.of("latitude", 10.7725, "longitude", 106.6980, "address", "Cho Ben Thanh, Quan 1");
    private static final Map<String, Object> DH_KHTN =
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

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void customerGetsAPricedQuoteThatOnlyTheyAndTripServiceCanUse() throws Exception {
        UUID customer = UUID.randomUUID();

        JsonNode quote = data(quote(customer, "DELIVERY", BEN_THANH, DH_KHTN).andExpect(status().isCreated()));

        String id = quote.path("id").asText();
        JsonNode b = quote.path("breakdown");
        assertThat(b.path("baseFare").asLong() + b.path("distanceFare").asLong() + b.path("timeFare").asLong()
                + b.path("minimumFareAdjustment").asLong() + b.path("surgeAmount").asLong()
                + b.path("roundingAdjustment").asLong()).isEqualTo(quote.path("total").asLong());
        assertThat(quote.path("total").asLong() % 1_000).isZero();
        assertThat(quote.path("currency").asText()).isEqualTo("VND");
        assertThat(quote.path("ruleVersion").asInt()).isEqualTo(1);
        assertThat(quote.path("surgeMultiplier").decimalValue()).isEqualByComparingTo("1.00");
        assertThat(quote.path("surgeSource").asText()).isEqualTo("COMPUTED");
        assertThat(quote.path("surgeConfirmationRequired").asBoolean()).isFalse();
        assertThat(quote.path("routeSource").asText()).isEqualTo("ESTIMATE");
        assertThat(quote.path("distanceMeters").asInt()).isBetween(2_600, 2_900);
        assertThat(Duration.between(Instant.parse(quote.path("createdAt").asText()),
                Instant.parse(quote.path("expiresAt").asText()))).isEqualTo(Duration.ofSeconds(3));
        assertThat(redisTemplate.hasKey("quote:" + id)).isTrue();

        // Owner and administrators can read it; other customers cannot tell it exists.
        perform(get("/api/v1/quotes/" + id), customerToken(customer)).andExpect(status().isOk());
        perform(get("/api/v1/quotes/" + id), token(UUID.randomUUID(), "ADMINISTRATOR")).andExpect(status().isOk());
        perform(get("/api/v1/quotes/" + id), customerToken(UUID.randomUUID())).andExpect(status().isNotFound());

        // trip-service validation (BR-005): owner + service + validity.
        mvc.perform(validate(id, customer, "DELIVERY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(quote.path("total").asLong()));
        mvc.perform(validate(id, UUID.randomUUID(), "DELIVERY")).andExpect(status().isNotFound());
        mvc.perform(validate(id, customer, "RIDE"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

        // Expired quotes cannot be used, whether read from Redis or from the database.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> mvc.perform(validate(id, customer, "DELIVERY"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("QUOTE_EXPIRED")));
        await().atMost(Duration.ofSeconds(5)).until(() -> !redisTemplate.hasKey("quote:" + id));
        mvc.perform(validate(id, customer, "DELIVERY")).andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));
    }

    @Test
    void aNewRuleVersionPricesNewQuotesWithoutTouchingIssuedOnes() throws Exception {
        UUID customer = UUID.randomUUID();
        RequestPostProcessor admin = token(UUID.randomUUID(), "ADMINISTRATOR");
        JsonNode before = data(quote(customer, "RIDE", BEN_THANH, DH_KHTN));
        Instant from = Instant.now().plusSeconds(2);

        // Not retroactive, and only administrators may change prices.
        perform(post("/api/v1/admin/pricing/rules"), admin, rule(Instant.now().minusSeconds(60)))
                .andExpect(status().isUnprocessableEntity());
        perform(post("/api/v1/admin/pricing/rules"), customerToken(customer), rule(from))
                .andExpect(status().isForbidden());

        JsonNode v2 = data(perform(post("/api/v1/admin/pricing/rules"), admin, rule(from))
                .andExpect(status().isCreated()));
        assertThat(v2.path("version").asInt()).isEqualTo(2);

        JsonNode rules = data(perform(get("/api/v1/admin/pricing/rules"), admin));
        JsonNode v1 = find(rules, "RIDE", 1);
        assertThat(Instant.parse(v1.path("effectiveTo").asText())).isEqualTo(Instant.parse(v2.path("effectiveFrom")
                .asText()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(data(quote(customer, "RIDE", BEN_THANH, DH_KHTN)).path("ruleVersion").asInt())
                        .isEqualTo(2));
        JsonNode after = data(quote(customer, "RIDE", BEN_THANH, DH_KHTN));
        assertThat(after.path("total").asLong()).isGreaterThan(before.path("total").asLong());
        assertThat(after.path("breakdown").path("baseFare").asLong()).isEqualTo(20_000);

        // The earlier quote keeps its rule version and price (BR-007).
        perform(get("/api/v1/quotes/" + before.path("id").asText()), customerToken(customer))
                .andExpect(jsonPath("$.data.ruleVersion").value(1))
                .andExpect(jsonPath("$.data.total").value(before.path("total").asLong()));
    }

    /** Hoan Kiem, Ha Noi: far from the other tests' pickups, so its counters are its own. */
    @Test
    void demandAboveSupplyRaisesTheMultiplierAndTheQuoteRecordsWhy() throws Exception {
        Map<String, Object> pickup = Map.of("latitude", 21.0285, "longitude", 105.8542, "address", "Ho Hoan Kiem");
        Map<String, Object> dropoff = Map.of("latitude", 21.0368, "longitude", 105.8342, "address", "Lang Bac");
        UUID customer = UUID.randomUUID();
        assertThat(data(quote(customer, "RIDE", pickup, dropoff)).path("surgeMultiplier").decimalValue())
                .isEqualByComparingTo("1.00");

        // One AVAILABLE driver nearby, one BUSY driver who must not count as supply.
        UUID available = UUID.randomUUID();
        UUID busy = UUID.randomUUID();
        publish("driver.events.v1", available, "DriverAvailabilityChanged", "user-service", 1,
                availability(available, "AVAILABLE"));
        publish("driver.events.v1", busy, "DriverAvailabilityChanged", "user-service", 1, availability(busy, "BUSY"));
        // driver.events and location.updates are separate topics: let availability land first.
        await().atMost(Duration.ofSeconds(20)).until(() ->
                "AVAILABLE".equals(redisTemplate.opsForHash().get("surge:driver:" + available, "status"))
                        && "BUSY".equals(redisTemplate.opsForHash().get("surge:driver:" + busy, "status")));
        publish("location.updates.v1", available, "DriverLocationUpdated", "location-service", 7,
                position(available, 21.0290, 105.8540));
        publish("location.updates.v1", busy, "DriverLocationUpdated", "location-service", 7,
                position(busy, 21.0291, 105.8541));

        // Six trip requests in the area within the window: demand 6 vs supply 1 -> capped at 2.00.
        for (int i = 0; i < 6; i++) {
            UUID tripId = UuidV7.random();
            publish("trip.events.v1", tripId, "TripRequested", "trip-service", 0, tripRequested(tripId, pickup, dropoff));
        }

        JsonNode surged = await().atMost(Duration.ofSeconds(20)).until(
                () -> data(quote(customer, "RIDE", pickup, dropoff)),
                q -> q.path("surgeMultiplier").decimalValue().compareTo(new BigDecimal("2.00")) == 0);

        assertThat(surged.path("surgeConfirmationRequired").asBoolean()).isTrue();
        assertThat(surged.path("surgeSource").asText()).isEqualTo("COMPUTED");
        assertThat(surged.path("surgeRuleVersion").asInt()).isEqualTo(1);
        JsonNode b = surged.path("breakdown");
        assertThat(b.path("surgeAmount").asLong()).isPositive();
        assertThat(surged.path("total").asLong()).isGreaterThanOrEqualTo(
                2 * (b.path("baseFare").asLong() + b.path("distanceFare").asLong() + b.path("timeFare").asLong()
                        + b.path("minimumFareAdjustment").asLong()));
        Set<String> supplyKeys = redisTemplate.keys("surge:supply:RIDE:*");
        assertThat(supplyKeys).isNotEmpty();
        assertThat(supplyKeys).anySatisfy(key ->
                assertThat(redisTemplate.opsForZSet().score(key, available.toString())).isNotNull());
        assertThat(supplyKeys).allSatisfy(key ->
                assertThat(redisTemplate.opsForZSet().score(key, busy.toString())).isNull());
    }

    /** README §4.9: TripCompleted -> FareFinalized exactly once, at the booked price (BR-009, BR-007). */
    @Test
    void aCompletedTripIsSettledOnceAtTheBookedPrice() throws Exception {
        UUID customer = UUID.randomUUID();
        JsonNode quote = data(quote(customer, "DELIVERY", BEN_THANH, DH_KHTN));
        UUID tripId = UuidV7.random();
        UUID driver = UUID.randomUUID();
        Map<String, Object> completed = new LinkedHashMap<>();
        completed.put("tripId", tripId.toString());
        completed.put("customerId", customer.toString());
        completed.put("driverId", driver.toString());
        completed.put("serviceType", "DELIVERY");
        completed.put("pickup", BEN_THANH);
        completed.put("dropoff", DH_KHTN);
        completed.put("acceptedAt", Instant.now().minusSeconds(900).toString());
        completed.put("completedAt", Instant.now().toString());
        completed.put("quoteId", quote.path("id").asText());

        // Redelivered (same eventId) and duplicated (new eventId, same trip): still one fare.
        UUID eventId = UuidV7.random();
        publish("trip.events.v1", tripId, eventId, "TripCompleted", "trip-service", 5, completed);
        publish("trip.events.v1", tripId, eventId, "TripCompleted", "trip-service", 5, completed);
        publish("trip.events.v1", tripId, UuidV7.random(), "TripCompleted", "trip-service", 5, completed);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE message_key = ? AND status = 'SENT'", Integer.class,
                tripId.toString())).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT total FROM final_fares WHERE trip_id = ?", Long.class, tripId))
                .isEqualTo(quote.path("total").asLong());

        JsonNode event = consume("pricing.events.v1", tripId.toString());
        assertThat(event.path("eventType").asText()).isEqualTo("FareFinalized");
        JsonNode payload = event.path("payload");
        assertThat(payload.path("method").asText()).isEqualTo("UPFRONT");
        assertThat(payload.path("total").asLong()).isEqualTo(quote.path("total").asLong());
        assertThat(payload.path("quoteId").asText()).isEqualTo(quote.path("id").asText());
        assertThat(payload.path("driverId").asText()).isEqualTo(driver.toString());
        assertThat(payload.path("breakdown").path("distanceFare").asLong())
                .isEqualTo(quote.path("breakdown").path("distanceFare").asLong());

        // A trip without a quote cannot be priced: it goes to the DLT, not to a made-up fare.
        UUID legacy = UuidV7.random();
        Map<String, Object> noQuote = new LinkedHashMap<>(completed);
        noQuote.put("tripId", legacy.toString());
        noQuote.remove("quoteId");
        publish("trip.events.v1", legacy, UuidV7.random(), "TripCompleted", "trip-service", 5, noQuote);
        assertThat(consume("trip.events.v1.DLT", legacy.toString()).path("eventType").asText())
                .isEqualTo("TripCompleted");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM final_fares WHERE trip_id = ?", Integer.class, legacy))
                .isZero();
    }

    @Test
    void everyCancellationGetsOneFeeDecision() throws Exception {
        UUID customer = UUID.randomUUID();
        JsonNode quote = data(quote(customer, "RIDE", BEN_THANH, DH_KHTN));
        UUID late = UuidV7.random();
        UUID early = UuidV7.random();
        Instant now = Instant.now();

        publish("trip.events.v1", late, UuidV7.random(), "TripCancelled", "trip-service", 3,
                cancelled(late, customer, "ARRIVED", now.minusSeconds(600), now, quote.path("id").asText()));
        publish("trip.events.v1", early, UuidV7.random(), "TripCancelled", "trip-service", 1,
                cancelled(early, customer, "MATCHING", null, now, null));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE message_key IN (?, ?) AND status = 'SENT'",
                Integer.class, late.toString(), early.toString())).isEqualTo(2));
        assertThat(jdbc.queryForMap("SELECT decision, fee, rule_version FROM cancellation_fees WHERE trip_id = ?",
                late)).containsEntry("decision", "LATE_CANCELLATION").containsEntry("fee", 10_000L)
                .containsEntry("rule_version", 1);
        assertThat(jdbc.queryForMap("SELECT decision, fee FROM cancellation_fees WHERE trip_id = ?", early))
                .containsEntry("decision", "NOT_ASSIGNED").containsEntry("fee", 0L);

        JsonNode event = consume("pricing.events.v1", late.toString());
        assertThat(event.path("eventType").asText()).isEqualTo("CancellationFeeCalculated");
        assertThat(event.path("payload").path("fee").asLong()).isEqualTo(10_000);
    }

    /** FR-CAN: the fee is shown before cancelling, decided by the same rule, without storing anything. */
    @Test
    void cancellationFeesCanBePreviewedWithoutBeingCharged() throws Exception {
        mvc.perform(preview("CUSTOMER", "MATCHING", "CHANGED_MIND", null, 27_000L))
                .andExpect(jsonPath("$.data.decision").value("NOT_ASSIGNED"))
                .andExpect(jsonPath("$.data.fee").value(0));
        Instant justAccepted = Instant.now().minusSeconds(30);
        mvc.perform(preview("CUSTOMER", "ACCEPTED", "CHANGED_MIND", justAccepted, 27_000L))
                .andExpect(jsonPath("$.data.decision").value("WITHIN_FREE_WINDOW"))
                .andExpect(jsonPath("$.data.fee").value(0))
                .andExpect(jsonPath("$.data.freeUntil").value(justAccepted.plusSeconds(120).toString()))
                .andExpect(jsonPath("$.data.ruleVersion").value(1));
        mvc.perform(preview("CUSTOMER", "PICKING_UP", "WAIT_TOO_LONG", Instant.now().minusSeconds(600), 27_000L))
                .andExpect(jsonPath("$.data.decision").value("LATE_CANCELLATION"))
                .andExpect(jsonPath("$.data.fee").value(10_000))
                .andExpect(jsonPath("$.data.currency").value("VND"));
        // A no-show fee never exceeds the booked price.
        mvc.perform(preview("DRIVER", "ARRIVED", "CUSTOMER_NO_SHOW", Instant.now().minusSeconds(900), 12_000L))
                .andExpect(jsonPath("$.data.decision").value("NO_SHOW"))
                .andExpect(jsonPath("$.data.fee").value(12_000));
        mvc.perform(preview("STAFF", "IN_TRIP", "OTHER", Instant.now().minusSeconds(900), 27_000L))
                .andExpect(jsonPath("$.data.decision").value("NOT_CHARGEABLE"));
        mvc.perform(preview("HACKER", "ACCEPTED", "OTHER", null, null)).andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder preview(String actor, String status, String reason, Instant acceptedAt,
                                                  Long bookedFare) {
        MockHttpServletRequestBuilder request = get("/internal/v1/cancellation-fees/preview")
                .param("serviceType", "RIDE").param("actorType", actor).param("status", status)
                .param("reason", reason);
        if (acceptedAt != null) {
            request.param("acceptedAt", acceptedAt.toString());
        }
        if (bookedFare != null) {
            request.param("bookedFare", bookedFare.toString());
        }
        return request;
    }

    @Test
    void quotesValidateTheirInput() throws Exception {
        UUID customer = UUID.randomUUID();
        quote(customer, "RIDE", BEN_THANH, BEN_THANH)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("too close")));
        quote(customer, "RIDE", Map.of("latitude", 21.0285, "longitude", 105.8542, "address", "Ha Noi"), BEN_THANH)
                .andExpect(status().isUnprocessableEntity());
        quote(customer, "RIDE", Map.of("latitude", 95, "longitude", 106.7, "address", "x"), BEN_THANH)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        perform(post("/api/v1/quotes"), token(UUID.randomUUID(), "DRIVER"),
                Map.of("serviceType", "RIDE", "pickup", BEN_THANH, "dropoff", DH_KHTN))
                .andExpect(status().isForbidden());
    }

    // ---- helpers --------------------------------------------------------------------------------

    private ResultActions quote(UUID customer, String serviceType, Map<String, Object> pickup,
                                Map<String, Object> dropoff) throws Exception {
        return perform(post("/api/v1/quotes"), customerToken(customer),
                Map.of("serviceType", serviceType, "pickup", pickup, "dropoff", dropoff));
    }

    private void publish(String topic, UUID key, String type, String producer, long version,
                         Map<String, Object> payload) throws Exception {
        publish(topic, key, UuidV7.random(), type, producer, version, payload);
    }

    private void publish(String topic, UUID key, UUID eventId, String type, String producer, long version,
                         Map<String, Object> payload) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", type);
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("correlationId", UuidV7.randomString());
        envelope.put("producer", producer);
        envelope.put("aggregateId", key.toString());
        envelope.put("aggregateVersion", version);
        envelope.put("payload", payload);
        kafkaTemplate.send(topic, key.toString(), json.writeValueAsString(envelope)).get();
    }

    private static Map<String, Object> availability(UUID driver, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("driverId", driver.toString());
        payload.put("oldStatus", "OFFLINE");
        payload.put("newStatus", status);
        payload.put("serviceTypes", List.of("RIDE"));
        payload.put("vehicleId", UUID.randomUUID().toString());
        payload.put("reason", null);
        payload.put("occurredAt", Instant.now().toString());
        return payload;
    }

    private static Map<String, Object> position(UUID driver, double lat, double lng) {
        Instant now = Instant.now();
        return Map.of("driverId", driver.toString(), "sequence", 7, "latitude", lat, "longitude", lng,
                "accuracyMeters", 8.0, "deviceTimestamp", now.toString(), "serverTimestamp", now.toString());
    }

    private static Map<String, Object> tripRequested(UUID tripId, Map<String, Object> pickup,
                                                     Map<String, Object> dropoff) {
        return Map.of("tripId", tripId.toString(), "customerId", UUID.randomUUID().toString(), "serviceType", "RIDE",
                "status", "MATCHING", "pickup", pickup, "dropoff", dropoff,
                "matchingDeadline", Instant.now().plusSeconds(30).toString(), "occurredAt", Instant.now().toString());
    }

    private static Map<String, Object> cancelled(UUID tripId, UUID customer, String oldStatus, Instant acceptedAt,
                                                 Instant cancelledAt, String quoteId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripId", tripId.toString());
        payload.put("customerId", customer.toString());
        payload.put("driverId", acceptedAt == null ? null : UUID.randomUUID().toString());
        payload.put("oldStatus", oldStatus);
        payload.put("actorType", "CUSTOMER");
        payload.put("actorId", customer.toString());
        payload.put("reason", "CHANGED_MIND");
        payload.put("cancelledAt", cancelledAt.toString());
        payload.put("serviceType", "RIDE");
        if (acceptedAt != null) {
            payload.put("acceptedAt", acceptedAt.toString());
        }
        if (quoteId != null) {
            payload.put("quoteId", quoteId);
        }
        return payload;
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

    private static Map<String, Object> rule(Instant effectiveFrom) {
        return Map.of("serviceType", "RIDE", "regionCode", "DEFAULT", "baseFare", 20_000, "perKm", 5_000,
                "perMinute", 400, "minimumFare", 25_000, "effectiveFrom", effectiveFrom.toString());
    }

    private static MockHttpServletRequestBuilder validate(String quoteId, UUID customer, String serviceType) {
        return get("/internal/v1/quotes/" + quoteId)
                .param("customerId", customer.toString())
                .param("serviceType", serviceType);
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, RequestPostProcessor token)
            throws Exception {
        return mvc.perform(request.with(token));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, RequestPostProcessor token, Object body)
            throws Exception {
        return mvc.perform(request.with(token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private static JsonNode find(JsonNode rules, String serviceType, int version) {
        for (JsonNode rule : rules) {
            if (rule.path("serviceType").asText().equals(serviceType) && rule.path("version").asInt() == version) {
                return rule;
            }
        }
        throw new AssertionError("No " + serviceType + " v" + version);
    }

    private static RequestPostProcessor customerToken(UUID id) {
        return token(id, "CUSTOMER");
    }

    private static RequestPostProcessor token(UUID id, String role) {
        return jwt().jwt(j -> j.subject(id.toString())).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
