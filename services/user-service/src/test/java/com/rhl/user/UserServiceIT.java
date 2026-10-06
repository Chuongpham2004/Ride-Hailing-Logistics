package com.rhl.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end against real PostgreSQL, Redis and Kafka (UC-01, FR-IAM, FR-EVT-002). */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhl.bootstrap.admin-email=admin@rhl.test",
        "rhl.bootstrap.admin-password=admin-password-123",
        "rhl.outbox.poll-interval=50ms"})
class UserServiceIT {

    private static final String PASSWORD = "s3cret-pass";

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

    @Test
    void driverOnboardingReviewAndGoingOnlinePublishesAnEvent() throws Exception {
        String driverEmail = unique("driver");
        String driverId = register(driverEmail, "DRIVER").path("id").asText();
        String driver = login(driverEmail, PASSWORD);

        call(post("/api/v1/drivers/me/profile"), driver, Map.of(
                "fullName", "Nguyen Van Tai", "dateOfBirth", "1990-05-01", "serviceTypes", List.of("RIDE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reviewStatus").value("DRAFT"));

        // Incomplete profiles cannot be submitted (UC-01).
        call(post("/api/v1/drivers/me/profile/submit"), driver, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

        String plate = "59X" + (100000 + (int) (Math.random() * 899999));
        String vehicleId = data(call(post("/api/v1/drivers/me/vehicles"), driver, Map.of(
                "type", "MOTORBIKE", "plateNumber", plate, "brand", "Honda", "model", "Wave",
                "color", "Red", "manufactureYear", 2021))
                .andExpect(status().isCreated())).path("id").asText();

        String nextYear = LocalDate.now().plusYears(1).toString();
        submitDocument(driver, "NATIONAL_ID", null, null);
        submitDocument(driver, "DRIVER_LICENSE", null, nextYear);
        submitDocument(driver, "VEHICLE_REGISTRATION", vehicleId, null);
        submitDocument(driver, "VEHICLE_INSURANCE", vehicleId, nextYear);

        call(post("/api/v1/drivers/me/profile/submit"), driver, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.profileVersion").value(1));

        // Not approved yet: cannot go online.
        call(post("/api/v1/drivers/me/availability/online"), driver,
                Map.of("vehicleId", vehicleId, "serviceTypes", List.of("RIDE")))
                .andExpect(status().isUnprocessableEntity());

        String admin = login("admin@rhl.test", "admin-password-123");
        call(get("/api/v1/admin/drivers/" + driverId), admin, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.documents[0].documentNumber").value("DOC12345678"));
        // A stale profile version is refused.
        call(post("/api/v1/admin/drivers/" + driverId + "/decisions"), admin,
                Map.of("verdict", "APPROVE", "profileVersion", 2))
                .andExpect(status().isConflict());
        call(post("/api/v1/admin/drivers/" + driverId + "/decisions"), admin,
                Map.of("verdict", "APPROVE", "profileVersion", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("APPROVED"));

        // The driver sees masked document numbers.
        call(get("/api/v1/drivers/me/profile"), driver, null)
                .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.documents[0].documentNumber").value("*******5678"));

        call(post("/api/v1/drivers/me/availability/online"), driver,
                Map.of("vehicleId", vehicleId, "serviceTypes", List.of("RIDE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availability").value("AVAILABLE"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE message_key = ? AND event_type = 'DriverAvailabilityChanged'",
                String.class, driverId)).isEqualTo("SENT"));

        ConsumerRecord<String, String> record = consumeOne("driver.events.v1", driverId);
        assertThat(new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                .isEqualTo("DriverAvailabilityChanged");
        JsonNode event = json.readTree(record.value());
        assertThat(event.path("producer").asText()).isEqualTo("user-service");
        assertThat(event.path("payload").path("newStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(event.path("payload").path("vehicleId").asText()).isEqualTo(vehicleId);

        // A driver on duty can go offline; repeating it is a no-op.
        call(post("/api/v1/drivers/me/availability/offline"), driver, null)
                .andExpect(jsonPath("$.data.availability").value("OFFLINE"));
        call(post("/api/v1/drivers/me/availability/offline"), driver, null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE message_key = ?",
                Integer.class, driverId)).isEqualTo(2);
    }

    @Test
    void endpointsEnforceAuthenticationAndRoles() throws Exception {
        String email = unique("customer");
        register(email, "CUSTOMER");
        String customer = login(email, PASSWORD);

        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        call(get("/api/v1/admin/drivers"), customer, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        call(get("/api/v1/drivers/me/profile"), customer, null).andExpect(status().isForbidden());
        call(get("/api/v1/users/me"), customer, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.roles[0]").value("CUSTOMER"));
    }

    @Test
    void signUpRejectsDuplicatesAndStaffRoles() throws Exception {
        String email = unique("dup");
        register(email, "CUSTOMER");

        call(post("/api/v1/auth/register"), null, registration(email.toUpperCase(), "CUSTOMER"))
                .andExpect(status().isConflict());
        call(post("/api/v1/auth/register"), null, registration(unique("staff"), "ADMINISTRATOR"))
                .andExpect(status().isUnprocessableEntity());
        call(post("/api/v1/auth/register"), null, Map.of("password", PASSWORD, "fullName", "X", "role", "CUSTOMER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void repeatedWrongPasswordsLockTheIdentifierWithoutRevealingIt() throws Exception {
        String email = unique("victim");
        register(email, "CUSTOMER");

        for (int i = 0; i < 5; i++) {
            call(post("/api/v1/auth/login"), null, Map.of("identifier", email, "password", "wrong-password"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        call(post("/api/v1/auth/login"), null, Map.of("identifier", email, "password", PASSWORD))
                .andExpect(status().isTooManyRequests());
        // Unknown accounts get the same answer as wrong passwords.
        call(post("/api/v1/auth/login"), null, Map.of("identifier", unique("ghost"), "password", PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void refreshTokensRotateAndReuseRevokesTheFamily() throws Exception {
        String email = unique("rotate");
        register(email, "CUSTOMER");
        JsonNode first = data(call(post("/api/v1/auth/login"), null,
                Map.of("identifier", email, "password", PASSWORD)).andExpect(status().isOk()));
        String r1 = first.path("refreshToken").asText();

        String r2 = data(call(post("/api/v1/auth/refresh"), null, Map.of("refreshToken", r1))
                .andExpect(status().isOk())).path("refreshToken").asText();
        assertThat(r2).isNotEqualTo(r1);

        call(post("/api/v1/auth/refresh"), null, Map.of("refreshToken", r1)).andExpect(status().isUnauthorized());
        // Reuse of r1 revoked the whole family, including r2.
        call(post("/api/v1/auth/refresh"), null, Map.of("refreshToken", r2)).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheAccessToken() throws Exception {
        String email = unique("logout");
        register(email, "CUSTOMER");
        String token = login(email, PASSWORD);

        call(post("/api/v1/auth/logout"), token, Map.of()).andExpect(status().isNoContent());
        call(get("/api/v1/users/me"), token, null).andExpect(status().isUnauthorized());
    }

    // ---- helpers --------------------------------------------------------------------------

    private void submitDocument(String token, String type, String vehicleId, String expiresOn) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("type", type);
        body.put("documentNumber", "DOC12345678");
        body.put("vehicleId", vehicleId);
        body.put("expiresOn", expiresOn);
        call(post("/api/v1/drivers/me/documents"), token, body).andExpect(status().isCreated());
    }

    private JsonNode register(String email, String role) throws Exception {
        return data(call(post("/api/v1/auth/register"), null, registration(email, role))
                .andExpect(status().isCreated()));
    }

    private static Map<String, Object> registration(String email, String role) {
        return Map.of("email", email, "password", PASSWORD, "fullName", "Test User", "role", role);
    }

    private String login(String identifier, String password) throws Exception {
        return data(call(post("/api/v1/auth/login"), null, Map.of("identifier", identifier, "password", password))
                .andExpect(status().isOk())).path("accessToken").asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@rhl.test";
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
