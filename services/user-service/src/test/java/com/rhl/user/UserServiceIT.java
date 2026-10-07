package com.rhl.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import com.rhl.user.application.notification.NotificationSender;
import com.rhl.user.domain.driver.UploadInspectorTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end against real PostgreSQL, Redis and Kafka (UC-01, FR-IAM, FR-EVT-002). */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhl.bootstrap.admin-email=admin@rhl.test",
        "rhl.bootstrap.admin-password=admin-password-123",
        "rhl.outbox.poll-interval=50ms",
        // Every test signs up from the same MockMvc address; the limit is exercised for resets.
        "rhl.rate-limits.registrations-per-hour=1000",
        "rhl.rate-limits.password-resets-per-hour=5"})
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

    /** MinIO as published by Chainguard (MinIO no longer ships public images); runs as root for /data. */
    @Container
    static MinIOContainer minio = new MinIOContainer(DockerImageName.parse("cgr.dev/chainguard/minio:latest")
            .asCompatibleSubstituteFor("minio/minio"))
            .withUserName("rhl-it")
            .withPassword("rhl-it-secret-123")
            .withCreateContainerCmdModifier(cmd -> cmd.withUser("0"));

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("rhl.storage.endpoint", minio::getS3URL);
        registry.add("rhl.storage.access-key", minio::getUserName);
        registry.add("rhl.storage.secret-key", minio::getPassword);
    }

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

    /** Stands in for the email/SMS provider; codes are read from its calls. */
    @MockitoBean
    NotificationSender sender;

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
        call(get("/api/v1/drivers/me/profile"), driver, null)
                .andExpect(jsonPath("$.data.missingRequirements[?(@ == 'Verify your email or phone number first')]")
                        .exists());
        verifyEmail(driver, driverEmail);
        call(get("/api/v1/drivers/me/profile"), driver, null)
                .andExpect(jsonPath("$.data.missingRequirements[?(@ == 'Verify your email or phone number first')]")
                        .doesNotExist());

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

    /** OFFERED and BUSY come from trip-service events and are republished for location-service (README §4.8). */
    @Test
    void tripServiceEventsMoveTheDriverThroughOfferedAndBusy() throws Exception {
        Map.Entry<String, String> online = onlineDriver();
        String driverId = online.getKey();
        String driver = online.getValue();
        UUID offerId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        String customerId = UUID.randomUUID().toString();

        publish("dispatch.offers.v1", driverId, "DriverOfferCreated", offerId, UUID.randomUUID(), Map.of(
                "offerId", offerId.toString(), "tripId", tripId.toString(), "driverId", driverId,
                "serviceType", "RIDE", "pickup", Map.of("latitude", 10.77, "longitude", 106.69, "address", "Q1"),
                "estimatedPickupDistanceMeters", 300, "createdAt", Instant.now().toString(),
                "expiresAt", Instant.now().plusSeconds(15).toString()));
        awaitAvailability(driver, "OFFERED");
        // Cannot go offline while holding an offer.
        call(post("/api/v1/drivers/me/availability/offline"), driver, null).andExpect(status().isConflict());

        UUID accepted = UUID.randomUUID();
        Map<String, Object> acceptedPayload = Map.of("tripId", tripId.toString(), "customerId", customerId,
                "driverId", driverId, "offerId", offerId.toString(), "serviceType", "RIDE",
                "acceptedAt", Instant.now().toString());
        publish("trip.events.v1", tripId.toString(), "TripAccepted", tripId, accepted, acceptedPayload);
        awaitAvailability(driver, "BUSY");

        // A replayed accept (same eventId) and a late offer event change nothing.
        publish("trip.events.v1", tripId.toString(), "TripAccepted", tripId, accepted, acceptedPayload);
        publish("dispatch.offers.v1", driverId, "DriverOfferExpired", offerId, UUID.randomUUID(), Map.of(
                "offerId", offerId.toString(), "tripId", tripId.toString(), "driverId", driverId,
                "expiredAt", Instant.now().toString()));

        publish("trip.events.v1", tripId.toString(), "TripCompleted", tripId, UUID.randomUUID(), Map.of(
                "tripId", tripId.toString(), "customerId", customerId, "driverId", driverId, "serviceType", "RIDE",
                "pickup", Map.of("latitude", 10.77, "longitude", 106.69, "address", "Q1"),
                "dropoff", Map.of("latitude", 10.76, "longitude", 106.68, "address", "Q5"),
                "acceptedAt", Instant.now().toString(), "completedAt", Instant.now().toString()));
        awaitAvailability(driver, "AVAILABLE");

        // The accepted offer's DriverOfferCreated arriving after the whole trip must not bring
        // OFFERED back (the two topics are not ordered against each other).
        UUID lateEvent = UUID.randomUUID();
        publish("dispatch.offers.v1", driverId, "DriverOfferCreated", offerId, lateEvent, Map.of(
                "offerId", offerId.toString(), "tripId", tripId.toString(), "driverId", driverId,
                "serviceType", "RIDE", "pickup", Map.of("latitude", 10.77, "longitude", 106.69, "address", "Q1"),
                "estimatedPickupDistanceMeters", 300, "createdAt", Instant.now().toString(),
                "expiresAt", Instant.now().plusSeconds(15).toString()));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class, lateEvent)).isEqualTo(1));
        awaitAvailability(driver, "AVAILABLE");

        // One DriverAvailabilityChanged per real change, in order, for location-service.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.queryForList("""
                        SELECT envelope->'payload'->>'newStatus' FROM outbox_events
                        WHERE message_key = ? ORDER BY id
                        """, String.class, driverId))
                .containsExactly("AVAILABLE", "OFFERED", "BUSY", "AVAILABLE"));
        assertThat(jdbc.queryForObject("""
                        SELECT envelope->'payload'->>'reason' FROM outbox_events
                        WHERE message_key = ? ORDER BY id DESC LIMIT 1
                        """, String.class, driverId)).isEqualTo("TRIP_COMPLETED");
        call(post("/api/v1/drivers/me/availability/offline"), driver, null)
                .andExpect(jsonPath("$.data.availability").value("OFFLINE"));
    }

    /** FR-IAM: verify email/phone with a one-time code sent to the address on the account. */
    @Test
    void contactsAreVerifiedWithAOneTimeCode() throws Exception {
        String email = unique("verify");
        register(email, "CUSTOMER");
        String token = login(email, PASSWORD);
        call(get("/api/v1/users/me"), token, null).andExpect(jsonPath("$.data.emailVerified").value(false));

        call(post("/api/v1/users/me/contacts/email/verification"), token, null)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.channel").value("EMAIL"))
                .andExpect(jsonPath("$.data.destination").value(email.charAt(0) + "***@rhl.test"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(600));
        String first = codeSentTo(email);
        // One code per cooldown.
        call(post("/api/v1/users/me/contacts/email/verification"), token, null)
                .andExpect(status().isTooManyRequests());
        // No phone on the account; unknown channel.
        call(post("/api/v1/users/me/contacts/phone/verification"), token, null)
                .andExpect(status().isUnprocessableEntity());
        call(post("/api/v1/users/me/contacts/fax/verification"), token, null)
                .andExpect(status().isUnprocessableEntity());

        // Five wrong entries discard the code: even the right one stops working.
        String wrong = first.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            call(post("/api/v1/users/me/contacts/email/verification/confirm"), token, Map.of("code", wrong))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        call(post("/api/v1/users/me/contacts/email/verification/confirm"), token, Map.of("code", first))
                .andExpect(status().isBadRequest());

        redisTemplateDeleteCooldowns();
        call(post("/api/v1/users/me/contacts/email/verification"), token, null).andExpect(status().isAccepted());
        call(post("/api/v1/users/me/contacts/email/verification/confirm"), token, Map.of("code", codeSentTo(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailVerified").value(true));
        call(post("/api/v1/users/me/contacts/email/verification"), token, null).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_records WHERE action = 'CONTACT_VERIFIED' "
                + "AND actor_id = (SELECT id FROM users WHERE email = ?)", Integer.class, email)).isEqualTo(1);
    }

    /** FR-IAM: password reset by code; the answer never reveals whether an account exists. */
    @Test
    void aForgottenPasswordIsResetWithACodeAndEndsEverySession() throws Exception {
        String email = unique("forgot");
        register(email, "CUSTOMER");
        JsonNode session = data(call(post("/api/v1/auth/login"), null,
                Map.of("identifier", email, "password", PASSWORD)).andExpect(status().isOk()));
        String ghost = unique("ghost");

        JsonNode known = data(call(post("/api/v1/auth/password-reset"), null, Map.of("identifier", email))
                .andExpect(status().isAccepted()));
        JsonNode unknown = data(call(post("/api/v1/auth/password-reset"), null, Map.of("identifier", ghost))
                .andExpect(status().isAccepted()));
        assertThat(unknown).isEqualTo(known);
        verify(sender, never()).sendCode(any(), eq(ghost), any(), any(), any());
        String code = codeSentTo(email);
        // Asking again within the cooldown answers the same but sends nothing.
        call(post("/api/v1/auth/password-reset"), null, Map.of("identifier", email)).andExpect(status().isAccepted());
        verify(sender, times(1)).sendCode(any(), eq(email), any(), any(), any());

        // A weak password is refused before the code is used up.
        call(post("/api/v1/auth/password-reset/confirm"), null,
                Map.of("identifier", email, "code", code, "newPassword", "short"))
                .andExpect(status().isUnprocessableEntity());
        call(post("/api/v1/auth/password-reset/confirm"), null,
                Map.of("identifier", ghost, "code", code, "newPassword", "brand-new-pass"))
                .andExpect(status().isBadRequest());
        call(post("/api/v1/auth/password-reset/confirm"), null,
                Map.of("identifier", email, "code", code, "newPassword", "brand-new-pass"))
                .andExpect(status().isNoContent());
        // Used once only.
        call(post("/api/v1/auth/password-reset/confirm"), null,
                Map.of("identifier", email, "code", code, "newPassword", "another-pass-1"))
                .andExpect(status().isBadRequest());

        call(post("/api/v1/auth/login"), null, Map.of("identifier", email, "password", PASSWORD))
                .andExpect(status().isUnauthorized());
        call(post("/api/v1/auth/refresh"), null, Map.of("refreshToken", session.path("refreshToken").asText()))
                .andExpect(status().isUnauthorized());
        String token = login(email, "brand-new-pass");
        // The code reached the inbox, so the email counts as verified.
        call(get("/api/v1/users/me"), token, null).andExpect(jsonPath("$.data.emailVerified").value(true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_records WHERE action = 'PASSWORD_RESET' "
                + "AND actor_id = (SELECT id FROM users WHERE email = ?)", Integer.class, email)).isEqualTo(1);
    }

    @Test
    void passwordResetRequestsAreLimitedPerClient() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/password-reset").with(fromAddress("203.0.113.7"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("identifier", unique("probe")))))
                    .andExpect(status().isAccepted());
        }
        mvc.perform(post("/api/v1/auth/password-reset").with(fromAddress("203.0.113.7"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("identifier", unique("probe")))))
                .andExpect(status().isTooManyRequests());
        // Another client is not affected.
        mvc.perform(post("/api/v1/auth/password-reset").with(fromAddress("203.0.113.8"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("identifier", unique("probe")))))
                .andExpect(status().isAccepted());
    }

    /** FR-DRV, README §9: checked uploads, one document per file, private downloads, audited reviews. */
    @Test
    void documentFilesAreCheckedStoredPrivatelyAndAuditedWhenReviewed() throws Exception {
        String email = unique("files");
        String driverId = register(email, "DRIVER").path("id").asText();
        String driver = login(email, PASSWORD);
        call(post("/api/v1/drivers/me/profile"), driver, Map.of(
                "fullName", "Le Van File", "dateOfBirth", "1990-05-01", "serviceTypes", List.of("RIDE")))
                .andExpect(status().isCreated());
        String otherEmail = unique("other");
        register(otherEmail, "DRIVER");
        String other = login(otherEmail, PASSWORD);
        call(post("/api/v1/drivers/me/profile"), other, Map.of(
                "fullName", "Pham Van Khac", "dateOfBirth", "1991-05-01", "serviceTypes", List.of("RIDE")))
                .andExpect(status().isCreated());

        byte[] photo = UploadInspectorTest.pngWithText("GPSLatitude", "10.7769");
        JsonNode uploaded = data(upload(driver, "license.png", photo)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.contentType").value("image/png")));
        String fileId = uploaded.path("fileId").asText();

        // Refused: not an image whatever its name, name not matching content, wrong role.
        upload(driver, "license.png", "MZ\u0090 not an image".getBytes(StandardCharsets.ISO_8859_1))
                .andExpect(status().isUnprocessableEntity());
        upload(driver, "license.pdf", photo).andExpect(status().isUnprocessableEntity());
        String customerEmail = unique("cust");
        register(customerEmail, "CUSTOMER");
        upload(login(customerEmail, PASSWORD), "license.png", photo).andExpect(status().isForbidden());

        // The file backs one document of its own driver.
        Map<String, Object> licence = new java.util.HashMap<>(Map.of("type", "DRIVER_LICENSE",
                "documentNumber", "B2-123456", "expiresOn", LocalDate.now().plusYears(2).toString(), "fileId", fileId));
        call(post("/api/v1/drivers/me/documents"), other, licence).andExpect(status().isNotFound());
        String documentId = data(call(post("/api/v1/drivers/me/documents"), driver, licence)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.hasFile").value(true))).path("id").asText();
        licence.put("type", "NATIONAL_ID");
        licence.remove("expiresOn");
        call(post("/api/v1/drivers/me/documents"), driver, licence).andExpect(status().isConflict());

        // Downloads: the driver and reviewers only, always as an attachment, metadata gone.
        byte[] stored = mvc.perform(get("/api/v1/drivers/me/documents/" + documentId + "/file")
                        .header("Authorization", "Bearer " + driver))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("GPSLatitude");
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stored)))
                .isEqualTo(uploaded.path("sha256").asText());
        call(get("/api/v1/drivers/me/documents/" + documentId + "/file"), other, null)
                .andExpect(status().isNotFound());
        String admin = login("admin@rhl.test", "admin-password-123");
        mvc.perform(get("/api/v1/admin/drivers/" + driverId + "/documents/" + documentId + "/file")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        call(get("/api/v1/admin/drivers/" + driverId + "/documents/" + documentId + "/file"), driver, null)
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_records WHERE action = 'DOCUMENT_FILE_VIEWED' "
                + "AND target_id = ?", Integer.class, documentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT object_key FROM document_files WHERE id = ?::uuid", String.class,
                fileId)).isEqualTo("drivers/" + driverId + "/" + fileId);
    }

    // ---- helpers --------------------------------------------------------------------------

    private ResultActions upload(String token, String name, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/v1/drivers/me/documents/files")
                .file(new MockMultipartFile("file", name, "application/octet-stream", content))
                .header("Authorization", "Bearer " + token));
    }

    private void verifyEmail(String token, String email) throws Exception {
        call(post("/api/v1/users/me/contacts/email/verification"), token, null).andExpect(status().isAccepted());
        call(post("/api/v1/users/me/contacts/email/verification/confirm"), token, Map.of("code", codeSentTo(email)))
                .andExpect(jsonPath("$.data.emailVerified").value(true));
    }

    /** The last code sent to {@code destination}. */
    private String codeSentTo(String destination) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).sendCode(any(), eq(destination), any(), code.capture(), any());
        return code.getValue();
    }

    private void redisTemplateDeleteCooldowns() {
        java.util.Set<String> keys = redisTemplate.keys("auth:otp-cooldown:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private static RequestPostProcessor fromAddress(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    /** Registers, onboards, approves and puts a driver online; returns (driverId, access token). */
    private Map.Entry<String, String> onlineDriver() throws Exception {
        String email = unique("driver");
        String driverId = register(email, "DRIVER").path("id").asText();
        String driver = login(email, PASSWORD);
        verifyEmail(driver, email);
        call(post("/api/v1/drivers/me/profile"), driver, Map.of(
                "fullName", "Tran Van Xe", "dateOfBirth", "1990-05-01", "serviceTypes", List.of("RIDE")))
                .andExpect(status().isCreated());
        String plate = "59X" + (100000 + (int) (Math.random() * 899999));
        String vehicleId = data(call(post("/api/v1/drivers/me/vehicles"), driver, Map.of(
                "type", "MOTORBIKE", "plateNumber", plate, "brand", "Honda", "model", "Wave",
                "color", "Red", "manufactureYear", 2021)).andExpect(status().isCreated())).path("id").asText();
        String nextYear = LocalDate.now().plusYears(1).toString();
        submitDocument(driver, "NATIONAL_ID", null, null);
        submitDocument(driver, "DRIVER_LICENSE", null, nextYear);
        submitDocument(driver, "VEHICLE_REGISTRATION", vehicleId, null);
        submitDocument(driver, "VEHICLE_INSURANCE", vehicleId, nextYear);
        call(post("/api/v1/drivers/me/profile/submit"), driver, null).andExpect(status().isOk());
        String admin = login("admin@rhl.test", "admin-password-123");
        call(post("/api/v1/admin/drivers/" + driverId + "/decisions"), admin,
                Map.of("verdict", "APPROVE", "profileVersion", 1)).andExpect(status().isOk());
        call(post("/api/v1/drivers/me/availability/online"), driver,
                Map.of("vehicleId", vehicleId, "serviceTypes", List.of("RIDE")))
                .andExpect(jsonPath("$.data.availability").value("AVAILABLE"));
        return Map.entry(driverId, driver);
    }

    private void awaitAvailability(String driverToken, String expected) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                call(get("/api/v1/drivers/me/profile"), driverToken, null)
                        .andExpect(jsonPath("$.data.availability").value(expected)));
    }

    /** Publishes an event as trip-service would, including the envelope trip-service's schemas require. */
    private void publish(String topic, String key, String type, UUID aggregateId, UUID eventId,
                         Map<String, Object> payload) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", type);
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("correlationId", UUID.randomUUID().toString());
        envelope.put("producer", "trip-service");
        envelope.put("aggregateId", aggregateId.toString());
        envelope.put("aggregateVersion", 1);
        envelope.put("payload", payload);
        kafkaTemplate.send(topic, key, json.writeValueAsString(envelope)).get();
    }


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
