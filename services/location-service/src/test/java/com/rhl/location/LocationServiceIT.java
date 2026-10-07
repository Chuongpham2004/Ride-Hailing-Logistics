package com.rhl.location;

import com.rhl.common.id.UuidV7;
import com.rhl.location.infrastructure.messaging.Topics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end against real PostgreSQL, Redis and Kafka (FR-LOC, CON-04, DR-GEO). */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        // Tokens are injected with spring-security-test, so the JWKS endpoint is never called.
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks.json",
        "rhl.telemetry.cleanup-interval=500ms"})
class LocationServiceIT {

    // Ben Thanh market, District 1, Ho Chi Minh City.
    private static final double LAT = 10.7725;
    private static final double LNG = 106.6980;

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
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void onlineDriverReportsAreIndexedSearchableAndPublished() throws Exception {
        UUID driver = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        goOnline(driver, vehicle, List.of("RIDE", "DELIVERY"), 1);

        report(driver, 1, LAT, LNG, 8, Instant.now())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("CURRENT"));

        // ~300 m north of the pickup point.
        JsonNode nearby = data(mvc.perform(get("/internal/v1/drivers/nearby")
                .param("latitude", "10.7698").param("longitude", Double.toString(LNG))
                .param("serviceType", "RIDE").param("radiusMeters", "2000")));
        JsonNode hit = find(nearby, driver);
        assertThat(hit).isNotNull();
        assertThat(hit.path("distanceMeters").asLong()).isBetween(250L, 350L);
        assertThat(hit.path("availability").asString()).isEqualTo("AVAILABLE");
        assertThat(hit.path("vehicleId").asString()).isEqualTo(vehicle.toString());

        ConsumerRecord<String, String> published = consumeOne(Topics.LOCATION_UPDATES, driver.toString());
        JsonNode event = json.readTree(published.value());
        assertThat(event.path("eventType").asString()).isEqualTo("DriverLocationUpdated");
        assertThat(event.path("payload").path("sequence").asLong()).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT quality FROM telemetry_history WHERE driver_id = ?", String.class,
                driver)).isEqualTo("CURRENT");

        mvc.perform(get("/api/v1/locations/me").with(driverToken(driver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sequence").value(1));
    }

    @Test
    void duplicatesLateAndImplausibleReportsNeverMoveTheCurrentPosition() throws Exception {
        UUID driver = UUID.randomUUID();
        goOnline(driver, UUID.randomUUID(), List.of("RIDE"), 1);
        Instant now = Instant.now();

        report(driver, 5, LAT, LNG, 8, now).andExpect(jsonPath("$.data.outcome").value("CURRENT"));
        report(driver, 5, LAT + 0.001, LNG, 8, now).andExpect(jsonPath("$.data.outcome").value("DUPLICATE"));
        report(driver, 4, LAT + 0.001, LNG, 8, now).andExpect(jsonPath("$.data.outcome").value("DUPLICATE"));
        report(driver, 6, LAT + 0.001, LNG, 8, now.minusSeconds(300))
                .andExpect(jsonPath("$.data.outcome").value("BACKFILL"));
        report(driver, 7, LAT + 0.1, LNG, 8, now.plusMillis(500))
                .andExpect(jsonPath("$.data.outcome").value("SUSPICIOUS"));
        report(driver, 8, LAT, LNG, 8, now.plusSeconds(60))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

        mvc.perform(get("/internal/v1/drivers/" + driver + "/location"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sequence").value(5))
                .andExpect(jsonPath("$.data.latitude").value(LAT));
        assertThat(jdbc.queryForList("SELECT quality FROM telemetry_history WHERE driver_id = ? ORDER BY sequence",
                String.class, driver)).containsExactly("CURRENT", "BACKFILL", "SUSPICIOUS");
    }

    @Test
    void offlineDriversAreRemovedAndCannotReport() throws Exception {
        UUID driver = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        goOnline(driver, vehicle, List.of("RIDE"), 1);
        report(driver, 1, LAT, LNG, 8, Instant.now()).andExpect(status().isOk());
        assertThat(isIndexed(driver)).isTrue();

        publishAvailability(driver, "AVAILABLE", "OFFLINE", vehicle, List.of("RIDE"), 2);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(isIndexed(driver)).isFalse());

        report(driver, 2, LAT, LNG, 8, Instant.now())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("DRIVER_OFFLINE")));

        // A replayed older event must not bring the driver back (FR-EVT-006).
        publishAvailability(driver, "OFFLINE", "AVAILABLE", vehicle, List.of("RIDE"), 1);
        Thread.sleep(1500);
        assertThat(jdbc.queryForObject("SELECT availability FROM driver_presence WHERE driver_id = ?", String.class,
                driver)).isEqualTo("OFFLINE");
    }

    @Test
    void busyDriversKeepTheirPositionButAreNotMatchable() throws Exception {
        UUID driver = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        goOnline(driver, vehicle, List.of("RIDE"), 1);
        report(driver, 1, LAT, LNG, 8, Instant.now()).andExpect(status().isOk());

        publishAvailability(driver, "AVAILABLE", "BUSY", vehicle, List.of("RIDE"), 2);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(isIndexed(driver)).isFalse());

        report(driver, 2, LAT + 0.0002, LNG, 8, Instant.now())
                .andExpect(jsonPath("$.data.outcome").value("CURRENT"));
        assertThat(isIndexed(driver)).isFalse();
    }

    @Test
    void streamedReportsFromRealtimeGatewayUseTheSamePipeline() throws Exception {
        UUID driver = UUID.randomUUID();
        goOnline(driver, UUID.randomUUID(), List.of("DELIVERY"), 1);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("driverId", driver.toString());
        payload.put("messageId", UuidV7.randomString());
        payload.put("sequence", 42);
        payload.put("latitude", LAT);
        payload.put("longitude", LNG);
        payload.put("accuracyMeters", 6.0);
        payload.put("deviceTimestamp", Instant.now().toString());
        payload.put("receivedAt", Instant.now().toString());
        kafkaTemplate.send(Topics.TELEMETRY_RAW, driver.toString(),
                envelope("DriverLocationReported", "realtime-gateway", driver, 42, payload)).get();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                mvc.perform(get("/internal/v1/drivers/" + driver + "/location"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.sequence").value(42)));
        assertThat(redisTemplate.opsForZSet().score("geo:drivers:DELIVERY", driver.toString())).isNotNull();
    }

    @Test
    void staleDriversArePrunedFromTheIndex() throws Exception {
        UUID driver = UUID.randomUUID();
        goOnline(driver, UUID.randomUUID(), List.of("RIDE"), 1);
        report(driver, 1, LAT, LNG, 8, Instant.now()).andExpect(status().isOk());
        assertThat(isIndexed(driver)).isTrue();

        // Pretend the last report arrived long ago: the pruner must drop the member (FR-LOC-008).
        redisTemplate.opsForZSet().add("geo:lastseen:RIDE", driver.toString(), 0);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(isIndexed(driver)).isFalse());
    }

    @Test
    void onlyDriversCanReportAndNearbyValidatesItsInput() throws Exception {
        mvc.perform(post("/api/v1/locations/me").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/locations/me")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body(1, LAT, LNG, 8, Instant.now()))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/locations/me").with(driverToken(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body(1, 95, LNG, 8, Instant.now()))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/internal/v1/drivers/nearby").param("latitude", "10.77").param("longitude", "106.69")
                        .param("serviceType", "RIDE").param("radiusMeters", "50000"))
                .andExpect(status().isUnprocessableEntity());
    }

    private void goOnline(UUID driver, UUID vehicle, List<String> types, long version) {
        publishAvailability(driver, "OFFLINE", "AVAILABLE", vehicle, types, version);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM driver_presence WHERE driver_id = ? AND aggregate_version = ?", Integer.class,
                driver, version)).isEqualTo(1));
    }

    private void publishAvailability(UUID driver, String oldStatus, String newStatus, UUID vehicle,
                                     List<String> types, long version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("driverId", driver.toString());
        payload.put("oldStatus", oldStatus);
        payload.put("newStatus", newStatus);
        payload.put("serviceTypes", types);
        payload.put("vehicleId", vehicle.toString());
        payload.put("reason", null);
        payload.put("occurredAt", Instant.now().toString());
        try {
            kafkaTemplate.send(Topics.DRIVER_EVENTS, driver.toString(),
                    envelope("DriverAvailabilityChanged", "user-service", driver, version, payload)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String envelope(String type, String producer, UUID aggregateId, long version, Map<String, Object> payload)
            throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UuidV7.randomString());
        envelope.put("eventType", type);
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("correlationId", UuidV7.randomString());
        envelope.put("producer", producer);
        envelope.put("aggregateId", aggregateId.toString());
        envelope.put("aggregateVersion", version);
        envelope.put("payload", payload);
        return json.writeValueAsString(envelope);
    }

    private ResultActions report(UUID driver, long sequence, double lat, double lng, double accuracy,
                                 Instant deviceTime) throws Exception {
        return mvc.perform(post("/api/v1/locations/me").with(driverToken(driver))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body(sequence, lat, lng, accuracy, deviceTime))));
    }

    private static Map<String, Object> body(long sequence, double lat, double lng, double accuracy,
                                            Instant deviceTime) {
        return Map.of("sequence", sequence, "latitude", lat, "longitude", lng, "accuracyMeters", accuracy,
                "deviceTimestamp", deviceTime.toString());
    }

    private static RequestPostProcessor driverToken(UUID driver) {
        return jwt().jwt(j -> j.subject(driver.toString())).authorities(new SimpleGrantedAuthority("ROLE_DRIVER"));
    }

    private boolean isIndexed(UUID driver) {
        return redisTemplate.opsForZSet().score("geo:drivers:RIDE", driver.toString()) != null;
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data");
    }

    private static JsonNode find(JsonNode list, UUID driver) {
        for (JsonNode item : list) {
            if (item.path("driverId").asString().equals(driver.toString())) {
                return item;
            }
        }
        return null;
    }

    private static ConsumerRecord<String, String> consumeOne(String topic, String key) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No record with key " + key + " on " + topic);
    }
}
